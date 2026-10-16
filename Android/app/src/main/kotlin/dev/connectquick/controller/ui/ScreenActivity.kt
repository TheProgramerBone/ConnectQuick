package dev.connectquick.controller.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.connectquick.controller.connectQuick
import dev.connectquick.controller.input.MouseButton
import dev.connectquick.controller.session.ControllerHub
import dev.connectquick.controller.stream.StreamRequest
import dev.connectquick.controller.stream.StreamStatus
import dev.connectquick.controller.stream.StreamTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Live view of the PC's screen, with direct touch control: touch where you want the pointer, tap to
 * click, press and hold for a right click. The video comes straight from the PC (or through a TURN
 * relay); the app server never carries it. Streaming runs only while this screen is visible.
 */
class ScreenActivity : HubActivity() {
    override val scrolling: Boolean = false

    private data class Quality(val label: String, val request: StreamRequest)
    private val qualities = listOf(
        Quality("Baja", StreamRequest(854, 480, 20, 1200)),
        Quality("Media", StreamRequest(1280, 720, 30, 2500)),
        Quality("Alta", StreamRequest(1920, 1080, 30, 6000)),
    )
    private var quality = 1

    private var renderer: SurfaceViewRenderer? = null
    private var videoSize: Pair<Int, Int>? = null
    private var banner: Banner? = null
    private var hud: StatusPill? = null
    private var hudDetail: TextView? = null
    private var toggle: android.widget.Button? = null
    private var qualityButton: android.widget.Button? = null
    private var videoFrame: FrameLayout? = null
    private var placeholder: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** True once the person pressed Detener: the stream then stays off until they ask for it again. */
    private var userStopped = false
    private var statusWiredFor: Any? = null
    private var longPress: Runnable? = null

