package dev.connectquick.controller

import dev.connectquick.controller.input.InputSink
import dev.connectquick.controller.input.MouseButton
import dev.connectquick.controller.input.Shortcuts
import dev.connectquick.controller.input.TouchAction
import dev.connectquick.controller.input.TouchEvent
import dev.connectquick.controller.input.TouchGesture
import dev.connectquick.controller.input.TouchSample
import dev.connectquick.controller.input.TouchpadConfig
import dev.connectquick.controller.input.TouchpadEngine
import dev.connectquick.controller.input.Viewport
import dev.connectquick.controller.input.VirtualPointer
import dev.connectquick.controller.stream.AutoQuality
import dev.connectquick.controller.stream.StreamProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureTest {
    private class Rec : InputSink {
        val log = mutableListOf<String>()
        var wheelY = 0
        override fun mouseMove(dx: Int, dy: Int) { log += "move" }
        override fun mouseButton(button: MouseButton, down: Boolean) { log += "${button.name.lowercase()}-${if (down) "down" else "up"}" }
        override fun wheel(dx: Int, dy: Int) { wheelY += dy; log += "wheel" }
        override fun key(code: String, down: Boolean) { log += "$code-${if (down) "down" else "up"}" }
        fun nonMotion() = log.filter { it != "move" && it != "wheel" }
    }

    private val cfg = TouchpadConfig(sensitivity = 1f, acceleration = 0f, minMoveIntervalMs = 0)
    private fun p(id: Int, x: Float, y: Float) = TouchSample(id, x, y)
    private fun ev(a: TouchAction, t: Long, vararg pts: TouchSample) = TouchEvent(a, t, pts.toList())

    private fun threeFingerStroke(e: TouchpadEngine, dx: Float, dy: Float, upAt: Long) {
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 100f, 300f)))
        e.onTouch(ev(TouchAction.DOWN, 5, p(0, 100f, 300f), p(1, 160f, 300f)))
        e.onTouch(ev(TouchAction.DOWN, 10, p(0, 100f, 300f), p(1, 160f, 300f), p(2, 220f, 300f)))
        e.onTouch(ev(TouchAction.MOVE, 60, p(0, 100f + dx, 300f + dy), p(1, 160f + dx, 300f + dy), p(2, 220f + dx, 300f + dy)))
        e.onTouch(ev(TouchAction.UP, upAt, p(1, 160f + dx, 300f + dy), p(2, 220f + dx, 300f + dy)))
        e.onTouch(ev(TouchAction.UP, upAt + 5, p(2, 220f + dx, 300f + dy)))
        e.onTouch(ev(TouchAction.UP, upAt + 10))
    }

    @Test
    fun three_finger_swipes_report_their_direction_and_nothing_else() {
        for ((d, expected) in listOf(Pair(150f to 0f, TouchGesture.SWIPE_RIGHT), Pair(-150f to 0f, TouchGesture.SWIPE_LEFT),
            Pair(0f to -150f, TouchGesture.SWIPE_UP), Pair(0f to 150f, TouchGesture.SWIPE_DOWN))) {
            val r = Rec()
            val e = TouchpadEngine(r, cfg)
            val got = mutableListOf<TouchGesture>()
            e.onGesture = { got += it }
            threeFingerStroke(e, d.first, d.second, upAt = 120)
            assertEquals(listOf(expected), got)
            assertEquals("no clicks or scrolls from a swipe", emptyList<String>(), r.nonMotion())
            assertEquals(0, r.wheelY)
        }
    }

    @Test
    fun a_quick_three_finger_tap_is_a_middle_click() {
        val r = Rec()
        val e = TouchpadEngine(r, cfg)
        var gestures = 0
        e.onGesture = { gestures++ }
        threeFingerStroke(e, 0f, 0f, upAt = 150)
        assertEquals(listOf("middle-down", "middle-up"), r.nonMotion())
        assertEquals(0, gestures)
    }

    @Test
    fun spreading_two_fingers_zooms_with_ctrl_wheel_and_releases_ctrl() {
        val r = Rec()
        val e = TouchpadEngine(r, cfg)
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 200f, 300f)))
        e.onTouch(ev(TouchAction.DOWN, 5, p(0, 200f, 300f), p(1, 260f, 300f)))
        var t = 5L
        for (i in 1..10) { t += 16; e.onTouch(ev(TouchAction.MOVE, t, p(0, 200f - i * 10f, 300f), p(1, 260f + i * 10f, 300f))) }
        e.onTouch(ev(TouchAction.UP, t + 10, p(1, 360f, 300f)))
        e.onTouch(ev(TouchAction.UP, t + 20))
        assertTrue("zoom in = wheel up, got ${r.wheelY}", r.wheelY > 0)
        assertEquals(listOf("ControlLeft-down", "ControlLeft-up"), r.nonMotion())
    }

    @Test
    fun cancel_during_a_pinch_never_leaves_ctrl_held() {
        val r = Rec()
        val e = TouchpadEngine(r, cfg)
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 200f, 300f)))
        e.onTouch(ev(TouchAction.DOWN, 5, p(0, 200f, 300f), p(1, 260f, 300f)))
        e.onTouch(ev(TouchAction.MOVE, 30, p(0, 150f, 300f), p(1, 310f, 300f)))
        e.cancel()
        assertEquals(listOf("ControlLeft-down", "ControlLeft-up"), r.nonMotion())
    }

    @Test
    fun parallel_two_finger_motion_still_scrolls_not_zooms() {
        val r = Rec()
        val e = TouchpadEngine(r, cfg)
        e.onTouch(ev(TouchAction.DOWN, 0, p(0, 200f, 300f)))
        e.onTouch(ev(TouchAction.DOWN, 5, p(0, 200f, 300f), p(1, 260f, 300f)))
        e.onTouch(ev(TouchAction.MOVE, 30, p(0, 200f, 200f), p(1, 260f, 200f)))
        e.onTouch(ev(TouchAction.UP, 40, p(1, 260f, 200f)))
        e.onTouch(ev(TouchAction.UP, 50))
        assertTrue(r.wheelY != 0)
        assertFalse(r.log.any { it.startsWith("ControlLeft") })
    }

    @Test
    fun gestures_map_to_windows_touchpad_conventions() {
        assertEquals(listOf("MetaLeft", "Tab"), Shortcuts.forGesture(TouchGesture.SWIPE_UP).keys)
        assertEquals(listOf("MetaLeft", "KeyD"), Shortcuts.forGesture(TouchGesture.SWIPE_DOWN).keys)
        assertEquals(listOf("AltLeft", "Tab"), Shortcuts.forGesture(TouchGesture.SWIPE_RIGHT).keys)
        assertEquals(listOf("AltLeft", "ShiftLeft", "Tab"), Shortcuts.forGesture(TouchGesture.SWIPE_LEFT).keys)
    }
}

