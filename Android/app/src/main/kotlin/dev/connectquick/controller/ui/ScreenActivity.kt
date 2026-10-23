package dev.connectquick.controller.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.connectquick.controller.connectQuick
import dev.connectquick.controller.input.InputClient
import dev.connectquick.controller.input.InputSink
import dev.connectquick.controller.input.MouseButton
import dev.connectquick.controller.input.TouchAction
import dev.connectquick.controller.input.TouchEvent
import dev.connectquick.controller.input.TouchSample
import dev.connectquick.controller.input.TouchpadEngine
import dev.connectquick.controller.input.VirtualPointer
import dev.connectquick.controller.stream.AutoQuality
import dev.connectquick.controller.session.ControllerHub
import dev.connectquick.controller.stream.StreamProfile
import dev.connectquick.controller.stream.StreamStatus
import dev.connectquick.controller.stream.StreamTexts

/**
 * Live view of the PC's screen. Two input modes: "Directo" (touch where you want the pointer, tap to
 * click, press and hold for a right click) and "Touchpad" (the finger moves a pointer the phone draws
 * itself, so it reacts instantly; tap clicks, tap-and-drag drags, and moves are finer when zoomed).
 * In both modes two fingers zoom and pan the picture (they never move the PC's pointer). "Pantalla completa" hides everything but the picture and turns to landscape; turning
 * the phone sideways does the same. The video comes straight from the PC (or through a TURN relay);
 * the app server never carries it. Streaming runs only while this screen is visible.
 */
class ScreenActivity : HubActivity() {
    override val scrolling: Boolean = false

    private var profile = StreamProfile.NORMAL

    private var video: ZoomableVideoView? = null
    private var banner: Banner? = null
    private var hud: StatusPill? = null
    private var hudDetail: TextView? = null
    private var toggle: android.widget.Button? = null
    private var profileButton: android.widget.Button? = null
    private var videoFrame: FrameLayout? = null
    private var placeholder: TextView? = null
    private var zoomBadge: TextView? = null
    private var overlay: LinearLayout? = null
    private val chrome = mutableListOf<View>()
    private val ui = Handler(Looper.getMainLooper())
    /** True once the person pressed Detener: the stream then stays off until they ask for it again. */
    private var userStopped = false
    private var statusWiredFor: Any? = null
    private var longPress: Runnable? = null
    private var fullscreen = false

    // Touchpad mode: a pointer the phone tracks and draws itself (no waiting for the video to show it).
    private var touchpadMode = false
    private var modeButton: android.widget.Button? = null
    private var cursor: PointerOverlay? = null
    private var pointer: VirtualPointer? = null
    private var padEngine: TouchpadEngine? = null
    private var padFor: InputClient? = null
    private val auto = AutoQuality()

