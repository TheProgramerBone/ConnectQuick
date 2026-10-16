package dev.connectquick.controller

import dev.connectquick.controller.net.SignalingState
import dev.connectquick.controller.session.LinkAction
import dev.connectquick.controller.session.LinkState
import dev.connectquick.controller.session.LinkState.Phase
import dev.connectquick.controller.session.LinkTexts
import dev.connectquick.controller.session.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPresentationTest {
    private fun of(phase: Phase, reason: String? = null, attempt: Int = 0, retryMs: Long = 0, sig: SignalingState = SignalingState.READY) =
        LinkTexts.of(LinkState(phase, sig, reason, attempt, retryMs), "Mi PC")

    @Test
    fun only_a_verified_online_session_may_control_and_say_connected() {
        for (phase in Phase.values()) {
            val p = of(phase)
            assertEquals("$phase", phase == Phase.ONLINE, p.canControl)
            assertEquals("$phase", phase == Phase.ONLINE, p.headline.startsWith("Conectado"))
        }
    }

    @Test
    fun every_state_explains_itself_and_names_the_pc_where_it_matters() {
        for (phase in Phase.values()) {
            val p = of(phase)
            assertTrue("$phase headline", p.headline.isNotBlank())
            assertTrue("$phase detail", p.detail.isNotBlank())
        }
        assertTrue(of(Phase.HOST_OFFLINE).headline.contains("Mi PC"))
    }

    @Test
    fun revoked_asks_to_pair_again_and_failures_offer_retry() {
        assertEquals(LinkAction.REPAIR, of(Phase.REVOKED).action)
        assertEquals(Tone.BAD, of(Phase.REVOKED).tone)
        assertEquals(LinkAction.RETRY, of(Phase.FAILED, "user_denied").action)
        assertTrue(of(Phase.FAILED, "user_denied").detail.contains("rechazó"))
        assertTrue(of(Phase.FAILED, "host_not_accepting").detail.contains("pausa"))
        assertTrue(of(Phase.FAILED, "host_busy").detail.contains("otro teléfono"))
        assertTrue(of(Phase.FAILED, "too_many_attempts:connect_timeout").detail.contains("varios intentos"))
    }

    @Test
    fun reconnecting_shows_attempt_and_countdown_without_double_punctuation() {
        val p = of(Phase.RECONNECTING, "network_lost", attempt = 2, retryMs = 2500)
        assertTrue(p.detail, p.detail.contains("Se perdió la red"))
        assertTrue(p.detail, p.detail.contains("Intento 2 de 8"))
        assertTrue(p.detail, p.detail.contains("en 3 s"))
        assertFalse(p.detail, p.detail.contains(".."))
        assertEquals(Tone.WARN, p.tone)
    }

    @Test
    fun connecting_distinguishes_authenticating() {
        assertTrue(of(Phase.CONNECTING_SIGNALING, sig = SignalingState.AUTHENTICATING).headline.contains("identidad"))
        assertTrue(of(Phase.CONNECTING_SIGNALING, sig = SignalingState.CONNECTING).headline.contains("servidor"))
    }
}
