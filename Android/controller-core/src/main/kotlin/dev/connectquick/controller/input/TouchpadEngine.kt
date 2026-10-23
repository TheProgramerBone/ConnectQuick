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
    /** Two fingers spreading/pinching zoom on the PC (Ctrl + wheel) instead of scrolling. */
    val pinchZoom: Boolean = true,
    /** Change in finger distance that starts a pinch (must also beat the fingers' common travel). */
    val pinchStartPx: Float = 28f,
    /** Change in finger distance that makes one zoom notch. */
    val pinchPixelsPerNotch: Float = 40f,
    /** Three fingers must travel this far to count as a swipe; less (and quick) is a three-finger tap. */
    val threeSwipePx: Float = 60f,
)

/** Three-finger swipes. The app maps them to Windows shortcuts (see `Shortcuts.forGesture`). */
enum class TouchGesture { SWIPE_LEFT, SWIPE_RIGHT, SWIPE_UP, SWIPE_DOWN }

/**
 * Turns raw touches into mouse intent. Gestures:
 *  - one finger moves the pointer (and NEVER while two fingers are down, so a scroll cannot nudge the cursor);
 *  - a quick tap is a left click;
 *  - a tap followed at once by touch-and-move is a drag (left button held until the finger lifts);
 *  - a quick tap of two fingers is a right click;
 *  - two fingers moving together scroll (vertical and horizontal);
 *  - two fingers spreading or pinching zoom on the PC (Ctrl + wheel);
 *  - three fingers swiping report a [TouchGesture]; a quick three-finger tap is a middle click.
 * Pure and deterministic: time comes from the events, so it is fully unit-testable.
 * Sub-pixel motion is carried over between events, so slow movement is never lost to rounding.
 */
class TouchpadEngine(private val sink: InputSink, var config: TouchpadConfig = TouchpadConfig()) {

    private enum class Mode { IDLE, POINTING, DRAGGING, SCROLLING, TWO_FINGER_PENDING, PINCHING, THREE_FINGER }

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
    private var twoStartDistance = 0f
    private var pinchRem = 0f
    private var ctrlHeld = false
    private var threeStartX = 0f
    private var threeStartY = 0f
    private var threeLastX = 0f
    private var threeLastY = 0f
    private var threeStartMs = 0L

    /** Called for a recognised three-finger swipe. */
    var onGesture: ((TouchGesture) -> Unit)? = null
    private var maxPointers = 0

    val isDragging: Boolean get() = leftHeld

    /**
     * "Bloquear arrastre": the left button stays held across finger lifts, so long selections and
     * drags need no finger kept on the glass. A tap ends it (releasing the button), as does [cancel].
     */
    var dragLocked = false
        private set
    var onDragLockChanged: ((Boolean) -> Unit)? = null

