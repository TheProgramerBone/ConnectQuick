package dev.connectquick.controller.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import kotlin.math.min

/**
 * WebSocket client for the ConnectQuick backend. Owns the connect/reconnect loop (Protocol §6
 * state machine: CONNECTING → ... → RECONNECTING → CONNECTED, exponential backoff + jitter,
 * capped at 30s) and the 15s heartbeat.ping the spec requires from both peers. Message dispatch
 * is by envelope `type`, via [on] handlers registered before [runForever]. Role-agnostic — the
 * same class serves both the WindowsHost-equivalent and Controller sides in tests.
 */
class BackendClient(private val url: String) {
    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val handlers = mutableMapOf<String, MutableList<(JsonObject) -> Unit>>()

    @Volatile private var socket: WebSocket? = null
    private var heartbeatSeq = 0

    var onConnectionChanged: ((Boolean) -> Unit)? = null

    fun on(type: String, handler: (JsonObject) -> Unit) {
        handlers.getOrPut(type) { mutableListOf() }.add(handler)
    }

    /** Connects, reconnects with backoff on failure, and never returns until its coroutine is cancelled. */
    suspend fun runForever() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                connectOnce()
                attempt = 0 // clean disconnect after a successful session: reset backoff
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // reconnect loop below decides what happens next
            }
            onConnectionChanged?.invoke(false)
            if (!currentCoroutineContext().isActive) break
            attempt++
            val backoffMs = min(30_000L, 1000L * (1L shl min(attempt - 1, 20)))
            val jitterMs = (Math.random() * backoffMs * 0.3).toLong()
            delay(backoffMs + jitterMs)
        }
    }

    private suspend fun connectOnce(): Unit = coroutineScope {
        val closed = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url).build()
        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                onConnectionChanged?.invoke(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                dispatch(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.completeExceptionally(t)
            }
        })
        val heartbeat = launch { heartbeatLoop() }
        try {
            closed.await()
        } finally {
            heartbeat.cancel()
            socket = null
            ws.cancel()
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(15_000)
            send("heartbeat.ping", buildJsonObject { put("seq", heartbeatSeq++) })
        }
    }

    private fun dispatch(text: String) {
        val envelope = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return // malformed frame from a well-behaved backend should never happen; ignore defensively
        }
        val type = envelope["type"]?.jsonPrimitive?.content ?: return
        val payload = envelope["payload"]?.jsonObject ?: JsonObject(emptyMap())
        handlers[type]?.toList()?.forEach { it(payload) }
    }

    /** Sends one envelope. Throws if not currently connected. */
    fun send(type: String, payload: JsonObject) {
        val ws = socket ?: throw IllegalStateException("not connected to backend")
        val envelope = buildJsonObject {
            put("v", 1)
            put("id", UUID.randomUUID().toString())
            put("ts", System.currentTimeMillis())
            put("type", type)
            put("payload", payload)
        }
        ws.send(envelope.toString())
    }
}
