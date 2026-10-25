package dev.connectquick.controller

import dev.connectquick.controller.pairing.QrPairingPayload
import dev.connectquick.controller.session.PairedHost
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayListTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val qrBase = """"v":1,"pairingId":"p","relay":"wss://cloud","hostId":"h","hostName":"PC","hostPub":"k","hostEphPub":"e","nonce":"n","exp":1"""

    @Test
    fun a_qr_without_a_relay_list_still_parses() {
        val qr = json.decodeFromString(QrPairingPayload.serializer(), "{$qrBase}")
        assertEquals(emptyList<String>(), qr.relays)
    }

    @Test
    fun a_qr_with_a_relay_list_keeps_its_order() {
        val qr = json.decodeFromString(QrPairingPayload.serializer(), "{$qrBase,\"relays\":[\"ws://192.168.1.5:8443\",\"wss://cloud\"]}")
        assertEquals(listOf("ws://192.168.1.5:8443", "wss://cloud"), qr.relays)
    }

    @Test
    fun a_host_saved_before_relay_lists_existed_still_loads() {
        val old = """{"hostId":"h","hostName":"PC","hostPub":"k","relay":"wss://cloud","permissions":["mouse"],"pairedAtMs":5}"""
        val host = json.decodeFromString(PairedHost.serializer(), old)
        assertEquals(emptyList<String>(), host.relays)
        assertEquals("wss://cloud", host.relay)
    }
}
