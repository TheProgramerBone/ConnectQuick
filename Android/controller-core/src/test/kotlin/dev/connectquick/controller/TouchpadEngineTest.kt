package dev.connectquick.controller

import dev.connectquick.controller.input.InputSink
import dev.connectquick.controller.input.MouseButton
import dev.connectquick.controller.input.TouchAction
import dev.connectquick.controller.input.TouchEvent
import dev.connectquick.controller.input.TouchSample
import dev.connectquick.controller.input.TouchpadConfig
import dev.connectquick.controller.input.TouchpadEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadEngineTest {
    private class Rec : InputSink {
        val events = mutableListOf<String>()
        var sumDx = 0
        var sumDy = 0
        var wheelX = 0
        var wheelY = 0
        override fun mouseMove(dx: Int, dy: Int) { sumDx += dx; sumDy += dy; events += "move" }
        override fun mouseButton(button: MouseButton, down: Boolean) { events += "${button.name.lowercase()}-${if (down) "down" else "up"}" }
        override fun wheel(dx: Int, dy: Int) { wheelX += dx; wheelY += dy; events += "wheel" }
        fun buttons() = events.filter { it != "move" && it != "wheel" }
        fun moves() = events.count { it == "move" }
    }

    // Plain 1:1 motion so expectations are exact; individual tests turn features on.
    private val plain = TouchpadConfig(sensitivity = 1f, acceleration = 0f, smoothing = 0f, minMoveIntervalMs = 0)

    private fun p(id: Int, x: Float, y: Float) = TouchSample(id, x, y)
    private fun ev(a: TouchAction, t: Long, vararg pts: TouchSample) = TouchEvent(a, t, pts.toList())

    private fun engine(config: TouchpadConfig = plain): Pair<TouchpadEngine, Rec> {
        val rec = Rec()
        return TouchpadEngine(rec, config) to rec
    }

    /** Drags one finger from (x0,y0) to (x1,y1) in `steps` equal steps, `dt` ms apart, starting at t0. Returns the time of the last move. */
    private fun TouchpadEngine.drag(t0: Long, x0: Float, y0: Float, x1: Float, y1: Float, steps: Int, dt: Long = 16): Long {
        var t = t0
        for (i in 1..steps) {
            t += dt
            onTouch(ev(TouchAction.MOVE, t, p(0, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps)))
        }
        return t
    }

    @Test
    fun one_finger_moves_the_pointer_by_the_distance_travelled() {
        val (e, r) = engine()
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 100f, 100f)))
        val t = e.drag(0, 100f, 100f, 300f, 160f, steps = 20)
        e.onTouch(ev(TouchAction.UP, t + 16))
        // the slop distance travelled before the gesture is recognised as movement is not applied
        assertTrue("dx was ${r.sumDx}", r.sumDx in 170..200)
        assertTrue("dy was ${r.sumDy}", r.sumDy in 45..60)
        assertTrue(r.buttons().isEmpty())
    }

    @Test
    fun pointer_motion_reaches_the_pc_while_the_finger_is_still_down() {
        // Regression: with a flush interval the first motion used to be held back until the finger lifted.
        val (e, r) = engine(plain.copy(minMoveIntervalMs = 8))
        e.onTouch(ev(TouchAction.DOWN, 1000, p(0, 0f, 0f)))
        e.drag(1000, 0f, 0f, 300f, 0f, steps = 30)
        assertTrue("only ${r.moves()} moves before lifting", r.moves() >= 20)
        assertTrue("only ${r.sumDx} px delivered before lifting", r.sumDx > 250)
    }

    @Test
    fun a_quick_tap_is_a_left_click_even_with_a_little_finger_jitter() {
        val (e, r) = engine()
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 50f, 50f)))
        e.onTouch(ev(TouchAction.MOVE, 20, p(0, 53f, 51f)))
        e.onTouch(ev(TouchAction.UP, 90))
        assertEquals(listOf("left-down", "left-up"), r.buttons())
        assertEquals(0, r.moves())
    }

    @Test
    fun a_long_press_without_movement_does_nothing() {
        val (e, r) = engine()
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 50f, 50f)))
        e.onTouch(ev(TouchAction.UP, 900))
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun two_quick_taps_are_two_clicks() {
        val (e, r) = engine()
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 50f, 50f)));  e.onTouch(ev(TouchAction.UP, 60))
        e.onTouch(ev(TouchAction.DOWN, 150, p(0, 50f, 50f))); e.onTouch(ev(TouchAction.UP, 210))
        assertEquals(listOf("left-down", "left-up", "left-down", "left-up"), r.buttons())
    }

    @Test
    fun tap_then_touch_and_move_drags_with_the_left_button_held_until_release() {
        val (e, r) = engine()
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 50f, 50f)));  e.onTouch(ev(TouchAction.UP, 60))
        r.events.clear()
        e.onTouch(ev(TouchAction.DOWN, 150, p(0, 50f, 50f)))
        val t = e.drag(150, 50f, 50f, 250f, 50f, steps = 10)
        assertTrue(e.isDragging)
        assertEquals("left-down", r.buttons().single())
        e.onTouch(ev(TouchAction.UP, t + 16))
        assertEquals(listOf("left-down", "left-up"), r.buttons())
        assertFalse(e.isDragging)
        assertTrue(r.sumDx > 60)
    }

    @Test
    fun a_two_finger_tap_is_a_right_click_and_never_moves_the_pointer() {
        val (e, r) = engine()
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 100f, 100f)))
        e.onTouch(ev(TouchAction.DOWN, 10, p(0, 100f, 100f), p(1, 160f, 100f)))
        e.onTouch(ev(TouchAction.UP, 80, p(1, 160f, 100f)))
        e.onTouch(ev(TouchAction.UP, 85))
        assertEquals(listOf("right-down", "right-up"), r.buttons())
        assertEquals(0, r.moves())
    }

    @Test
    fun two_fingers_moving_together_scroll_and_do_not_move_the_pointer() {
        val (e, r) = engine(plain.copy(scrollPixelsPerNotch = 36f, naturalScroll = true))
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 100f, 300f)))
        e.onTouch(ev(TouchAction.DOWN, 5, p(0, 100f, 300f), p(1, 160f, 300f)))
        var y = 300f
        var t = 5L
        repeat(18) { // fingers travel 72 px upward
            y -= 4f; t += 16
            e.onTouch(ev(TouchAction.MOVE, t, p(0, 100f, y), p(1, 160f, y)))
        }
        e.onTouch(ev(TouchAction.UP, t + 16, p(1, 160f, y)))
        e.onTouch(ev(TouchAction.UP, t + 20))
        assertEquals("a scroll must not move the cursor", 0, r.moves())
        assertTrue("natural scroll: fingers up => wheel down (negative), was ${r.wheelY}", r.wheelY in -245..-200)
        assertTrue("no accidental right click", r.buttons().isEmpty())
    }

    @Test
    fun traditional_scroll_direction_inverts_the_wheel() {
        val (e, r) = engine(plain.copy(naturalScroll = false))
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 100f, 300f)))
        e.onTouch(ev(TouchAction.DOWN, 5, p(0, 100f, 300f), p(1, 160f, 300f)))
        var y = 300f; var t = 5L
        repeat(18) { y -= 4f; t += 16; e.onTouch(ev(TouchAction.MOVE, t, p(0, 100f, y), p(1, 160f, y))) }
        assertTrue(r.wheelY > 200)
    }

    @Test
    fun horizontal_two_finger_motion_scrolls_sideways() {
        val (e, r) = engine()
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 100f, 300f)))
        e.onTouch(ev(TouchAction.DOWN, 5, p(0, 100f, 300f), p(1, 160f, 300f)))
        var x = 0f; var t = 5L
        repeat(18) { x += 4f; t += 16; e.onTouch(ev(TouchAction.MOVE, t, p(0, 100f + x, 300f), p(1, 160f + x, 300f))) }
        assertTrue("fingers right => content follows => wheel left (negative), was ${r.wheelX}", r.wheelX < -200)
        assertTrue(r.wheelY in -3..3)
    }

    @Test
    fun lifting_one_finger_after_a_scroll_does_not_start_moving_the_pointer() {
        val (e, r) = engine()
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 100f, 300f)))
        e.onTouch(ev(TouchAction.DOWN, 5, p(0, 100f, 300f), p(1, 160f, 300f)))
        e.onTouch(ev(TouchAction.MOVE, 40, p(0, 100f, 250f), p(1, 160f, 250f)))
        e.onTouch(ev(TouchAction.UP, 60, p(0, 100f, 250f)))     // second finger leaves, first stays down
        e.onTouch(ev(TouchAction.MOVE, 80, p(0, 140f, 200f)))   // the remaining finger drifts
        e.onTouch(ev(TouchAction.MOVE, 100, p(0, 200f, 150f)))
        assertEquals(0, r.moves())
    }

    @Test
    fun three_fingers_are_not_a_right_click() {
        val (e, r) = engine()
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 100f, 100f)))
        e.onTouch(ev(TouchAction.DOWN, 5, p(0, 100f, 100f), p(1, 160f, 100f)))
        e.onTouch(ev(TouchAction.DOWN, 10, p(0, 100f, 100f), p(1, 160f, 100f), p(2, 220f, 100f)))
        e.onTouch(ev(TouchAction.UP, 60, p(1, 160f, 100f), p(2, 220f, 100f)))
        assertTrue(r.buttons().isEmpty())
    }

    @Test
    fun cancel_releases_a_held_drag_so_nothing_stays_pressed() {
        val (e, r) = engine()
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 50f, 50f)));  e.onTouch(ev(TouchAction.UP, 60))
        e.onTouch(ev(TouchAction.DOWN, 150, p(0, 50f, 50f)))
        e.drag(150, 50f, 50f, 150f, 50f, steps = 6)
        assertTrue(e.isDragging)
        e.cancel() // e.g. the activity was paused or the screen locked mid-drag
        assertFalse(e.isDragging)
        assertEquals("left-up", r.buttons().last())
    }

    @Test
    fun slow_movement_is_not_lost_to_rounding() {
        val (e, r) = engine()
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 0f, 0f)))
        e.onTouch(ev(TouchAction.MOVE, 16, p(0, 20f, 0f))) // leave the tap slop
        val before = r.sumDx
        var x = 20f; var t = 16L
        repeat(100) { x += 0.3f; t += 16; e.onTouch(ev(TouchAction.MOVE, t, p(0, x, 0f))) }
        e.onTouch(ev(TouchAction.UP, t + 16))
        assertTrue("moved ${r.sumDx - before} of 30 px", r.sumDx - before in 28..30)
    }

    @Test
    fun sensitivity_scales_travel() {
        val (slow, rs) = engine(plain.copy(sensitivity = 1f))
        val (fast, rf) = engine(plain.copy(sensitivity = 2f))
        for ((eng, _) in listOf(slow to rs, fast to rf)) {
            eng.onTouch(ev(TouchAction.DOWN, 0, p(0, 0f, 0f)))
            val t = eng.drag(0, 0f, 0f, 200f, 0f, steps = 20)
            eng.onTouch(ev(TouchAction.UP, t + 16))
        }
        assertTrue(rf.sumDx > rs.sumDx * 1.8)
    }

    @Test
    fun a_fast_swipe_travels_further_than_a_slow_one_when_acceleration_is_on() {
        val cfg = plain.copy(acceleration = 1f)
        val (slow, rs) = engine(cfg)
        val (fast, rf) = engine(cfg)
        slow.onTouch(ev(TouchAction.DOWN, 0, p(0, 0f, 0f)))
        slow.onTouch(ev(TouchAction.UP, slow.drag(0, 0f, 0f, 200f, 0f, steps = 40, dt = 40) + 10))
        fast.onTouch(ev(TouchAction.DOWN, 0, p(0, 0f, 0f)))
        fast.onTouch(ev(TouchAction.UP, fast.drag(0, 0f, 0f, 200f, 0f, steps = 8, dt = 4) + 10))
        assertTrue("fast ${rf.sumDx} should beat slow ${rs.sumDx}", rf.sumDx > rs.sumDx + 20)
    }

    @Test
    fun motion_inside_the_flush_interval_is_summed_into_one_message_without_losing_distance() {
        val (e, r) = engine(plain.copy(minMoveIntervalMs = 8))
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 0f, 0f)))
        e.onTouch(ev(TouchAction.MOVE, 1, p(0, 20f, 0f)))
        val base = r.moves()
        for (i in 1..40) e.onTouch(ev(TouchAction.MOVE, 1L + i, p(0, 20f + i * 2f, 0f))) // 40 events in 40 ms
        e.onTouch(ev(TouchAction.UP, 60))
        assertTrue("sent ${r.moves() - base} messages for 40 events", r.moves() - base <= 8)
        assertTrue("distance ${r.sumDx}", r.sumDx in 78..82)
    }

    @Test
    fun drag_lock_holds_the_button_across_lifts_and_a_tap_releases_it() {
        val (e, r) = engine()
        val changes = mutableListOf<Boolean>()
        e.onDragLockChanged = { changes += it }
        e.setDragLock(true)
        assertEquals(listOf("left-down"), r.buttons())
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 0f, 0f)))
        e.onTouch(ev(TouchAction.UP, e.drag(0, 0f, 0f, 100f, 0f, steps = 10) + 16))
        assertEquals("a lift must not release a locked drag", listOf("left-down"), r.buttons())
        assertTrue(r.sumDx > 0)
        // A second stroke keeps dragging, and is not taken for a double-tap drag.
        e.onTouch(ev(TouchAction.DOWN, 400, p(0, 0f, 0f)))
        e.onTouch(ev(TouchAction.UP, e.drag(400, 0f, 0f, 0f, 50f, steps = 5) + 16))
        assertEquals(listOf("left-down"), r.buttons())
        assertTrue(e.dragLocked)
        // Tap: let go.
        e.onTouch(ev(TouchAction.DOWN, 1000, p(0, 10f, 10f)))
        e.onTouch(ev(TouchAction.UP, 1050, p(0, 10f, 10f)).copy(pointers = emptyList()))
        assertEquals(listOf("left-down", "left-up"), r.buttons())
        assertFalse(e.dragLocked)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun cancel_releases_a_locked_drag_and_reports_it() {
        val (e, r) = engine()
        var last: Boolean? = null
        e.onDragLockChanged = { last = it }
        e.setDragLock(true)
        e.cancel()
        assertEquals(listOf("left-down", "left-up"), r.buttons())
        assertFalse(e.dragLocked)
        assertEquals(false, last)
    }

    @Test
    fun a_locked_drag_survives_a_two_finger_scroll() {
        val (e, r) = engine()
        e.setDragLock(true)
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 0f, 0f)))
        e.onTouch(ev(TouchAction.DOWN, 5, p(0, 0f, 0f), p(1, 40f, 0f)))
        e.onTouch(ev(TouchAction.MOVE, 30, p(0, 0f, 60f), p(1, 40f, 60f)))
        e.onTouch(ev(TouchAction.UP, 60, p(1, 40f, 60f)))
        e.onTouch(ev(TouchAction.UP, 70))
        assertEquals(listOf("left-down"), r.buttons())
        assertTrue(r.wheelY != 0)
        assertTrue(e.dragLocked)
    }
}
