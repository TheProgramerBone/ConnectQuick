package dev.connectquick.controller.input

/** One touch sample, in screen pixels, as any UI toolkit can produce it. */
data class TouchSample(val pointerId: Int, val x: Float, val y: Float)

enum class TouchAction { DOWN, MOVE, UP, CANCEL }

/**
 * A frame of touch input: which action just happened and where the fingers are. For DOWN the list
 * includes the new finger; for UP it lists the fingers STILL down after the lifted one is removed
 * (so it is empty for the final lift).
 */
data class TouchEvent(val action: TouchAction, val timeMs: Long, val pointers: List<TouchSample>)

data class TouchpadConfig(
    /** Pointer speed multiplier applied to finger travel. 1.0 = 1 px of finger -> 1 px of cursor. */
    val sensitivity: Float = 1.6f,
    /** Extra speed for fast swipes (0 disables). The factor grows with finger speed, capped. */
    val acceleration: Float = 0.5f,
    /** Exponential smoothing of finger deltas, 0 (off) .. 0.8. Reduces jitter at the cost of a little latency. */
    val smoothing: Float = 0f,
    /** Natural scrolling: content follows the fingers (phone convention). */
    val naturalScroll: Boolean = true,
    /** Finger travel that makes one wheel notch (120 units) when scrolling. */
    val scrollPixelsPerNotch: Float = 36f,
    /** A touch that moves less than this is still a tap. */
    val tapSlopPx: Float = 14f,
    val tapMaxMs: Long = 250,
    /** A second touch this soon after a tap becomes a double-click / click-and-drag. */
    val doubleTapWindowMs: Long = 320,
    /** Two fingers must travel this far before the gesture is accepted as a scroll (not an accidental brush). */
    val scrollStartPx: Float = 6f,
    /** Pointer motion is flushed to the network at most this often; deltas are summed in between. */
    val minMoveIntervalMs: Long = 8,
)

/**
 * Turns raw touches into mouse intent. Gestures:
 *  - one finger moves the pointer (and NEVER while two fingers are down, so a scroll cannot nudge the cursor);
 *  - a quick tap is a left click;
 *  - a tap followed at once by touch-and-move is a drag (left button held until the finger lifts);
 *  - a quick tap of two fingers is a right click;
 *  - two fingers moving together scroll (vertical and horizontal).
 * Pure and deterministic: time comes from the events, so it is fully unit-testable.
 * Sub-pixel motion is carried over between events, so slow movement is never lost to rounding.
 */
class TouchpadEngine(private val sink: InputSink, var config: TouchpadConfig = TouchpadConfig()) {

    private enum class Mode { IDLE, POINTING, DRAGGING, SCROLLING, TWO_FINGER_PENDING }

    private var mode = Mode.IDLE
    private var downTimeMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTimeMs = 0L
    private var travelled = false

    /** Time the previous quick tap lifted, or [NO_TAP]. Compared explicitly: subtracting MIN_VALUE would overflow. */
    private var lastTapUpMs = NO_TAP
    private var candidateDoubleTap = false
    private var leftHeld = false

    private var remX = 0f
    private var remY = 0f
    private var pendingDx = 0
    private var pendingDy = 0
    /** Time of the last motion flush. Starts far in the past (not MIN_VALUE: `now - MIN_VALUE` overflows). */
    private var lastFlushMs = -1_000_000_000L
    private var smoothedDx = 0f
    private var smoothedDy = 0f

    private var scrollRemX = 0f
    private var scrollRemY = 0f
    private var twoStartMs = 0L
    private var twoStartCenterX = 0f
    private var twoStartCenterY = 0f
    private var twoLastCenterX = 0f
    private var twoLastCenterY = 0f
    private var twoMovedPastSlop = false
    private var maxPointers = 0

    val isDragging: Boolean get() = leftHeld

