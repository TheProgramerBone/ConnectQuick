package dev.connectquick.controller.stream

import dev.connectquick.controller.net.Subscription
import dev.connectquick.controller.session.SessionPort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** Where a stream is, in terms a screen can show. [reason] is a machine code; [message] is for people (Spanish). */
data class StreamStatus(
    val phase: Phase,
    val reason: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val stats: StreamStats? = null,
    val blocked: String? = null,
) {
    enum class Phase { IDLE, REQUESTING, NEGOTIATING, STREAMING, STOPPED, ERROR }
}

/**
 * Controller side of screen streaming (Phase 5). The relay never carries video: this asks the PC
 * for a stream over the encrypted control channel, answers the PC's offer, exchanges ICE candidates
 * inside that same channel, and lets the platform's [RtcEngine] receive the media directly (or via
 * TURN). It reports progress as [StreamStatus] and always tears the connection down on stop, error
 * or end of session.
 */
class StreamController(
    private val port: SessionPort,
    private val ice: suspend () -> IceConfig,
    private val engine: RtcEngine,
) {
    @Volatile var onStatus: ((StreamStatus) -> Unit)? = null
    @Volatile var status: StreamStatus = StreamStatus(StreamStatus.Phase.IDLE)
        private set

    private val lock = Any()
    private var session: RtcSession? = null
    private var streamId: String? = null
    private var pendingCandidates = mutableListOf<RtcCandidate>()
    private var haveRemoteOffer = false
    private val subs = mutableListOf<Subscription>()

    init {
        subs += port.on("rtc.offer") { m -> onOffer(m) }
        subs += port.on("rtc.candidate") { m -> onRemoteCandidate(m) }
        subs += port.on("rtc.end") { m -> teardown(StreamStatus.Phase.STOPPED, m["reason"]?.jsonPrimitive?.content) }
        subs += port.on("stream.state") { m -> onPcState(m) }
        subs += port.on("stream.stats") { m ->
            val s = StreamStats(
                m.getValue("fps").jsonPrimitive.doubleOrNull ?: 0.0, m.getValue("bitrateKbps").jsonPrimitive.intOrNull ?: 0,
                m["width"]?.jsonPrimitive?.intOrNull ?: 0, m["height"]?.jsonPrimitive?.intOrNull ?: 0, m["rttMs"]?.jsonPrimitive?.intOrNull ?: -1,
            )
            publish(status.copy(stats = s))
        }
        subs += port.on("ctl.error") { m ->
            // A refusal of stream.start (forbidden / disabled_by_host) is addressed by the stream id.
            if (m["id"]?.jsonPrimitive?.content == streamId) teardown(StreamStatus.Phase.ERROR, m.getValue("code").jsonPrimitive.content)
        }
    }

    private fun publish(s: StreamStatus) {
        status = s
        onStatus?.let { runCatching { it(s) } }
    }

    /** Asks the PC to start streaming. Safe to call again: the PC replaces its previous stream. */
    fun start(request: StreamRequest): Boolean {
        teardown(StreamStatus.Phase.IDLE, null, notifyPc = false)
        val id = UUID.randomUUID().toString()
        synchronized(lock) { streamId = id; haveRemoteOffer = false; pendingCandidates = mutableListOf() }
        publish(StreamStatus(StreamStatus.Phase.REQUESTING))
        val sent = port.send("stream.start") {
            put("id", id); put("maxWidth", request.maxWidth); put("maxHeight", request.maxHeight)
            put("maxFps", request.maxFps); put("maxBitrateKbps", request.maxBitrateKbps)
        }
        if (!sent) publish(StreamStatus(StreamStatus.Phase.ERROR, "session_closed"))
        return sent
    }

    /** Leaves the stream; the pairing and the control session stay up (mouse, keyboard, files keep working). */
    fun stop() {
        val id = synchronized(lock) { streamId }
        if (id != null) port.send("stream.stop") { put("id", id) }
        teardown(StreamStatus.Phase.STOPPED, "stopped_by_user", notifyPc = false)
    }

    private fun onOffer(m: JsonObject) {
        val id = synchronized(lock) { streamId } ?: return
        publish(status.copy(phase = StreamStatus.Phase.NEGOTIATING))
        // The ICE lookup may need the network; do it off the dispatch thread but keep candidates ordered.
        Thread {
            try {
                val cfg = kotlinx.coroutines.runBlocking { ice() }
                val rtc = engine.createSession(cfg)
                rtc.onState = { st -> onRtcState(st, cfg) }
                synchronized(lock) { if (streamId != id) { rtc.close(); return@Thread }; session = rtc }
                val answer = rtc.answer(m.getValue("sdp").jsonPrimitive.content) { c ->
                    port.send("rtc.candidate") {
                        put("candidate", c.candidate)
                        c.sdpMid?.let { put("sdpMid", it) }
                        c.sdpMLineIndex?.let { put("sdpMLineIndex", it) }
                    }
                }
                port.send("rtc.answer") { put("sdp", answer) }
                val queued = synchronized(lock) { haveRemoteOffer = true; pendingCandidates.also { pendingCandidates = mutableListOf() } }
                queued.forEach { rtc.addRemoteCandidate(it) }
            } catch (e: Exception) {
                teardown(StreamStatus.Phase.ERROR, "negotiation_failed")
            }
        }.apply { isDaemon = true; name = "stream-negotiation" }.start()
    }

    private fun onRemoteCandidate(m: JsonObject) {
        val c = RtcCandidate(
            m.getValue("candidate").jsonPrimitive.content,
            m["sdpMid"]?.jsonPrimitive?.content,
            m["sdpMLineIndex"]?.jsonPrimitive?.intOrNull,
        )
        val rtc = synchronized(lock) {
            // Candidates can race the answer; hold them until the session exists.
            if (!haveRemoteOffer || session == null) { if (pendingCandidates.size < 60) pendingCandidates.add(c); null } else session
        }
        rtc?.let { runCatching { it.addRemoteCandidate(c) } }
    }

    private fun onPcState(m: JsonObject) {
        when (m.getValue("state").jsonPrimitive.content) {
            "starting" -> publish(status.copy(phase = StreamStatus.Phase.REQUESTING))
            "streaming" -> publish(status.copy(
                phase = StreamStatus.Phase.STREAMING,
                width = m["width"]?.jsonPrimitive?.intOrNull ?: status.width, height = m["height"]?.jsonPrimitive?.intOrNull ?: status.height,
                blocked = m["reason"]?.jsonPrimitive?.content,
            ))
            "stopped" -> teardown(StreamStatus.Phase.STOPPED, m["reason"]?.jsonPrimitive?.content, notifyPc = false)
            "error" -> teardown(StreamStatus.Phase.ERROR, m["reason"]?.jsonPrimitive?.content ?: "error", notifyPc = false)
        }
    }

    private fun onRtcState(st: RtcState, cfg: IceConfig) {
        if (st == RtcState.FAILED) {
            // Say why, in terms of the network: with no relay a hostile NAT is the likely cause.
            teardown(StreamStatus.Phase.ERROR, if (cfg.hasTurn) "ice_failed" else "ice_failed_no_relay")
        }
    }

    private fun teardown(phase: StreamStatus.Phase, reason: String?, notifyPc: Boolean = true) {
        val (rtc, id) = synchronized(lock) {
            val s = session
            val i = streamId
            session = null
            if (phase != StreamStatus.Phase.IDLE) streamId = null
            haveRemoteOffer = false
            pendingCandidates = mutableListOf()
            s to i
        }
        runCatching { rtc?.close() }
        if (notifyPc && id != null && phase == StreamStatus.Phase.ERROR) port.send("stream.stop") { put("id", id) }
        if (phase == StreamStatus.Phase.IDLE) return
        if (status.phase != phase || status.reason != reason) publish(StreamStatus(phase, reason))
    }

    /** The session ended: release the media connection. */
    fun release() {
        subs.forEach { it.cancel() }
        teardown(StreamStatus.Phase.STOPPED, "session_ended", notifyPc = false)
    }
}
