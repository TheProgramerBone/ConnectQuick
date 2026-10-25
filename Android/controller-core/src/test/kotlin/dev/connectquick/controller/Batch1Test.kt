package dev.connectquick.controller

import dev.connectquick.controller.input.InputClient
import dev.connectquick.controller.input.Shortcuts
import dev.connectquick.controller.input.Viewport
import dev.connectquick.controller.stream.StreamProfile
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    private fun vp() = Viewport(maxScale = 4f).apply { setSize(1000f, 500f) }

    @Test
    fun starts_unzoomed_and_maps_touches_straight_through() {
        val v = vp()
        assertFalse(v.isZoomed)
        assertEquals(0.25f to 0.5f, v.toFraction(250f, 250f))
    }

    @Test
    fun pinch_keeps_the_point_under_the_fingers_still() {
        val v = vp()
        val before = v.toFraction(600f, 300f)
        v.zoomBy(2f, 600f, 300f)
        assertTrue(v.isZoomed)
        val after = v.toFraction(600f, 300f)
        assertEquals(before.first, after.first, 1e-4f)
        assertEquals(before.second, after.second, 1e-4f)
    }

    @Test
    fun zoom_is_bounded_and_panning_never_shows_past_an_edge() {
        val v = vp()
        v.zoomBy(100f, 0f, 0f)
        assertEquals(4f, v.scale, 0f)
        v.panBy(10_000f, 10_000f)
        assertEquals(0f, v.offsetX, 0f); assertEquals(0f, v.offsetY, 0f)
        v.panBy(-100_000f, -100_000f)
        assertEquals(-3000f, v.offsetX, 0.01f); assertEquals(-1500f, v.offsetY, 0.01f)
        assertEquals(1f to 1f, v.toFraction(1000f, 500f))
        v.zoomBy(0.0001f, 500f, 250f)
        assertEquals(1f, v.scale, 0f)
        assertEquals(0f, v.offsetX, 0f)
    }

    @Test
    fun reset_returns_to_the_whole_screen() {
        val v = vp()
        v.zoomBy(3f, 100f, 100f); v.panBy(-50f, -50f)
        v.reset()
        assertFalse(v.isZoomed)
        assertEquals(0.5f to 0.5f, v.toFraction(500f, 250f))
    }
}

class ShortcutsAndProfilesTest {
    private val codePattern = Regex("^[A-Za-z0-9]{1,24}$") // input.key "code" in the shared schema

    @Test
    fun every_shortcut_key_is_a_valid_wire_code_and_no_reserved_combo_is_offered() {
        val presentation = with(Shortcuts) { listOf(presentationStart, presentationStartHere, presentationNext, presentationPrevious, presentationBlack, presentationWhite, presentationEnd) }
        val all = Shortcuts.editing + Shortcuts.windows + presentation
        all.flatMap { it.keys }.plus(Shortcuts.functionKeys).plus(Shortcuts.keyBar.map { it.second })
            .forEach { assertTrue("bad code $it", codePattern.matches(it)) }
        assertFalse(all.any { it.keys.containsAll(listOf("ControlLeft", "AltLeft", "Delete")) })
        assertFalse(all.any { it.keys == listOf("MetaLeft", "KeyL") })
        assertEquals(12, Shortcuts.functionKeys.size)
    }

    @Test
    fun combo_presses_in_order_and_releases_in_reverse() {
        val (phone, _) = LoopbackPort.pair()
        InputClient(phone).combo(listOf("ControlLeft", "ShiftLeft", "Escape"))
        val seq = phone.sent.map { it.getValue("code").jsonPrimitive.content + ":" + it.getValue("down").jsonPrimitive.content }
        assertEquals(
            listOf("ControlLeft:true", "ShiftLeft:true", "Escape:true", "Escape:false", "ShiftLeft:false", "ControlLeft:false"),
            seq,
        )
    }

    @Test
    fun profiles_stay_inside_the_stream_start_schema_and_cycle() {
        StreamProfile.entries.forEach {
            val r = it.request
            assertTrue(r.maxWidth in 160..7680 && r.maxHeight in 120..4320 && r.maxFps in 1..60 && r.maxBitrateKbps in 100..50_000)
        }
        assertTrue(StreamProfile.TEXT.request.maxFps < StreamProfile.VIDEO.request.maxFps)
        assertTrue(StreamProfile.TEXT.request.maxWidth >= StreamProfile.NORMAL.request.maxWidth)
        assertEquals(StreamProfile.AUTO, StreamProfile.SAVER.next())
        assertEquals(StreamProfile.AUTO, StreamProfile.fromName("nonsense"))
        assertEquals(StreamProfile.VIDEO, StreamProfile.fromName("VIDEO"))
    }
}