    override fun buildContent(hub: ControllerHub?) {
        val ctx = this
        content.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 12), Ui.dp(ctx, 12), Ui.dp(ctx, 12))
        content.addView(backRow("Pantalla"), Ui.lp(ctx, bottom = 8))
        banner = Banner(ctx) { hub?.retryNow() }.also { content.addView(it, Ui.lp(ctx, bottom = 8)) }

        val frame = FrameLayout(ctx).apply { background = android.graphics.drawable.ColorDrawable(Color.BLACK) }
        videoFrame = frame
        val r = SurfaceViewRenderer(ctx)
        renderer = r
        r.init(connectQuick.rtc.eglBase.eglBaseContext, null)
        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        r.setEnableHardwareScaler(true)
        r.contentDescription = "Pantalla del PC en directo. Toca para mover el puntero y hacer clic."
        frame.addView(r, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val note = Ui.label(ctx, "", 15f, Ui.DIM).apply { gravity = Gravity.CENTER; setPadding(Ui.dp(ctx, 24), 0, Ui.dp(ctx, 24), 0) }
        placeholder = note
        frame.addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.setOnTouchListener { _, ev -> onVideoTouch(ev); true }

        val pill = StatusPill(ctx).also { hud = it }
        val detail = Ui.dim(ctx, "").also { hudDetail = it }
        content.addView(pill, Ui.lp(ctx, top = 8))
        content.addView(detail, Ui.lp(ctx, bottom = 8))

        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = Ui.button(ctx, "Detener", Ui.ButtonStyle.DANGER) { toggleStream() }
        qualityButton = Ui.button(ctx, "Calidad: ${qualities[quality].label}") { cycleQuality() }
        val pad = Ui.button(ctx, "Touchpad") { startActivity(Intent(ctx, TouchpadActivity::class.java)) }
        val kb = Ui.button(ctx, "Teclado") { startActivity(Intent(ctx, TouchpadActivity::class.java).putExtra(TouchpadActivity.EXTRA_OPEN_KEYBOARD, true)) }
        listOf(toggle!!, qualityButton!!, pad, kb).forEachIndexed { i, b ->
            controls.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (i == 0) 0 else Ui.dp(ctx, 4); marginEnd = Ui.dp(ctx, 0)
            })
            b.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0)
            b.textSize = 13f
        }
        content.addView(controls, Ui.lp(ctx))

        connectQuick.rtc.attach(r)
        connectQuick.rtc.onFrameSize = { w, h -> videoSize = w to h }
    }

    override fun render() {
        val h = hub ?: return
        presentation()?.let { banner?.update(it) }
        val reason = unavailableReason("remote_desktop")
        val streams = h.streams

        // Bring the stream's status onto the screen (callback is re-pointed whenever the session changes).
        if (statusWiredFor !== streams) {
            statusWiredFor = streams
            streams?.onStatus = { runOnUiThread { showStatus(it) } }
        }
        banner?.visibility = if (reason == null && streams?.status?.phase == StreamStatus.Phase.STREAMING) View.GONE else View.VISIBLE
        toggle?.isEnabled = reason == null
        toggle?.alpha = if (reason == null) 1f else 0.5f
        if (reason != null) {
            hud?.set(dev.connectquick.controller.session.Tone.IDLE, reason)
            hudDetail?.text = ""
            placeholder?.text = reason
            return
        }
        if (streams != null) {
            showStatus(streams.status)
            val phase = streams.status.phase
            if (!userStopped && (phase == StreamStatus.Phase.IDLE || phase == StreamStatus.Phase.STOPPED)) startStream()
        }
    }

    private fun showStatus(s: StreamStatus) {
        val t = StreamTexts.of(s)
        hud?.set(t.tone, t.headline)
        hudDetail?.text = t.detail
        placeholder?.text = if (t.showVideo) "" else "${t.headline}\n${t.detail}"
        toggle?.text = if (s.phase == StreamStatus.Phase.IDLE || s.phase == StreamStatus.Phase.STOPPED || s.phase == StreamStatus.Phase.ERROR) "Iniciar" else "Detener"
        toggle?.background = Ui.ripple(this, android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Ui.dpf(this@ScreenActivity, 16f)
            setColor(if (toggle?.text == "Iniciar") Ui.ACCENT_FILL else Ui.DANGER_FILL)
        })
        hud?.announceForAccessibility(t.headline)
    }

    private fun startStream() {
        hub?.streams?.start(qualities[quality].request)
    }

    private fun toggleStream() {
        val s = hub?.streams ?: return
        when (s.status.phase) {
            StreamStatus.Phase.IDLE, StreamStatus.Phase.STOPPED, StreamStatus.Phase.ERROR -> { userStopped = false; startStream() }
            else -> { userStopped = true; s.stop() }
        }
    }

    private fun cycleQuality() {
        quality = (quality + 1) % qualities.size
        qualityButton?.text = "Calidad: ${qualities[quality].label}"
        val s = hub?.streams ?: return
        if (s.status.phase == StreamStatus.Phase.STREAMING || s.status.phase == StreamStatus.Phase.NEGOTIATING) startStream()
    }

    /** Maps a touch on the letterboxed video to 0..65535 across the PC's primary monitor. */
    private fun onVideoTouch(ev: MotionEvent) {
        val input = hub?.input ?: return
        val frame = videoFrame ?: return
        val (vw, vh) = videoSize ?: return
        if (unavailableReason("mouse") != null) return
        val scale = minOf(frame.width / vw.toFloat(), frame.height / vh.toFloat())
        val dw = vw * scale; val dh = vh * scale
        val left = (frame.width - dw) / 2f; val top = (frame.height - dh) / 2f
        fun norm(v: Float, origin: Float, size: Float) = (((v - origin) / size).coerceIn(0f, 1f) * 65535f).toInt()
        val x = norm(ev.x, left, dw); val y = norm(ev.y, top, dh)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                input.mouseAbsolute(x, y)
                val r = Runnable { input.click(MouseButton.RIGHT); longPress = null }
                longPress = r
                ui.postDelayed(r, 550)
                downAt = ev.eventTime; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                moved = true
                longPress?.let { ui.removeCallbacks(it); longPress = null }
                input.mouseAbsolute(x, y)
            }
            MotionEvent.ACTION_UP -> {
                val pending = longPress
                if (pending != null) { ui.removeCallbacks(pending); longPress = null; if (!moved && ev.eventTime - downAt < 450) input.click(MouseButton.LEFT) }
            }
            MotionEvent.ACTION_CANCEL -> { longPress?.let { ui.removeCallbacks(it); longPress = null } }
        }
    }
    private var downAt = 0L
    private var moved = false

    override fun onStop() {
        // Never keep decoding video (battery, data, privacy) while the screen is not in front.
        hub?.streams?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        connectQuick.rtc.attach(null)
        connectQuick.rtc.onFrameSize = null
        hub?.streams?.onStatus = null
        renderer?.release()
        scope.cancel()
        super.onDestroy()
    }
}
