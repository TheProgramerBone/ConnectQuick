package dev.connectquick.controller.session

import dev.connectquick.controller.crypto.CryptoUtil
import dev.connectquick.controller.identity.DeviceIdentity
import dev.connectquick.controller.net.BackendClient
import dev.connectquick.controller.net.Subscription
import dev.connectquick.controller.protocol.InnerMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The PC (or the relay on its behalf) refused or abandoned the session; [reason] is a short machine code. */
class SessionFailed(val reason: String) : Exception(reason)

/**
 * The Controller's end of an end-to-end encrypted control session (protocol-spec section 9).
 *
 * The handshake never trusts the backend: the PC's answer must carry an Ed25519 signature, made
 * with the identity key this phone stored when it paired, over BOTH ephemeral keys and the
 * permissions granted. A relay that swaps a key, adds a permission or impersonates the PC cannot
 * produce that signature. The session counts as established only after the first frame
 * (`ctl.caps`) decrypts — proof both sides derived the same keys.
 */
class ControllerSession private constructor(
    private val client: BackendClient,
    private val host: PairedHost,
    val sessionId: String,
    private val channel: SessionChannel,
    override val permissions: Set<String>,
) : SessionPort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ended = AtomicBoolean(false)
    private val inbox = Channel<JsonObject>(Channel.UNLIMITED)
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(JsonObject) -> Unit>>()
    private val subscriptions = CopyOnWriteArrayList<Subscription>()
    private val lastRxNanos = AtomicLong(System.nanoTime())
    private val violations = AtomicInteger(0)
    private val pingSentNanos = AtomicLong(0)
    private val pingCounter = AtomicLong(0)
    private val caps = CompletableDeferred<HostCaps>()

    @Volatile var hostCaps: HostCaps? = null
        private set
    @Volatile var rttMs: Int = -1
        private set
    @Volatile var endReason: String? = null
        private set
    private val endedListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /**
     * Calls [listener] exactly once, with the reason, when the session ends for any cause. Several
     * components may listen (the connection manager, the UI); if the session already ended, the
     * listener is called immediately so nobody can miss the event.
     */
    fun addEndedListener(listener: (String) -> Unit): Subscription {
        endedListeners.add(listener)
        endReason?.let { if (ended.get()) runCatching { listener(it) } }
        return Subscription { endedListeners.remove(listener) }
    }

    override val isOpen: Boolean get() = !ended.get()

    override fun on(type: String, handler: (JsonObject) -> Unit): Subscription {
        listeners.getOrPut(type) { CopyOnWriteArrayList() }.add(handler)
        return Subscription { listeners[type]?.remove(handler) }
    }

    override fun send(type: String, fields: JsonObjectBuilder.() -> Unit): Boolean {
        if (ended.get()) return false
        return try {
            val plain = InnerMessages.serialize(type, fields)
            // Seal and queue under one lock so frames reach the socket in sequence order.
            synchronized(channel) {
                val frame = channel.seal(plain)
                client.send("session.data", buildJsonObject {
                    put("sessionId", sessionId)
                    put("seq", frame.seq)
                    put("ct", frame.ct)
                })
            }
            true
        } catch (e: IllegalStateException) {
            end("send_failed")
            false
        }
    }

    /** Called on the network thread for every relayed frame: decrypt (ordered), then hand off. */
    private fun onData(payload: JsonObject) {
        if (ended.get() || payload["sessionId"]?.jsonPrimitive?.content != sessionId) return
        val plain = try {
            channel.open(payload.getValue("seq").jsonPrimitive.long(), payload.getValue("ct").jsonPrimitive.content)
        } catch (e: Exception) {
            violation("undecryptable frame")
            return
        }
        lastRxNanos.set(System.nanoTime())
        val parsed = InnerMessages.parse(plain)
        val message = parsed.message
        if (!parsed.ok || message == null) {
            violation("invalid inner message: ${parsed.rejection}")
            return
        }
        if (!caps.isCompleted) {
            // The very first frame must be ctl.caps: it doubles as key confirmation.
            if (parsed.type != "ctl.caps") {
                violation("first frame was not ctl.caps")
                return
            }
            val c = parseCaps(message)
            hostCaps = c
            caps.complete(c)
        }
        inbox.trySend(message)
    }

    /** Last reason a frame was dropped, for diagnostics (never contains message content). */
    @Volatile var lastViolation: String? = null
        private set

    private fun violation(why: String) {
        lastViolation = why
        if (violations.incrementAndGet() >= MAX_VIOLATIONS) end("protocol_violations")
    }

    private fun JsonPrimitive.long(): Long = longOrNull ?: throw IllegalArgumentException("not an integer")

    private fun parseCaps(m: JsonObject): HostCaps {
        val screen = m["screen"] as? JsonObject
        return HostCaps(
            hostVersion = m.getValue("hostVersion").jsonPrimitive.content,
            permissions = m.getValue("permissions").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            features = m.getValue("features").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            screenWidth = screen?.get("width")?.jsonPrimitive?.intOrNull,
            screenHeight = screen?.get("height")?.jsonPrimitive?.intOrNull,
            maxFileBytes = m["maxFileBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
            clipboardAutoSync = m["clipboardSync"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun startBackgroundWork() {
        scope.launch {
            for (message in inbox) {
                val type = message["t"]!!.jsonPrimitive.content
                if (type == "ctl.pong") {
                    val sent = pingSentNanos.get()
                    if (sent != 0L) rttMs = ((System.nanoTime() - sent) / 1_000_000).toInt()
                }
                listeners[type]?.forEach { h ->
                    try { h(message) } catch (e: Exception) { /* a faulty listener must not break the session */ }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(PING_EVERY_MS)
                pingSentNanos.set(System.nanoTime())
                if (!send("ctl.ping") { put("n", pingCounter.incrementAndGet()) }) return@launch
                val silentMs = (System.nanoTime() - lastRxNanos.get()) / 1_000_000
                if (silentMs > HOST_SILENT_LIMIT_MS) {
                    end("host_unresponsive")
                    return@launch
                }
            }
        }
        subscriptions += client.on("session.data") { onData(it) }
        subscriptions += client.on("session.end") { p ->
            if (p["sessionId"]?.jsonPrimitive?.content == sessionId) end(p["reason"]?.jsonPrimitive?.content ?: "ended", notifyPeer = false)
        }
        subscriptions += client.on("session.state") { p ->
            val state = p["state"]?.jsonPrimitive?.content
            if (state == "DISCONNECTED" || state == "REJECTED") end(p["reason"]?.jsonPrimitive?.content ?: state.lowercase(), notifyPeer = false)
        }
        subscriptions += client.addStateListener { s -> if (s != dev.connectquick.controller.net.SignalingState.READY) end("signaling_lost", notifyPeer = false) }
    }

    /** Idempotent. Tells the PC (best effort), wipes the keys and stops every coroutine. */
    fun end(reason: String, notifyPeer: Boolean = true) {
        if (!ended.compareAndSet(false, true)) return
        endReason = reason
        if (notifyPeer) {
            client.trySend("session.end", buildJsonObject {
                put("sessionId", sessionId)
                put("reason", reason.take(64))
            })
        }
        subscriptions.forEach { it.cancel() }
        inbox.close()
        scope.cancel()
        channel.wipe()
        caps.cancel()
        endedListeners.forEach { runCatching { it(reason) } }
    }

    companion object {
        private const val PING_EVERY_MS = 10_000L
        private const val HOST_SILENT_LIMIT_MS = 30_000L
        private const val MAX_VIOLATIONS = 25

        /**
         * Opens a session with [host]. Throws [SessionFailed] with a machine-readable reason when the
         * PC is offline, rejects, the signature does not verify, or key confirmation fails.
         */
        suspend fun open(
            client: BackendClient,
            identity: DeviceIdentity,
            host: PairedHost,
            timeoutMs: Long = 20_000,
        ): ControllerSession {
            val ephemeral = SessionCrypto.generateEphemeral()
            val sessionId = UUID.randomUUID().toString()
            val issuedAt = System.currentTimeMillis()
            val sig = identity.sign(SessionCrypto.connectMessage(sessionId, host.hostId, identity.deviceId, ephemeral.publicB64Url, issuedAt))

            val result = CompletableDeferred<ControllerSession>()
            val subs = CopyOnWriteArrayList<Subscription>()
            var connectEnvelopeId: String? = null
            fun fail(reason: String) { result.completeExceptionally(SessionFailed(reason)) }

            // Installed BEFORE session.connect is sent. The accept handler does all verification and
            // installs the data handler synchronously, so the PC's first frame cannot be missed.
            subs += client.on("session.accept") { p ->
                if (p["sessionId"]?.jsonPrimitive?.content != sessionId || result.isCompleted) return@on
                try {
                    val hostEph = p.getValue("ephPub").jsonPrimitive.content
                    val granted = p.getValue("permissions").jsonArray.map { it.jsonPrimitive.content }
                    val acceptSig = p.getValue("sig").jsonPrimitive.content
                    val signed = SessionCrypto.acceptMessage(sessionId, host.hostId, identity.deviceId, ephemeral.publicB64Url, hostEph, granted)
                    if (!CryptoUtil.verifyEd25519(host.hostPub, signed, acceptSig)) return@on fail("host_signature_invalid")
                    if (!host.permissions.containsAll(granted)) return@on fail("host_granted_unpaired_permissions")
                    val transcript = SessionCrypto.transcriptHash(sessionId, host.hostId, identity.deviceId, ephemeral.publicB64Url, hostEph, granted)
                    val keys = SessionCrypto.deriveKeys(SessionCrypto.sharedSecret(ephemeral, hostEph), transcript)
                    val session = ControllerSession(client, host, sessionId, SessionChannel.forController(sessionId, keys), granted.toSet())
                    session.startBackgroundWork()
                    result.complete(session)
                } catch (e: Exception) {
                    fail("handshake_failed")
                }
            }
            subs += client.on("session.reject") { p ->
                if (p["sessionId"]?.jsonPrimitive?.content == sessionId) fail(p["reason"]?.jsonPrimitive?.content ?: "rejected")
            }
            subs += client.on("session.state") { p ->
                when (p["state"]?.jsonPrimitive?.content) {
                    "DISCONNECTED", "REJECTED" -> if (!result.isCompleted) fail(p["reason"]?.jsonPrimitive?.content ?: "refused")
                }
            }
            subs += client.on("error") { p ->
                if (p["refId"]?.jsonPrimitive?.content == connectEnvelopeId) fail(p["code"]?.jsonPrimitive?.content ?: "error")
            }

            try {
                connectEnvelopeId = client.send("session.connect", buildJsonObject {
                    put("hostId", host.hostId)
                    put("sessionId", sessionId)
                    put("ephPub", ephemeral.publicB64Url)
                    put("issuedAt", issuedAt)
                    put("sig", sig)
                })
                val session = try {
                    withTimeout(timeoutMs) { result.await() }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    client.trySend("session.end", buildJsonObject { put("sessionId", sessionId); put("reason", "connect_timeout") })
                    throw SessionFailed("connect_timeout")
                }
                // Key confirmation: the PC's first frame must decrypt.
                try {
                    withTimeout(10_000) { session.caps.await() }
                } catch (e: Exception) {
                    session.end("key_confirmation_failed")
                    throw SessionFailed("key_confirmation_failed")
                }
                return session
            } catch (e: IllegalStateException) {
                throw SessionFailed("signaling_unavailable")
            } finally {
                subs.forEach { it.cancel() }
            }
        }
    }
}
