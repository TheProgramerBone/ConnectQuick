package dev.connectquick.controller.stream

/** One ICE server entry as the backend hands it out (TURN credentials are short-lived and per device). */
data class IceServer(val urls: List<String>, val username: String?, val credential: String?)

data class IceConfig(val servers: List<IceServer>, val hasStun: Boolean, val hasTurn: Boolean, val ttlSeconds: Int) {
    companion object {
        /** No ICE servers: only direct (LAN / same network) paths will work, and the UI can say so. */
        val NONE = IceConfig(emptyList(), hasStun = false, hasTurn = false, ttlSeconds = 0)
    }
}

data class RtcCandidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?)

enum class RtcState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** What the user asked to see; the PC may deliver less (its own limits, adaptation). */
data class StreamRequest(val maxWidth: Int, val maxHeight: Int, val maxFps: Int, val maxBitrateKbps: Int)

/** Information about the video that is arriving, for the HUD. */
data class StreamStats(val fps: Double, val bitrateKbps: Int, val width: Int, val height: Int, val rttMs: Int)

/**
 * The receiving end of one WebRTC connection. The Android app implements it over libwebrtc (and
 * renders the track to a surface); tests implement it with a scripted fake. Keeping it this small
 * is what lets the whole signalling flow be verified without a media stack.
 */
interface RtcSession : AutoCloseable {
    /** Applies the PC's offer and returns the SDP answer. Local ICE candidates are delivered to [onCandidate] as they appear. */
    fun answer(offerSdp: String, onCandidate: (RtcCandidate) -> Unit): String
    fun addRemoteCandidate(candidate: RtcCandidate)
    /** Connection state changes (ICE / DTLS). */
    var onState: ((RtcState) -> Unit)?
}

fun interface RtcEngine {
    fun createSession(ice: IceConfig): RtcSession
}