    fun onTouch(e: TouchEvent) {
        when (e.action) {
            TouchAction.DOWN -> onDown(e)
            TouchAction.MOVE -> onMove(e)
            TouchAction.UP -> onUp(e)
            TouchAction.CANCEL -> cancel()
        }
    }

    /** Abort everything and leave nothing pressed (screen locked, activity paused, gesture stolen). */
    fun cancel() {
        flushMove()
        if (leftHeld) {
            sink.mouseButton(MouseButton.LEFT, false)
            leftHeld = false
        }
        mode = Mode.IDLE
        candidateDoubleTap = false
        lastTapUpMs = NO_TAP
        resetMotionState()
    }

    private fun resetMotionState() {
        remX = 0f; remY = 0f; pendingDx = 0; pendingDy = 0
        smoothedDx = 0f; smoothedDy = 0f; scrollRemX = 0f; scrollRemY = 0f
        maxPointers = 0
    }

    private fun onDown(e: TouchEvent) {
        val n = e.pointers.size
        maxPointers = maxOf(maxPointers, n)
        if (n == 1) {
            val p = e.pointers[0]
            downTimeMs = e.timeMs; downX = p.x; downY = p.y
            lastX = p.x; lastY = p.y; lastTimeMs = e.timeMs
            travelled = false
            lastFlushMs = e.timeMs - config.minMoveIntervalMs - 1 // first motion of a gesture goes out immediately
            candidateDoubleTap = lastTapUpMs != NO_TAP && e.timeMs - lastTapUpMs <= config.doubleTapWindowMs
            mode = Mode.POINTING
        } else if (n == 2) {
            // A second finger turns whatever was happening into a two-finger gesture. Any pending
            // pointer motion is flushed first so nothing is lost, and the pointer stays put from now on.
            flushMove()
            if (leftHeld) { sink.mouseButton(MouseButton.LEFT, false); leftHeld = false }
            candidateDoubleTap = false
            mode = Mode.TWO_FINGER_PENDING
            twoStartMs = e.timeMs
            val (cx, cy) = center(e.pointers)
            twoStartCenterX = cx; twoStartCenterY = cy; twoLastCenterX = cx; twoLastCenterY = cy
            twoMovedPastSlop = false
            scrollRemX = 0f; scrollRemY = 0f
        }
    }

    private fun onMove(e: TouchEvent) {
        when (mode) {
            Mode.POINTING, Mode.DRAGGING -> if (e.pointers.size == 1) movePointer(e)
            Mode.TWO_FINGER_PENDING, Mode.SCROLLING -> if (e.pointers.size >= 2) moveScroll(e)
            Mode.IDLE -> {}
        }
    }

    private fun movePointer(e: TouchEvent) {
        val p = e.pointers[0]
        val dxRaw = p.x - lastX
        val dyRaw = p.y - lastY
        lastX = p.x; lastY = p.y

        if (!travelled && hypot(p.x - downX, p.y - downY) < config.tapSlopPx) return // still within tap slop
        if (!travelled) {
            travelled = true
            if (candidateDoubleTap && mode == Mode.POINTING) {
                // tap, then touch-and-move: this is a drag. Hold the left button for its duration.
                sink.mouseButton(MouseButton.LEFT, true)
                leftHeld = true
                mode = Mode.DRAGGING
            }
            return // do not apply the slop distance itself as motion
        }

        val dtMs = maxOf(1L, e.timeMs - lastTimeMs)
        lastTimeMs = e.timeMs
        var dx = dxRaw
        var dy = dyRaw
        if (config.smoothing > 0f) {
            val a = config.smoothing.coerceIn(0f, 0.8f)
            smoothedDx = smoothedDx * a + dx * (1 - a)
            smoothedDy = smoothedDy * a + dy * (1 - a)
            dx = smoothedDx; dy = smoothedDy
        }
        val speed = hypot(dx, dy) / dtMs // px per ms
        val gain = config.sensitivity * (1f + config.acceleration * minOf(speed, 3f) / 3f)
        remX += dx * gain
        remY += dy * gain
        val ix = remX.toInt() // truncation toward zero keeps the remainder's sign consistent
        val iy = remY.toInt()
        remX -= ix; remY -= iy
        pendingDx += ix; pendingDy += iy
        if (e.timeMs - lastFlushMs >= config.minMoveIntervalMs) flushMove(e.timeMs)
    }

