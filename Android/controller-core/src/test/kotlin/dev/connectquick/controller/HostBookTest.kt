package dev.connectquick.controller

import dev.connectquick.controller.session.HostBook
import dev.connectquick.controller.session.PairedHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostBookTest {
    private fun host(id: String, relay: String = "wss://$id") = PairedHost(id, "PC $id", "pub-$id", relay, listOf("mouse"), 1)

    @Test
    fun pairing_a_second_pc_keeps_the_first_and_switches_to_the_new_one() {
        val book = HostBook().adopt(host("a")).adopt(host("b"))
        assertEquals(listOf("a", "b"), book.hosts.map { it.hostId })
        assertEquals("b", book.active?.hostId)
        assertEquals(listOf("a"), book.others.map { it.hostId })
        assertEquals("a", book.use("a").active?.hostId)
        assertEquals("a", book.use("nope").use("a").use("nope").active?.hostId, "an unknown id changes nothing")
    }

    @Test
    fun pairing_the_same_pc_again_refreshes_it_in_place() {
        val book = HostBook().adopt(host("a")).adopt(host("b")).adopt(host("a", relay = "wss://new"))
        assertEquals(listOf("a", "b"), book.hosts.map { it.hostId })
        assertEquals("wss://new", book.active?.relay)
    }

    @Test
    fun refresh_updates_a_pc_without_switching_to_it() {
        val book = HostBook().adopt(host("a")).adopt(host("b")).refresh(host("a").copy(relays = listOf("ws://192.168.1.5:8443")))
        assertEquals("b", book.active?.hostId)
        assertEquals(listOf("ws://192.168.1.5:8443"), book.hosts.first().relays)
        assertEquals(book, book.refresh(host("zzz")), "an unknown PC is ignored")
    }

    @Test
    fun forgetting_the_pc_in_use_falls_back_to_another_or_to_none() {
        val book = HostBook().adopt(host("a")).adopt(host("b"))
        assertEquals("a", book.forget("b").active?.hostId)
        assertEquals("b", book.forget("a").active?.hostId)
        assertNull(book.forget("a").forget("b").active)
    }

    @Test
    fun it_survives_storage_and_reads_the_old_single_pc_format() {
        val book = HostBook().adopt(host("a")).adopt(host("b")).use("a")
        assertEquals(book, HostBook.parse(book.encode()))

        val legacy = """{"hostId":"h","hostName":"PC","hostPub":"k","relay":"wss://cloud","permissions":["mouse"],"pairedAtMs":5}"""
        val migrated = HostBook.parse(legacy)
        assertEquals("h", migrated.active?.hostId)
        assertEquals(1, migrated.hosts.size)

        assertNull(HostBook.parse(null).active)
        assertNull(HostBook.parse("garbage{").active)
    }
}
