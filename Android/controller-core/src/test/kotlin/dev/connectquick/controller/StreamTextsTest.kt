package dev.connectquick.controller

import dev.connectquick.controller.session.Tone
import dev.connectquick.controller.stream.StreamStats
import dev.connectquick.controller.stream.StreamStatus
import dev.connectquick.controller.stream.StreamStatus.Phase
import dev.connectquick.controller.stream.StreamTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTextsTest {
    @Test
    fun only_a_streaming_phase_shows_video_and_only_unblocked_streaming_says_live() {
        for (p in Phase.values()) {
            val t = StreamTexts.of(StreamStatus(p))
            assertEquals("$p", p == Phase.STREAMING, t.showVideo)
            assertEquals("$p", p == Phase.STREAMING, t.headline == "En directo")
        }
    }

    @Test
    fun live_stats_are_summarised() {
        val t = StreamTexts.of(StreamStatus(Phase.STREAMING, width = 1920, height = 1080, stats = StreamStats(24.4, 1500, 1920, 1080, 38)))
        assertEquals(Tone.OK, t.tone)
        assertTrue(t.detail, t.detail.contains("1920×1080") && t.detail.contains("24 fps") && t.detail.contains("1500 kbps") && t.detail.contains("38 ms"))
    }

    @Test
    fun a_locked_screen_is_explained_not_shown_as_black() {
        val t = StreamTexts.of(StreamStatus(Phase.STREAMING, blocked = "secure_desktop"))
        assertEquals(Tone.WARN, t.tone)
        assertTrue(t.headline.contains("protegida"))
        assertTrue(t.detail.contains("UAC"))
        assertFalse(t.headline == "En directo")
    }

    @Test
    fun network_failures_say_whether_a_relay_could_have_helped() {
        assertTrue(StreamTexts.of(StreamStatus(Phase.ERROR, "ice_failed_no_relay")).detail.contains("TURN"))
        assertTrue(StreamTexts.of(StreamStatus(Phase.ERROR, "ice_failed")).detail.contains("relevo"))
        assertEquals(Tone.BAD, StreamTexts.of(StreamStatus(Phase.ERROR, "forbidden")).tone)
        assertTrue(StreamTexts.of(StreamStatus(Phase.ERROR, "disabled_by_host")).detail.contains("desactivada"))
    }

    @Test
    fun every_phase_and_common_reason_has_text() {
        for (p in Phase.values()) for (r in listOf(null, "", "weird_code")) {
            val t = StreamTexts.of(StreamStatus(p, r))
            assertTrue(t.headline.isNotBlank() && t.detail.isNotBlank())
        }
    }
}
