package dev.connectquick.controller.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * What the client is actually connected to. [READY] means the signalling server accepted our
 * identity (`hello.ack`) — a bare WebSocket connection is NOT enough to call the app connected,
 * and even READY is only the signalling leg: a control session with a PC is a separate state.
 */
enum class SignalingState { DISCONNECTED, CONNECTING, AUTHENTICATING, READY }

/** Answers the server's `hello.challenge`; supplied by whoever owns the device identity. */
fun interface HelloAuthenticator {
    fun buildHello(serverChallenge: String): JsonObject
}

/** Handle returned by every registration so listeners can be removed (sessions are short-lived, the client is not). */
fun interface Subscription {
    fun cancel()
}

/**
 * WebSocket client for the ConnectQuick backend (Protocol §6 state machine). Owns:
 *  - connect/reconnect with exponential backoff + jitter (capped at 30 s) that only resets after a
 *    successful authentication, so a server that accepts sockets but rejects us is not hammered;
 *  - the `hello` handshake when an [authenticator] is set;
 *  - a 15 s heartbeat and dead-connection detection (nothing received for 40 s => drop and reconnect);
 *  - isolation of handler failures: a throwing handler is reported through [onFault] and never
 *    kills the reader thread;
 *  - immediate reconnection on [notifyNetworkChanged] (Wi-Fi <-> mobile data).
 * Mirrors `WindowsHost/.../Net/BackendClient.cs`.
 */
