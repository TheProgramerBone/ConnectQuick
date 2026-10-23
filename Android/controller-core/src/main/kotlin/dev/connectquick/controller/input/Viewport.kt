package dev.connectquick.controller.input

/**
 * Zoom and pan of the live screen, as plain arithmetic so it can be unit-tested. Coordinates are
 * pixels of the video view at zoom 1; the view shows `screen = content * scale + offset`. The
 * content always covers the view (no panning past an edge), and zoom stays within 1..[maxScale].
 */
class Viewport(val maxScale: Float = 5f) {
    var scale = 1f
        private set
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set
    private var width = 0f
    private var height = 0f

    val isZoomed: Boolean get() = scale > 1.01f

    fun setSize(w: Float, h: Float) {
        width = w; height = h
        clamp()
    }

    /** Pinch: multiplies the zoom by [factor] keeping the point under ([focusX], [focusY]) still. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (factor <= 0f || factor.isNaN()) return
        val next = (scale * factor).coerceIn(1f, maxScale)
        val f = next / scale
        offsetX = focusX - (focusX - offsetX) * f
        offsetY = focusY - (focusY - offsetY) * f
        scale = next
        clamp()
    }

    fun panBy(dx: Float, dy: Float) {
        offsetX += dx; offsetY += dy
        clamp()
    }

    fun reset() {
        scale = 1f; offsetX = 0f; offsetY = 0f
    }

    /** Where a touch on the zoomed view lands on the un-zoomed content, as 0..1 fractions of the view. */
    fun toFraction(x: Float, y: Float): Pair<Float, Float> {
        if (width <= 0f || height <= 0f) return 0f to 0f
        val cx = (x - offsetX) / scale
        val cy = (y - offsetY) / scale
        return (cx / width).coerceIn(0f, 1f) to (cy / height).coerceIn(0f, 1f)
    }

    private fun clamp() {
        offsetX = offsetX.coerceIn(minOf(0f, width * (1 - scale)), 0f)
        offsetY = offsetY.coerceIn(minOf(0f, height * (1 - scale)), 0f)
    }
}