    private fun flushMove(nowMs: Long? = null) {
        if (pendingDx != 0 || pendingDy != 0) {
            sink.mouseMove(pendingDx, pendingDy)
            pendingDx = 0; pendingDy = 0
        }
        if (nowMs != null) lastFlushMs = nowMs
    }

    private fun moveScroll(e: TouchEvent) {
        val (cx, cy) = center(e.pointers)
        if (mode == Mode.TWO_FINGER_PENDING) {
            if (hypot(cx - twoStartCenterX, cy - twoStartCenterY) < config.scrollStartPx) return
            mode = Mode.SCROLLING
            twoMovedPastSlop = true
        }
        val dx = cx - twoLastCenterX
        val dy = cy - twoLastCenterY
        twoLastCenterX = cx; twoLastCenterY = cy
        val sign = if (config.naturalScroll) 1f else -1f
        val perPx = 120f / config.scrollPixelsPerNotch
        // Wheel units: positive dy scrolls up / away from the user, positive dx scrolls right.
        // Natural scrolling: the content follows the fingers. A finger moving UP (screen dy < 0) drags
        // the content up, i.e. the view moves DOWN => negative wheel. Moving RIGHT views content to the LEFT.
        scrollRemY += dy * sign * perPx
        scrollRemX += -dx * sign * perPx
        val iy = scrollRemY.toInt()
        val ix = scrollRemX.toInt()
        if (ix != 0 || iy != 0) {
            scrollRemY -= iy; scrollRemX -= ix
            sink.wheel(ix, iy)
        }
    }

    private fun onUp(e: TouchEvent) {
        val remaining = e.pointers.size // pointers still down AFTER this one lifted
        when (mode) {
            Mode.POINTING -> {
                flushMove(e.timeMs)
                val quick = e.timeMs - downTimeMs <= config.tapMaxMs
                if (!travelled && quick) {
                    // Tap => click. A tap soon after a previous tap is just the second click of a double-click.
                    sink.mouseButton(MouseButton.LEFT, true)
                    sink.mouseButton(MouseButton.LEFT, false)
                    lastTapUpMs = if (candidateDoubleTap) NO_TAP else e.timeMs
                } else {
                    lastTapUpMs = NO_TAP
                }
                mode = Mode.IDLE
                resetMotionState()
            }
            Mode.DRAGGING -> {
                flushMove(e.timeMs)
                sink.mouseButton(MouseButton.LEFT, false)
                leftHeld = false
                lastTapUpMs = NO_TAP
                mode = Mode.IDLE
                resetMotionState()
            }
            Mode.TWO_FINGER_PENDING, Mode.SCROLLING -> {
                if (mode == Mode.TWO_FINGER_PENDING && !twoMovedPastSlop && maxPointers == 2 && e.timeMs - twoStartMs <= config.tapMaxMs) {
                    sink.mouseButton(MouseButton.RIGHT, true)
                    sink.mouseButton(MouseButton.RIGHT, false)
                }
                if (remaining <= 1) {
                    // One finger left after a two-finger gesture must not suddenly start moving the pointer.
                    mode = Mode.IDLE
                    lastTapUpMs = NO_TAP
                    resetMotionState()
                }
            }
            Mode.IDLE -> {}
        }
    }

    private fun center(points: List<TouchSample>): Pair<Float, Float> {
        val a = points[0]
        val b = points[1]
        return (a.x + b.x) / 2f to (a.y + b.y) / 2f
    }

    private fun hypot(a: Float, b: Float): Float = kotlin.math.sqrt(a * a + b * b)

    private companion object {
        const val NO_TAP = Long.MIN_VALUE
    }
}