class BackendClient(
    private val url: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    /** Test seams for partition detection; production uses the defaults. */
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val deadAfterMs: Long = DEAD_AFTER_MS,
) {
    @Volatile var authenticator: HelloAuthenticator? = null

    /**
     * Other relays that reach the same PC (e.g. the PC's own LAN relay next to the cloud one). On every
     * connection attempt all of them and [url] are tried at once and the first to answer is used, so at
     * home the control stays on the local network and anywhere else it falls back to the cloud with no delay.
     */
    @Volatile var alternateUrls: List<String> = emptyList()
    @Volatile var state: SignalingState = SignalingState.DISCONNECTED
        private set
    @Volatile var lastRttMs: Int = -1
        private set

    /** Legacy single-callback form, kept for the pairing screen. Fires true only once authenticated. */
    @Volatile var onConnectionChanged: ((Boolean) -> Unit)? = null
    @Volatile var onFault: ((String) -> Unit)? = null
    /** Another instance with the same identity connected and the relay closed this one (code 4010). Reconnecting would only fight it. */
    @Volatile var onReplaced: (() -> Unit)? = null
    @Volatile private var replaced = false

    private val json = Json { ignoreUnknownKeys = true }
    private val handlers = HashMap<String, CopyOnWriteArrayList<(JsonObject) -> Unit>>()
    private val stateListeners = CopyOnWriteArrayList<(SignalingState) -> Unit>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var abortCurrent: (() -> Unit)? = null
    private val lastRxNanos = AtomicLong(System.nanoTime())
    private val pingSentNanos = AtomicLong(0)
    private var heartbeatSeq = 0
    private var wasReady = false

    fun on(type: String, handler: (JsonObject) -> Unit): Subscription {
        synchronized(handlers) { handlers.getOrPut(type) { CopyOnWriteArrayList() }.add(handler) }
        return Subscription { synchronized(handlers) { handlers[type]?.remove(handler) } }
    }

    fun addStateListener(listener: (SignalingState) -> Unit): Subscription {
        stateListeners.add(listener)
        return Subscription { stateListeners.remove(listener) }
    }

    private fun setState(next: SignalingState) {
        if (state == next) return
        state = next
        stateListeners.forEach { runCatching { it(next) } }
        val ready = next == SignalingState.READY
        if (ready != wasReady) {
            wasReady = ready
            onConnectionChanged?.let { runCatching { it(ready) } }
        }
    }

    /** Connects, reconnects with backoff on failure, and never returns until its coroutine is cancelled. */
    suspend fun runForever() {
        var attempt = 0
        try {
            while (currentCoroutineContext().isActive) {
                var authenticated = false
                try {
                    authenticated = connectOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFault?.invoke("signalling connection failed: ${e.javaClass.simpleName}")
                }
                setState(SignalingState.DISCONNECTED)
                if (replaced) {
                    onFault?.invoke("replaced by another instance of this device; not reconnecting")
                    onReplaced?.invoke()
                    break
                }
                if (!currentCoroutineContext().isActive) break
                attempt = if (authenticated) 1 else attempt + 1
                val wait = backoffMs(attempt)
                // A network change cuts the wait short and resets the backoff.
                val woken = withTimeoutOrNull(wait) { wake.receive(); true } ?: false
                if (woken) attempt = 0
            }
        } finally {
            setState(SignalingState.DISCONNECTED)
        }
    }

    /** The OS reported a different network: drop the current socket and reconnect right away. */
    fun notifyNetworkChanged() {
        wake.trySend(Unit)
        abortCurrent?.invoke()
    }

    /** The first reachable relay among [alternateUrls] and [url] (which is also the fallback when none answers in time). */
    private suspend fun pickUrl(): String {
        val candidates = (alternateUrls + url).distinct()
        if (candidates.size < 2) return url
        val winner = CompletableDeferred<String>()
        val probes = candidates.map { candidate ->
            val request = runCatching { Request.Builder().url(candidate).build() }.getOrNull() ?: return@map null
            httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    winner.complete(candidate)
                    webSocket.cancel()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {} // unreachable here: another one wins
            })
        }
        try {
            return withTimeoutOrNull(PROBE_TIMEOUT_MS) { winner.await() } ?: url
        } finally {
            probes.forEach { it?.cancel() }
        }
    }

    private suspend fun connectOnce(): Boolean = coroutineScope {
        val closed = CompletableDeferred<Unit>()
        var authenticated = false
        lastRxNanos.set(System.nanoTime())
        setState(SignalingState.CONNECTING)
        val request = Request.Builder().url(pickUrl()).build()
        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                lastRxNanos.set(System.nanoTime())
                setState(if (authenticator == null) SignalingState.READY else SignalingState.AUTHENTICATING)
                if (authenticator == null) authenticated = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastRxNanos.set(System.nanoTime())
                if (text.length > MAX_INBOUND_CHARS) {
                    webSocket.cancel()
                    return
                }
                if (dispatch(text)) authenticated = true
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code == CLOSE_REPLACED) replaced = true
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code == CLOSE_REPLACED) replaced = true
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.completeExceptionally(t)
            }
        })
        abortCurrent = { ws.cancel() }
        val watchdog = launch { watchdogLoop { ws.cancel() } }
        try {
            closed.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the reconnect loop reports and retries
        } finally {
            watchdog.cancel()
            socket = null
            abortCurrent = null
            ws.cancel()
        }
        authenticated
    }

    private suspend fun watchdogLoop(kill: () -> Unit) {
        val authDeadline = System.nanoTime() + AUTH_TIMEOUT_MS * 1_000_000
        while (true) {
            delay(1000)
            if (state == SignalingState.AUTHENTICATING && System.nanoTime() > authDeadline) {
                onFault?.invoke("authentication with the backend timed out")
                kill()
                return
            }
            val sinceRxMs = (System.nanoTime() - lastRxNanos.get()) / 1_000_000
            if (sinceRxMs > deadAfterMs) {
                onFault?.invoke("backend connection is dead (no traffic); reconnecting")
                kill()
                return
            }
            val margin = minOf(1000L, heartbeatMs / 2)
            if (state == SignalingState.READY && sinceRxMs >= heartbeatMs - margin) {
                pingSentNanos.set(System.nanoTime())
                if (!trySend("heartbeat.ping", buildJsonObject { put("seq", heartbeatSeq++) })) {
                    kill()
                    return
                }
            }
        }
    }

    /** @return true when this message authenticated the connection (hello.ack accepted). */
    private fun dispatch(text: String): Boolean {
        val envelope = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return false // a well-behaved backend never sends this; ignore defensively
        }
        val type = envelope["type"]?.jsonPrimitive?.content ?: return false
        val payload = envelope["payload"] as? JsonObject ?: JsonObject(emptyMap())
        var authenticatedNow = false

        when (type) {
            "hello.challenge" -> authenticator?.let { auth ->
                guarded { send("hello", auth.buildHello(payload.getValue("serverChallenge").jsonPrimitive.content)) }
            }
            "hello.ack" -> if (authenticator != null && payload["accepted"]?.jsonPrimitive?.booleanOrNull == true) {
                authenticatedNow = true
                setState(SignalingState.READY)
            }
            "heartbeat.pong" -> {
                val sent = pingSentNanos.get()
                if (sent != 0L) lastRttMs = ((System.nanoTime() - sent) / 1_000_000).toInt()
            }
        }
        val list = synchronized(handlers) { handlers[type]?.toList() } ?: return authenticatedNow
        for (h in list) guarded { h(payload) }
        return authenticatedNow
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            onFault?.invoke("handler failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Sends one envelope; returns its id (the backend echoes it as `refId` in errors). Throws if not connected. */
    fun send(type: String, payload: JsonObject): String {
        val ws = socket ?: throw IllegalStateException("not connected to backend")
        val id = UUID.randomUUID().toString()
        val envelope = buildJsonObject {
            put("v", 1)
            put("id", id)
            put("ts", System.currentTimeMillis())
            put("type", type)
            put("payload", payload)
        }
        if (!ws.send(envelope.toString())) throw IllegalStateException("send queue is full or socket closed")
        return id
    }

    /** Like [send] but reports failure instead of throwing. */
    fun trySend(type: String, payload: JsonObject): Boolean = try {
        send(type, payload)
        true
    } catch (e: IllegalStateException) {
        false
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 3_000L
        const val HEARTBEAT_MS = 15_000L
        const val DEAD_AFTER_MS = 40_000L
        const val AUTH_TIMEOUT_MS = 15_000L
        const val MAX_INBOUND_CHARS = 128 * 1024
        const val CLOSE_REPLACED = 4010

        /** Capped exponential backoff with +0..30% jitter: 1s, 2s, 4s ... 30s. */
        fun backoffMs(attempt: Int, jitterSample: Double = Math.random()): Long {
            val base = min(30_000L, 1000L * (1L shl min(maxOf(attempt - 1, 0), 20)))
            return base + (jitterSample * base * 0.3).toLong()
        }
    }
}