class VirtualPointerTest {
    @Test
    fun relative_motion_becomes_absolute_positions_and_zoom_slows_it_down() {
        val sent = mutableListOf<Pair<Int, Int>>()
        val v = VirtualPointer { x, y -> sent += x to y }.apply { viewWidth = 1000f; viewHeight = 500f }
        v.moveTo(0.5f, 0.5f)
        v.mouseMove(100, 0)
        assertEquals(0.6f, v.x, 1e-4f)
        v.zoom = 4f
        v.mouseMove(100, 0)
        assertEquals(0.625f, v.x, 1e-4f)
        v.mouseMove(-100_000, 100_000)
        assertEquals(0f, v.x, 0f); assertEquals(1f, v.y, 0f)
        assertEquals(0 to 65535, sent.last())
    }

    @Test
    fun buttons_go_to_the_real_sink() {
        val log = mutableListOf<String>()
        val v = VirtualPointer { _, _ -> }
        v.buttons = object : InputSink {
            override fun mouseMove(dx: Int, dy: Int) { log += "move" }
            override fun mouseButton(button: MouseButton, down: Boolean) { log += "$button$down" }
            override fun wheel(dx: Int, dy: Int) { log += "wheel" }
        }
        v.mouseButton(MouseButton.LEFT, true); v.wheel(0, 120)
        assertEquals(listOf("LEFTtrue", "wheel"), log)
    }

    @Test
    fun viewport_follows_a_pointer_that_leaves_the_zoomed_area() {
        val vp = Viewport().apply { setSize(1000f, 500f) }
        assertFalse(vp.ensureVisible(1f, 1f))
        vp.zoomBy(2f, 0f, 0f)
        assertTrue(vp.ensureVisible(0.9f, 0.9f))
        val sx = 0.9f * 1000f * vp.scale + vp.offsetX
        assertTrue("pointer at $sx should be on screen", sx in 0f..1000f)
    }
}

class AutoQualityTest {
    @Test
    fun picks_by_network_and_steps_down_only_after_sustained_delay() {
        val a = AutoQuality(highRttMs = 250, samplesToStepDown = 3)
        assertNull(a.onNetwork(isMetered = false))
        assertEquals(StreamProfile.VIDEO.request, a.current)
        assertNull(a.onStats(400)); assertNull(a.onStats(100)) // a blip resets the count
        assertNull(a.onStats(400)); assertNull(a.onStats(400))
        assertEquals(StreamProfile.NORMAL.request, a.onStats(400))
        assertEquals(StreamProfile.SAVER.request, a.onNetwork(isMetered = true))
        assertNull(a.onStats(900)); assertNull(a.onStats(900)); assertNull(a.onStats(900)) // already at the bottom
        assertEquals(StreamProfile.VIDEO.request, a.onNetwork(isMetered = false))
        assertNull(a.onNetwork(isMetered = false))
    }

    @Test
    fun game_profile_asks_for_60_fps() {
        assertEquals(60, StreamProfile.GAME.request.maxFps)
        assertTrue(StreamProfile.GAME.lowLatency)
    }
}
