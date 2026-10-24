package dev.connectquick.controller

import dev.connectquick.controller.stream.IceConfig
import dev.connectquick.controller.stream.IceServer
import dev.connectquick.controller.stream.RtcCandidate
import dev.connectquick.controller.stream.RtcEngine
import dev.connectquick.controller.stream.RtcSession
import dev.connectquick.controller.stream.RtcState
import dev.connectquick.controller.stream.StreamController
import dev.connectquick.controller.stream.StreamRequest
import dev.connectquick.controller.stream.StreamStatus
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class StreamControllerTest {
    private class FakeSession(val ice: IceConfig) : RtcSession {
        override var onState: ((RtcState) -> Unit)? = null
        val remoteCandidates = CopyOnWriteArrayList<RtcCandidate>()
        @Volatile var closed = false
        @Volatile var offer: String? = null
        override fun answer(offerSdp: String, onCandidate: (RtcCandidate) -> Unit): String {
            offer = offerSdp
            onCandidate(RtcCandidate("candidate:1 1 udp 2130706431 192.168.1.9 50000 typ host", "0", 0))
            return "v=0\r\nanswer-for-$offerSdp"
        }
        override fun addRemoteCandidate(candidate: RtcCandidate) { remoteCandidates += candidate }
        override fun close() { closed = true }
    }

    private class FakeEngine : RtcEngine {
        val sessions = CopyOnWriteArrayList<FakeSession>()
        var failNext = false
        override fun createSession(ice: IceConfig): RtcSession {
            if (failNext) throw IllegalStateException("no webrtc")
            return FakeSession(ice).also { sessions += it }
        }
    }

    private val req = StreamRequest(1280, 720, 30, 2500)

    private fun rig(ice: IceConfig = IceConfig.NONE): Triple<LoopbackPort, LoopbackPort, Pair<StreamController, FakeEngine>> {
        val (phone, pc) = LoopbackPort.pair()
        val engine = FakeEngine()
        val sc = StreamController(phone, { ice }, engine)
        return Triple(phone, pc, sc to engine)
    }

    private fun waitFor(what: String, ms: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun the_full_signalling_flow_negotiates_exchanges_candidates_and_streams() {
        val (phone, pc, pair) = rig()
        val (sc, engine) = pair
        val statuses = CopyOnWriteArrayList<StreamStatus.Phase>()
        sc.onStatus = { statuses += it.phase }

        assertTrue(sc.start(req))
        waitFor("stream.start at the PC") { pc.sentOfType("stream.start").isEmpty() && phone.sentOfType("stream.start").size == 1 }
        val start = phone.sentOfType("stream.start").single()
        assertEquals("1280", start.getValue("maxWidth").jsonPrimitive.content)

        // The PC sends a candidate BEFORE its offer is answered: it must be queued, not lost.
        pc.send("rtc.candidate") { put("candidate", "candidate:7 1 udp 1 10.0.0.2 4000 typ host"); put("sdpMid", "0"); put("sdpMLineIndex", 0) }
        pc.send("rtc.offer") { put("sdp", "OFFER") }
        waitFor("answer") { phone.sentOfType("rtc.answer").size == 1 }
        assertEquals("v=0\r\nanswer-for-OFFER", phone.sentOfType("rtc.answer").single().getValue("sdp").jsonPrimitive.content)
        assertEquals("our local candidate went to the PC", 1, phone.sentOfType("rtc.candidate").size)
        waitFor("queued candidate applied") { engine.sessions.single().remoteCandidates.size == 1 }

        pc.send("rtc.candidate") { put("candidate", "candidate:8 1 udp 1 10.0.0.2 4001 typ host") } // arrives after: applied directly
        waitFor("second candidate") { engine.sessions.single().remoteCandidates.size == 2 }

        pc.send("stream.state") { put("id", start.getValue("id").jsonPrimitive.content); put("state", "streaming"); put("width", 1280); put("height", 720) }
        waitFor("streaming") { sc.status.phase == StreamStatus.Phase.STREAMING }
        assertEquals(1280, sc.status.width)

        pc.send("stream.stats") { put("fps", 24.5); put("bitrateKbps", 1800); put("width", 1280); put("height", 720) }
        waitFor("stats") { sc.status.stats?.fps == 24.5 }

        pc.send("stream.state") { put("id", "x"); put("state", "streaming"); put("reason", "secure_desktop") }
        waitFor("blocked is surfaced") { sc.status.blocked == "secure_desktop" }

        sc.stop()
        assertEquals(1, phone.sentOfType("stream.stop").size)
        assertTrue("the media connection is released on stop", engine.sessions.single().closed)
        assertEquals(StreamStatus.Phase.STOPPED, sc.status.phase)
        assertTrue(statuses.containsAll(listOf(StreamStatus.Phase.REQUESTING, StreamStatus.Phase.NEGOTIATING, StreamStatus.Phase.STREAMING)))
    }

    @Test
    fun a_failed_connection_explains_whether_a_relay_could_have_helped() {
        for ((hasTurn, expected) in listOf(false to "ice_failed_no_relay", true to "ice_failed")) {
            val ice = IceConfig(listOf(IceServer(listOf("stun:s"), null, null)), hasStun = true, hasTurn = hasTurn, ttlSeconds = 0)
            val (_, pc, pair) = rig(ice)
            val (sc, engine) = pair
            sc.start(req)
            pc.send("rtc.offer") { put("sdp", "O") }
            waitFor("session") { engine.sessions.size == 1 }
            engine.sessions.single().onState!!.invoke(RtcState.FAILED)
            waitFor("error status") { sc.status.phase == StreamStatus.Phase.ERROR }
            assertEquals(expected, sc.status.reason)
            assertTrue(engine.sessions.single().closed)
        }
    }

    @Test
    fun a_stream_that_worked_asks_the_pc_again_after_the_network_changes() {
        val (phone, pc) = LoopbackPort.pair()
        val engine = FakeEngine()
        val sc = StreamController(phone, { IceConfig.NONE }, engine, retryDelayMs = 50, disconnectGraceMs = 100)
        sc.start(req)
        pc.send("rtc.offer") { put("sdp", "O1") }
        waitFor("first session") { engine.sessions.size == 1 }
        val first = engine.sessions[0]
        first.onState!!.invoke(RtcState.CONNECTED)

        // Wi-Fi -> mobile data: stays DISCONNECTED past the grace period, so the phone re-requests.
        first.onState!!.invoke(RtcState.DISCONNECTED)
        waitFor("second stream.start") { phone.sentOfType("stream.start").size == 2 }
        assertTrue(first.closed)
        pc.send("rtc.offer") { put("sdp", "O2") }
        waitFor("second session") { engine.sessions.size == 2 }
        engine.sessions[1].onState!!.invoke(RtcState.CONNECTED)

        // A brief DISCONNECTED that heals by itself must not restart anything.
        engine.sessions[1].onState!!.invoke(RtcState.DISCONNECTED)
        engine.sessions[1].onState!!.invoke(RtcState.CONNECTED)
        Thread.sleep(300)
        assertEquals(2, phone.sentOfType("stream.start").size)

        // FAILED after having worked also recovers, but stopping cancels a pending retry.
        engine.sessions[1].onState!!.invoke(RtcState.FAILED)
        sc.stop()
        Thread.sleep(300)
        assertEquals(2, phone.sentOfType("stream.start").size)
        assertEquals(StreamStatus.Phase.STOPPED, sc.status.phase)
    }

    @Test
    fun a_refusal_by_the_pc_surfaces_immediately_with_its_reason() {
        val (phone, pc, pair) = rig()
        val (sc, engine) = pair
        sc.start(req)
        val id = phone.sentOfType("stream.start").single().getValue("id").jsonPrimitive.content
        pc.send("ctl.error") { put("id", id); put("code", "disabled_by_host"); put("message", "off") }
        waitFor("error") { sc.status.phase == StreamStatus.Phase.ERROR }
        assertEquals("disabled_by_host", sc.status.reason)
        assertTrue(engine.sessions.isEmpty())
    }

    @Test
    fun the_pc_reporting_an_error_or_ending_the_stream_tears_it_down() {
        val (_, pc, pair) = rig()
        val (sc, engine) = pair
        sc.start(req)
        pc.send("rtc.offer") { put("sdp", "O") }
        waitFor("session") { engine.sessions.size == 1 }
        pc.send("stream.state") { put("id", "x"); put("state", "error"); put("reason", "ice_timeout_no_relay") }
        waitFor("error") { sc.status.phase == StreamStatus.Phase.ERROR }
        assertEquals("ice_timeout_no_relay", sc.status.reason)
        assertTrue(engine.sessions.single().closed)

        sc.start(req)
        pc.send("rtc.offer") { put("sdp", "O2") }
        waitFor("second session") { engine.sessions.size == 2 }
        pc.send("rtc.end") { put("reason", "stopped_by_phone") }
        waitFor("stopped") { sc.status.phase == StreamStatus.Phase.STOPPED }
        assertTrue(engine.sessions[1].closed)
    }

    @Test
    fun an_engine_that_cannot_start_is_an_error_not_a_crash() {
        val (_, pc, pair) = rig()
        val (sc, engine) = pair
        engine.failNext = true
        sc.start(req)
        pc.send("rtc.offer") { put("sdp", "O") }
        waitFor("error") { sc.status.phase == StreamStatus.Phase.ERROR }
        assertEquals("negotiation_failed", sc.status.reason)
    }

    @Test
    fun starting_again_replaces_the_previous_media_connection() {
        val (_, pc, pair) = rig()
        val (sc, engine) = pair
        sc.start(req)
        pc.send("rtc.offer") { put("sdp", "A") }
        waitFor("first") { engine.sessions.size == 1 }
        sc.start(req)
        assertTrue("the old connection is closed before a new one is requested", engine.sessions[0].closed)
        pc.send("rtc.offer") { put("sdp", "B") }
        waitFor("second") { engine.sessions.size == 2 }
        assertFalse(engine.sessions[1].closed)
    }

    @Test
    fun an_unsolicited_offer_without_a_request_is_ignored() {
        val (phone, pc, pair) = rig()
        val (sc, engine) = pair
        pc.send("rtc.offer") { put("sdp", "O") }
        Thread.sleep(300)
        assertTrue("a PC cannot make the phone open a media connection nobody asked for", engine.sessions.isEmpty())
        assertTrue(phone.sentOfType("rtc.answer").isEmpty())
        assertEquals(StreamStatus.Phase.IDLE, sc.status.phase)
    }

    @Test
    fun releasing_closes_the_connection_and_stops_listening() {
        val (_, pc, pair) = rig()
        val (sc, engine) = pair
        sc.start(req)
        pc.send("rtc.offer") { put("sdp", "O") }
        waitFor("session") { engine.sessions.size == 1 }
        sc.release()
        assertTrue(engine.sessions.single().closed)
        pc.send("rtc.offer") { put("sdp", "again") }
        Thread.sleep(200)
        assertEquals("no new connection after release", 1, engine.sessions.size)
        assertNotNull(sc.status)
    }
}