    // Touch state: one finger drives the PC pointer; as soon as a second finger lands, the gesture belongs to zoom/pan.
    private var zoomGesture = false
    private var pinchDistance = 0f
    private var pinchCenterX = 0f
    private var pinchCenterY = 0f
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun buildContent(hub: ControllerHub?) {
        val ctx = this
        chrome.clear()
        video?.let { connectQuick.rtc.attach(null); it.release() } // rebuilt for another PC: free the old renderer
        profile = StreamProfile.fromName(connectQuick.store.streamProfile())
        touchpadMode = connectQuick.store.screenTouchpadMode()
        padFor = null
        content.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 12), Ui.dp(ctx, 12), Ui.dp(ctx, 12))
        backRow("Pantalla").also { content.addView(it, Ui.lp(ctx, bottom = 8)); chrome += it }
        banner = Banner(ctx) { hub?.retryNow() }.also { content.addView(it, Ui.lp(ctx, bottom = 8)); chrome += it }

        val frame = FrameLayout(ctx).apply { background = android.graphics.drawable.ColorDrawable(Color.BLACK) }
        videoFrame = frame
        val v = ZoomableVideoView(ctx, connectQuick.rtc.eglBase.eglBaseContext)
        video = v
        v.contentDescription = "Pantalla del PC en directo. Toca para mover el puntero y hacer clic; dos dedos para ampliar."
        v.onVideoSize = { _, _ -> layoutVideo(); v.post { placeCursor() } }
        frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val note = Ui.label(ctx, "", 15f, 0xFFACB8D6.toInt()).apply { gravity = Gravity.CENTER; setPadding(Ui.dp(ctx, 24), 0, Ui.dp(ctx, 24), 0) }
        placeholder = note
        frame.addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val pointerView = PointerOverlay(ctx).apply { visibility = if (touchpadMode) View.VISIBLE else View.GONE }
        cursor = pointerView
        frame.addView(pointerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Shown over the picture when zoomed: how much, and one tap back to the whole screen.
        val badge = Ui.label(ctx, "", 13f, Color.WHITE, true).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dpf(ctx, 14f); setColor(Color.argb(170, 10, 14, 30)) }
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
            visibility = View.GONE
            contentDescription = "Restablecer el zoom"
            setOnClickListener { Ui.haptic(it); video?.viewport?.reset(); video?.applyViewport(); updateZoomBadge(); placeCursor() }
        }
        zoomBadge = badge
        frame.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(Ui.dp(ctx, 10), 0, 0, Ui.dp(ctx, 10))
        })

        // Floating controls for full screen: always reachable, small enough not to hide the picture.
        val floating = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        overlay = floating
        fun overlayButton(label: String, description: String, onClick: () -> Unit) = Ui.key(ctx, label) { onClick() }.apply {
            contentDescription = description
            background = Ui.ripple(ctx, android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dpf(ctx, 14f); setColor(Color.argb(150, 10, 14, 30)) })
            setTextColor(Color.WHITE)
        }
        floating.addView(overlayButton("⌨", "Abrir el teclado") { openKeyboard() })
        floating.addView(overlayButton("☝", "Cambiar entre toque directo y touchpad") { toggleMode() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 6) })
        floating.addView(overlayButton("📷", "Guardar una captura de la pantalla del PC") { screenshot() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 6) })
        floating.addView(overlayButton("Perfil", "Cambiar el perfil de calidad") { cycleProfile() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 6) })
        floating.addView(overlayButton("✕", "Salir de pantalla completa") { setFullscreen(false) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 6) })
        frame.addView(floating, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, Ui.dp(ctx, 10), Ui.dp(ctx, 10), 0)
        })

        content.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.setOnTouchListener { _, ev -> onVideoTouch(ev); true }
        frame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) layoutVideo() }

        val pill = StatusPill(ctx).also { hud = it }
        val detail = Ui.dim(ctx, "").also { hudDetail = it }
        content.addView(pill, Ui.lp(ctx, top = 8)); chrome += pill
        content.addView(detail, Ui.lp(ctx, bottom = 8)); chrome += detail

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = Ui.button(ctx, "Detener", Ui.ButtonStyle.DANGER) { toggleStream() }
        profileButton = Ui.button(ctx, "Perfil: ${profile.label}") { cycleProfile() }.apply { contentDescription = "Perfil de calidad: ${profile.label}. ${profile.hint}" }
        val full = Ui.button(ctx, "Completa") { setFullscreen(true) }.apply { contentDescription = "Pantalla completa en horizontal" }
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        modeButton = Ui.button(ctx, modeLabel()) { toggleMode() }.apply { contentDescription = "Modo de control: ${modeLabel()}" }
        val shot = Ui.button(ctx, "Captura") { screenshot() }.apply { contentDescription = "Guardar una captura de la pantalla del PC en el teléfono" }
        val kb = Ui.button(ctx, "Teclado") { openKeyboard() }
        fun fill(row: LinearLayout, buttons: List<android.widget.Button>) = buttons.forEachIndexed { i, b ->
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = if (i == 0) 0 else Ui.dp(ctx, 4) })
            b.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0)
            b.textSize = 13f
        }
        fill(row1, listOf(toggle!!, profileButton!!, full))
        fill(row2, listOf(modeButton!!, shot, kb))
        content.addView(row1, Ui.lp(ctx, bottom = 6)); chrome += row1
        content.addView(row2, Ui.lp(ctx)); chrome += row2

        connectQuick.rtc.attach(v)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) setFullscreen(true)
    }

    /** Sizes the picture to the video's aspect ratio inside the black frame, so zoom maps exactly onto the PC's pixels. */
    private fun layoutVideo() {
        val frame = videoFrame ?: return
        val v = video ?: return
        val (vw, vh) = v.videoSize ?: return
        if (frame.width == 0 || frame.height == 0) return
        val scale = minOf(frame.width / vw.toFloat(), frame.height / vh.toFloat())
        val w = (vw * scale).toInt()
        val h = (vh * scale).toInt()
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
            v.layoutParams = lp
        }
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
        val streaming = reason == null && streams?.status?.phase == StreamStatus.Phase.STREAMING
        banner?.visibility = if (fullscreen || streaming) View.GONE else View.VISIBLE
        toggle?.isEnabled = reason == null
        toggle?.alpha = if (reason == null) 1f else 0.5f
        if (reason != null) {
            hud?.set(dev.connectquick.controller.session.Tone.IDLE, reason)
            hudDetail?.text = ""
            placeholder?.text = reason
            return
        }
        ensurePadEngine()
        if (streams != null) {
            showStatus(streams.status)
            val phase = streams.status.phase
            if (!userStopped && (phase == StreamStatus.Phase.IDLE || phase == StreamStatus.Phase.STOPPED)) startStream()
        }
    }

    private fun showStatus(s: StreamStatus) {
        val t = StreamTexts.of(s)
        hud?.set(t.tone, t.headline)
        hudDetail?.text = "${t.detail}${if (t.detail.isEmpty()) "" else " · "}Perfil ${profile.label}"
        placeholder?.text = if (t.showVideo) "" else "${t.headline}\n${t.detail}"
        toggle?.text = if (s.phase == StreamStatus.Phase.IDLE || s.phase == StreamStatus.Phase.STOPPED || s.phase == StreamStatus.Phase.ERROR) "Iniciar" else "Detener"
        toggle?.background = Ui.ripple(this, android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Ui.dpf(this@ScreenActivity, 16f)
            setColor(if (toggle?.text == "Iniciar") Ui.ACCENT_FILL else Ui.DANGER_FILL)
        })
        if (!fullscreen) hud?.announceForAccessibility(t.headline)
        padEngine?.let { e -> val ms = moveIntervalMs(s); if (e.config.minMoveIntervalMs != ms) e.config = e.config.copy(minMoveIntervalMs = ms) }
        autoAdjust(s)
    }

    private fun startStream() {
        val request = if (profile == StreamProfile.AUTO) { auto.onNetwork(isMetered()); auto.current } else profile.request
        hub?.streams?.start(request)
    }

    private fun isMetered(): Boolean =
        (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered

    /** Auto profile: restart with a lower ceiling after sustained delay, or a new one when the network type changes. */
    private fun autoAdjust(s: StreamStatus) {
        if (profile != StreamProfile.AUTO || s.phase != StreamStatus.Phase.STREAMING) return
        val change = auto.onNetwork(isMetered()) ?: auto.onStats(s.stats?.rttMs) ?: return
        hub?.streams?.start(change)
        toast(if (change.maxFps < 30 || change.maxWidth < 1280) "Auto: calidad reducida por la red" else "Auto: calidad ajustada a la red")
    }

    /** Pointer motion is flushed more often in Juego, or whenever the round trip is short anyway. */
    private fun moveIntervalMs(s: StreamStatus?): Long {
        val rtt = s?.stats?.rttMs ?: 0
        return if (profile.lowLatency || (rtt in 1..39)) 4L else 8L
    }

    private fun modeLabel() = if (touchpadMode) "Modo: Touchpad" else "Modo: Directo"

    private fun toggleMode() {
        touchpadMode = !touchpadMode
        connectQuick.store.saveScreenTouchpadMode(touchpadMode)
        padEngine?.cancel()
        modeButton?.text = modeLabel()
        modeButton?.contentDescription = "Modo de control: ${modeLabel()}"
        cursor?.visibility = if (touchpadMode) View.VISIBLE else View.GONE
        toast(if (touchpadMode) "Touchpad: el dedo mueve el puntero; toque = clic, dos dedos = zoom" else "Directo: toca donde quieras hacer clic")
        placeCursor()
    }

    /** Builds the relative-pointer engine for the current session (again whenever the session changes). */
    private fun ensurePadEngine() {
        val input = hub?.input
        if (input === padFor) return
        padEngine?.cancel()
        padFor = input
        if (input == null) { padEngine = null; pointer = null; return }
        val vp = VirtualPointer { x, y -> input.mouseAbsolute(x, y) }.apply {
            buttons = object : InputSink by input {
                override fun mouseButton(button: MouseButton, down: Boolean) {
                    input.mouseButton(button, down)
                    if (down) videoFrame?.let { Ui.haptic(it) }
                }
            }
            onMoved = { fx, fy ->
                video?.let { v -> if (v.viewport.ensureVisible(fx, fy)) { v.applyViewport(); updateZoomBadge() } }
                placeCursor()
            }
        }
        pointer = vp
        // Sensitivity 1: the pointer follows the finger across the picture as seen (zoom makes it finer).
        padEngine = TouchpadEngine(vp, connectQuick.store.touchpad().copy(sensitivity = 1.2f, pinchZoom = false, minMoveIntervalMs = moveIntervalMs(hub?.streams?.status)))
    }

    /** Positions the locally drawn pointer over the picture, following zoom and pan. */
    private fun placeCursor() {
        val c = cursor ?: return
        val v = video ?: return
        val p = pointer
        if (!touchpadMode || p == null || v.videoSize == null) { c.hidePointer(); return }
        val vp = v.viewport
        c.showAt(v.left + p.x * v.width * vp.scale + vp.offsetX, v.top + p.y * v.height * vp.scale + vp.offsetY)
    }

    /** Saves the next frame as a PNG in Pictures/ConnectQuick (no storage permission needed). */
    private fun screenshot() {
        val v = video ?: return
        if (v.videoSize == null) { toast("Inicia la pantalla para poder capturarla"); return }
        videoFrame?.let { Ui.haptic(it) }
        v.capture { bmp ->
            if (bmp == null) { toast("No llegó ninguna imagen; ¿está la pantalla en marcha?"); return@capture }
            Thread {
                val ok = runCatching { saveImage(bmp) }.isSuccess
                runOnUiThread { toast(if (ok) "Captura guardada en Imágenes/ConnectQuick" else "No se pudo guardar la captura") }
            }.start()
        }
    }

    private fun saveImage(bmp: android.graphics.Bitmap) {
        val name = "ConnectQuick_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".png"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ConnectQuick")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no media uri")
            contentResolver.openOutputStream(uri)!!.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            // Before Android 10, the app's own Pictures folder needs no permission (visible via a file manager).
            val dir = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "ConnectQuick").apply { mkdirs() }
            java.io.FileOutputStream(java.io.File(dir, name)).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun toggleStream() {
        val s = hub?.streams ?: return
        when (s.status.phase) {
            StreamStatus.Phase.IDLE, StreamStatus.Phase.STOPPED, StreamStatus.Phase.ERROR -> { userStopped = false; startStream() }
            else -> { userStopped = true; s.stop() }
        }
    }

    private fun cycleProfile() {
        profile = profile.next()
        connectQuick.store.saveStreamProfile(profile.name)
        profileButton?.text = "Perfil: ${profile.label}"
        profileButton?.contentDescription = "Perfil de calidad: ${profile.label}. ${profile.hint}"
        toast("${profile.label}: ${profile.hint}")
        val s = hub?.streams ?: return
        if (s.status.phase == StreamStatus.Phase.STREAMING || s.status.phase == StreamStatus.Phase.NEGOTIATING) startStream()
    }

    private fun openKeyboard() {
        startActivity(Intent(this, TouchpadActivity::class.java).putExtra(TouchpadActivity.EXTRA_OPEN_KEYBOARD, true))
    }

    @Suppress("DEPRECATION")
    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        chrome.forEach { it.visibility = if (on) View.GONE else View.VISIBLE }
        if (!on) render()
        overlay?.visibility = if (on) View.VISIBLE else View.GONE
        val pad = if (on) 0 else Ui.dp(this, 12)
        content.setPadding(pad, pad, pad, pad)
        content.fitsSystemWindows = !on
        content.requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { c ->
                if (on) {
                    c.hide(WindowInsets.Type.systemBars())
                    c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    c.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                0
            }
            if (!on) Ui.styleWindow(window) // restores the light-theme bar icon flags
        }
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        content.post { layoutVideo() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Turning the phone sideways means "let me see it big".
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE && !fullscreen) setFullscreen(true)
        content.post { layoutVideo() }
    }

    @Deprecated("Activity back handling; kept for API < 33 and simple full-screen exit.")
    override fun onBackPressed() {
        if (fullscreen) setFullscreen(false) else super.onBackPressed()
    }

    private fun updateZoomBadge() {
        val vp = video?.viewport ?: return
        zoomBadge?.visibility = if (vp.isZoomed) View.VISIBLE else View.GONE
        zoomBadge?.text = "Zoom ${"%.1f".format(vp.scale)}×  ·  toca para ver todo"
    }

    private fun cancelLongPress() {
        longPress?.let { ui.removeCallbacks(it); longPress = null }
    }

    /**
     * One finger: the pointer goes where the finger is (tap = left click, hold = right click).
     * Two fingers: pinch to zoom, move together to pan; the PC's pointer is left alone.
     */
    private fun onVideoTouch(ev: MotionEvent) {
        val v = video ?: return
        val frame = videoFrame ?: return
        val localX = { i: Int -> ev.getX(i) - v.left }
        val localY = { i: Int -> ev.getY(i) - v.top }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> zoomGesture = false
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) {
                // A second finger: this is a zoom/pan gesture now. Drop any pending click or right click.
                zoomGesture = true
                cancelLongPress()
                padEngine?.cancel() // a second finger means zoom: never leave a button held on the PC
                pinchDistance = distance(ev)
                pinchCenterX = (localX(0) + localX(1)) / 2f
                pinchCenterY = (localY(0) + localY(1)) / 2f
                return
            }
        }
        if (zoomGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_MOVE && ev.pointerCount >= 2) {
                val d = distance(ev)
                val cx = (localX(0) + localX(1)) / 2f
                val cy = (localY(0) + localY(1)) / 2f
                if (pinchDistance > 0f && d > 0f) v.viewport.zoomBy(d / pinchDistance, cx, cy)
                v.viewport.panBy(cx - pinchCenterX, cy - pinchCenterY)
                pinchDistance = d; pinchCenterX = cx; pinchCenterY = cy
                v.applyViewport()
                updateZoomBadge()
                placeCursor()
            } else if (ev.actionMasked == MotionEvent.ACTION_POINTER_UP && ev.pointerCount == 3) {
                // From three fingers back to two: restart the pinch from the remaining pair.
                pinchDistance = 0f
            }
            return
        }

        val input = hub?.input ?: return
        if (v.videoSize == null || frame.width == 0) return
        if (unavailableReason("mouse") != null) return
        if (touchpadMode) { touchpadTouch(ev); return }
        val (fx, fy) = v.viewport.toFraction(localX(0), localY(0))
        val x = (fx * 65535f).toInt()
        val y = (fy * 65535f).toInt()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                input.mouseAbsolute(x, y)
                val r = Runnable { input.click(MouseButton.RIGHT); Ui.haptic(frame, strong = true); longPress = null }
                longPress = r
                ui.postDelayed(r, 550)
                downAt = ev.eventTime; downX = ev.x; downY = ev.y; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                // A finger always trembles a little: only real travel turns a tap into a move.
                if (!moved && kotlin.math.hypot(ev.x - downX, ev.y - downY) < Ui.dpf(this, 10f)) return
                moved = true
                cancelLongPress()
                input.mouseAbsolute(x, y)
            }
            MotionEvent.ACTION_UP -> {
                val pending = longPress
                if (pending != null) {
                    cancelLongPress()
                    if (!moved && ev.eventTime - downAt < 450) { input.click(MouseButton.LEFT); Ui.haptic(frame) }
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelLongPress()
        }
    }

    /** Touchpad mode: the first finger drives the relative engine (move, tap = click, tap-and-drag = drag). */
    private fun touchpadTouch(ev: MotionEvent) {
        val e = padEngine ?: return
        val p = pointer ?: return
        val v = video ?: return
        p.viewWidth = v.width.toFloat(); p.viewHeight = v.height.toFloat(); p.zoom = v.viewport.scale
        val one = listOf(TouchSample(ev.getPointerId(0), ev.getX(0), ev.getY(0)))
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> e.onTouch(TouchEvent(TouchAction.DOWN, ev.eventTime, one))
            MotionEvent.ACTION_MOVE -> e.onTouch(TouchEvent(TouchAction.MOVE, ev.eventTime, one))
            MotionEvent.ACTION_UP -> e.onTouch(TouchEvent(TouchAction.UP, ev.eventTime, emptyList()))
            MotionEvent.ACTION_CANCEL -> e.cancel()
        }
    }

    private fun distance(ev: MotionEvent): Float {
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    override fun onPause() {
        padEngine?.cancel() // leaving the screen must never leave a button held on the PC
        super.onPause()
    }

    override fun onStop() {
        // Never keep decoding video (battery, data, privacy) while the screen is not in front.
        hub?.streams?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        connectQuick.rtc.attach(null)
        hub?.streams?.onStatus = null
        video?.release()
        super.onDestroy()
    }
}

/** The pointer the phone draws itself in touchpad mode: an arrow with a dark outline, visible on any picture. */
class PointerOverlay(ctx: android.content.Context) : View(ctx) {
    private var px = -1f
    private var py = -1f
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = android.graphics.Paint.Style.FILL }
    private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 10, 14, 30); style = android.graphics.Paint.Style.STROKE; strokeWidth = Ui.dpf(ctx, 1.6f); strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val arrow = android.graphics.Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    fun showAt(x: Float, y: Float) { px = x; py = y; invalidate() }
    fun hidePointer() { px = -1f; invalidate() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (px < 0f) return
        val u = Ui.dpf(context, 1f)
        arrow.reset()
        arrow.moveTo(px, py)
        arrow.lineTo(px, py + 18 * u)
        arrow.lineTo(px + 4.5f * u, py + 14 * u)
        arrow.lineTo(px + 8 * u, py + 21 * u)
        arrow.lineTo(px + 11 * u, py + 19.5f * u)
        arrow.lineTo(px + 7.5f * u, py + 12.5f * u)
        arrow.lineTo(px + 13 * u, py + 12.5f * u)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, edge)
    }
}
