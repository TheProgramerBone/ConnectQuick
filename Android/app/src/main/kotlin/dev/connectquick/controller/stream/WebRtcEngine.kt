package dev.connectquick.controller.stream

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * libwebrtc behind the small [RtcEngine] contract the (unit-tested) [StreamController] speaks. It is
 * receive-only: the PC offers one video track, this answers, and frames go to whatever renderer the
 * screen attached with [attach]. Hardware decoding is used when the device has it.
 */
class WebRtcEngine(context: Context) : RtcEngine {
    private val appContext = context.applicationContext
    val eglBase: EglBase by lazy { EglBase.create() }

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    @Volatile private var renderer: VideoSink? = null
    @Volatile private var lastFrameSize: Pair<Int, Int>? = null
    @Volatile var onFrameSize: ((Int, Int) -> Unit)? = null

    /** The screen's renderer. Frames that arrive while nothing is attached are dropped, not queued. */
    fun attach(sink: VideoSink?) {
        renderer = sink
    }

    private val proxy = VideoSink { frame: VideoFrame ->
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (lastFrameSize != w to h) {
            lastFrameSize = w to h
            onFrameSize?.invoke(w, h)
        }
        renderer?.onFrame(frame)
    }

    override fun createSession(ice: IceConfig): RtcSession = Session(ice)

    private inner class Session(ice: IceConfig) : RtcSession {
        override var onState: ((RtcState) -> Unit)? = null
        private var track: VideoTrack? = null
        @Volatile private var closed = false
        private var onCandidate: ((RtcCandidate) -> Unit)? = null

        private val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate) {
                onCandidate?.invoke(RtcCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {
                val t = transceiver?.receiver?.track()
                if (t is VideoTrack && track == null) {
                    track = t
                    t.setEnabled(true)
                    t.addSink(proxy)
                }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                if (closed) return
                onState?.invoke(
                    when (state) {
                        PeerConnection.PeerConnectionState.NEW -> RtcState.NEW
                        PeerConnection.PeerConnectionState.CONNECTING -> RtcState.CONNECTING
                        PeerConnection.PeerConnectionState.CONNECTED -> RtcState.CONNECTED
                        PeerConnection.PeerConnectionState.DISCONNECTED -> RtcState.DISCONNECTED
                        PeerConnection.PeerConnectionState.FAILED -> RtcState.FAILED
                        PeerConnection.PeerConnectionState.CLOSED -> RtcState.CLOSED
                        null -> RtcState.NEW
                    },
                )
            }
        }

        private val pc: PeerConnection = run {
            val servers = ice.servers.map { s ->
                PeerConnection.IceServer.builder(s.urls).apply {
                    s.username?.let { setUsername(it) }
                    s.credential?.let { setPassword(it) }
                }.createIceServer()
            }
            val config = PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            factory.createPeerConnection(config, observer) ?: throw IllegalStateException("peer connection unavailable")
        }

        override fun answer(offerSdp: String, onCandidate: (RtcCandidate) -> Unit): String {
            this.onCandidate = onCandidate
            awaitSdp("set remote description") { obs -> pc.setRemoteDescription(obs, SessionDescription(SessionDescription.Type.OFFER, offerSdp)) }
            val answer = awaitSdp("create answer") { obs -> pc.createAnswer(obs, MediaConstraints()) }
                ?: throw IllegalStateException("no answer produced")
            awaitSdp("set local description") { obs -> pc.setLocalDescription(obs, answer) }
            return answer.description
        }

        override fun addRemoteCandidate(candidate: RtcCandidate) {
            if (closed) return
            pc.addIceCandidate(IceCandidate(candidate.sdpMid ?: "", candidate.sdpMLineIndex ?: 0, candidate.candidate))
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { track?.removeSink(proxy) }
            runCatching { pc.close() }
            runCatching { pc.dispose() }
            lastFrameSize = null
        }

        /**
         * Runs one asynchronous WebRTC step and waits for its result. Any failure, or ten seconds of
         * silence, aborts the negotiation (the caller reports it and tears the stream down).
         */
        private fun awaitSdp(what: String, start: (SdpObserver) -> Unit): SessionDescription? {
            val done = CountDownLatch(1)
            val error = AtomicReference<String?>(null)
            val created = AtomicReference<SessionDescription?>(null)
            start(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) { created.set(sdp); done.countDown() }
                override fun onSetSuccess() { done.countDown() }
                override fun onCreateFailure(reason: String?) { error.set(reason ?: "failed"); done.countDown() }
                override fun onSetFailure(reason: String?) { error.set(reason ?: "failed"); done.countDown() }
            })
            if (!done.await(10, TimeUnit.SECONDS)) throw IllegalStateException("$what timed out")
            error.get()?.let { throw IllegalStateException("$what: $it") }
            return created.get()
        }
    }
}
