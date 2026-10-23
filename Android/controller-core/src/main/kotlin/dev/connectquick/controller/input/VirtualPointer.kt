package dev.connectquick.controller.input

/**
 * "Touchpad" mode on the live screen: the phone keeps its own idea of where the PC's pointer is (as
 * 0..1 fractions of the screen), moves it by relative finger motion and sends absolute positions.
 * Because the position is known locally, the app can draw the pointer immediately, before the video
 * catches up, which hides the network delay. When the picture is zoomed in, the same finger travel
 * moves the pointer less, for precise clicks on small targets.
 */
class VirtualPointer(private val send: (Int, Int) -> Unit) : InputSink {
    var x = 0.5f
        private set
    var y = 0.5f
        private set

    /** Size of the picture on the phone (px) and its zoom; motion is relative to what the person sees. */
    var viewWidth = 0f
    var viewHeight = 0f
    var zoom = 1f

    /** Invoked after every move with the new position (fractions). */
    var onMoved: ((Float, Float) -> Unit)? = null

    /** Where the real button presses go (the session's InputClient). */
    var buttons: InputSink? = null

    override fun mouseMove(dx: Int, dy: Int) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val z = zoom.coerceAtLeast(1f)
        x = (x + dx / (viewWidth * z)).coerceIn(0f, 1f)
        y = (y + dy / (viewHeight * z)).coerceIn(0f, 1f)
        send(wire(x), wire(y))
        onMoved?.invoke(x, y)
    }

    /** Jumps to a point (e.g. the first touch of a session) without sending a button. */
    fun moveTo(fx: Float, fy: Float) {
        x = fx.coerceIn(0f, 1f); y = fy.coerceIn(0f, 1f)
        send(wire(x), wire(y))
        onMoved?.invoke(x, y)
    }

    override fun mouseButton(button: MouseButton, down: Boolean) { buttons?.mouseButton(button, down) }
    override fun wheel(dx: Int, dy: Int) { buttons?.wheel(dx, dy) }
    override fun key(code: String, down: Boolean) { buttons?.key(code, down) }

    private fun wire(f: Float) = (f * 65535f).toInt().coerceIn(0, 65535)
}