    fun setDragLock(on: Boolean) {
        if (on == dragLocked) return
        flushMove()
        if (on && !leftHeld) { sink.mouseButton(MouseButton.LEFT, true); leftHeld = true }
        if (!on && leftHeld) { sink.mouseButton(MouseButton.LEFT, false); leftHeld = false }
        dragLocked = on
        onDragLockChanged?.invoke(on)
    }

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
        if (dragLocked) {
            dragLocked = false
            onDragLockChanged?.invoke(false)
        }
        releaseCtrl()
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
            candidateDoubleTap = !dragLocked && lastTapUpMs != NO_TAP && e.timeMs - lastTapUpMs <= config.doubleTapWindowMs
            mode = Mode.POINTING
        } else if (n == 2) {
            // A second finger turns whatever was happening into a two-finger gesture. Any pending
            // pointer motion is flushed first so nothing is lost, and the pointer stays put from now on.
            flushMove()
            // A locked drag survives a two-finger scroll (dragging to the edge of a long list).
            if (leftHeld && !dragLocked) { sink.mouseButton(MouseButton.LEFT, false); leftHeld = false }
            candidateDoubleTap = false
            mode = Mode.TWO_FINGER_PENDING
            twoStartMs = e.timeMs
            val (cx, cy) = center(e.pointers)
            twoStartCenterX = cx; twoStartCenterY = cy; twoLastCenterX = cx; twoLastCenterY = cy
            twoMovedPastSlop = false
            twoStartDistance = distance(e.pointers)
            pinchRem = 0f
            scrollRemX = 0f; scrollRemY = 0f
        } else if (n == 3 && mode != Mode.THREE_FINGER) {
            releaseCtrl()
            mode = Mode.THREE_FINGER
            threeStartMs = e.timeMs
            val (cx, cy) = centroid(e.pointers)
            threeStartX = cx; threeStartY = cy; threeLastX = cx; threeLastY = cy
        }
    }

    private fun onMove(e: TouchEvent) {
        when (mode) {
            Mode.POINTING, Mode.DRAGGING -> if (e.pointers.size == 1) movePointer(e)
            Mode.TWO_FINGER_PENDING, Mode.SCROLLING -> if (e.pointers.size >= 2) moveScroll(e)
            Mode.PINCHING -> if (e.pointers.size >= 2) movePinch(e)
            Mode.THREE_FINGER -> if (e.pointers.size >= 3) { val (cx, cy) = centroid(e.pointers); threeLastX = cx; threeLastY = cy }
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
            val spread = kotlin.math.abs(distance(e.pointers) - twoStartDistance)
            val travel = hypot(cx - twoStartCenterX, cy - twoStartCenterY)
            if (config.pinchZoom && spread >= config.pinchStartPx && spread > travel) {
                // The fingers moved apart/together more than they moved as a pair: a zoom, not a scroll.
                mode = Mode.PINCHING
                twoMovedPastSlop = true
                twoStartDistance = distance(e.pointers)
                pinchRem = 0f
                sink.key("ControlLeft", true)
                ctrlHeld = true
                return
            }
            if (travel < config.scrollStartPx) return
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

    private fun movePinch(e: TouchEvent) {
        val d = distance(e.pointers)
        pinchRem += (d - twoStartDistance) / config.pinchPixelsPerNotch * 120f
        twoStartDistance = d
        val notches = pinchRem.toInt()
        if (notches != 0) {
            pinchRem -= notches
            sink.wheel(0, notches) // spreading = wheel up = zoom in with Ctrl held
        }
    }

    private fun releaseCtrl() {
        if (ctrlHeld) {
            sink.key("ControlLeft", false)
            ctrlHeld = false
        }
    }

    private fun onUp(e: TouchEvent) {
        val remaining = e.pointers.size // pointers still down AFTER this one lifted
        when (mode) {
            Mode.POINTING -> {
                flushMove(e.timeMs)
                val quick = e.timeMs - downTimeMs <= config.tapMaxMs
                if (dragLocked) {
                    // While locked a lift keeps holding; a tap is how the person lets go.
                    if (!travelled && quick) setDragLock(false)
                    lastTapUpMs = NO_TAP
                } else if (!travelled && quick) {
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
            Mode.PINCHING -> if (remaining <= 1) {
                releaseCtrl()
                mode = Mode.IDLE
                lastTapUpMs = NO_TAP
                resetMotionState()
            }
            Mode.THREE_FINGER -> if (remaining == 0) {
                val dx = threeLastX - threeStartX
                val dy = threeLastY - threeStartY
                val ax = kotlin.math.abs(dx)
                val ay = kotlin.math.abs(dy)
                when {
                    maxOf(ax, ay) >= config.threeSwipePx -> onGesture?.invoke(
                        if (ax >= ay) (if (dx > 0) TouchGesture.SWIPE_RIGHT else TouchGesture.SWIPE_LEFT)
                        else (if (dy > 0) TouchGesture.SWIPE_DOWN else TouchGesture.SWIPE_UP),
                    )
                    e.timeMs - threeStartMs <= config.tapMaxMs + 100 -> {
                        sink.mouseButton(MouseButton.MIDDLE, true)
                        sink.mouseButton(MouseButton.MIDDLE, false)
                    }
                }
                mode = Mode.IDLE
                lastTapUpMs = NO_TAP
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

    private fun distance(points: List<TouchSample>): Float =
        if (points.size < 2) 0f else hypot(points[0].x - points[1].x, points[0].y - points[1].y)

    private fun centroid(points: List<TouchSample>): Pair<Float, Float> =
        points.map { it.x }.average().toFloat() to points.map { it.y }.average().toFloat()

    private fun hypot(a: Float, b: Float): Float = kotlin.math.sqrt(a * a + b * b)

    private companion object {
        const val NO_TAP = Long.MIN_VALUE
    }
}
