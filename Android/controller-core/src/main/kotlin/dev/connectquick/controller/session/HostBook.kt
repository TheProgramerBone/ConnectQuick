package dev.connectquick.controller.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every PC this phone is paired with, and which one is in use. Pure data so it is unit-tested on the JVM;
 * the app persists it as one JSON string. A phone paired before several PCs were supported stored a single
 * [PairedHost]; [parse] reads that too, so an update never makes the user pair again.
 */
@Serializable
data class HostBook(val hosts: List<PairedHost> = emptyList(), val activeId: String? = null) {
    val active: PairedHost? get() = hosts.firstOrNull { it.hostId == activeId } ?: hosts.firstOrNull()
    val others: List<PairedHost> get() = hosts.filter { it.hostId != active?.hostId }

    /** Adds the PC (or refreshes it if already known, keeping its position) and makes it the one in use. */
    fun adopt(host: PairedHost): HostBook {
        val exists = hosts.any { it.hostId == host.hostId }
        val next = if (exists) hosts.map { if (it.hostId == host.hostId) host else it } else hosts + host
        return HostBook(next, host.hostId)
    }

    /** Updates a known PC without changing which one is in use (e.g. relays learned in a session). */
    fun refresh(host: PairedHost): HostBook =
        if (hosts.none { it.hostId == host.hostId }) this else copy(hosts = hosts.map { if (it.hostId == host.hostId) host else it })

    fun use(hostId: String): HostBook = if (hosts.any { it.hostId == hostId }) copy(activeId = hostId) else this

    /** Forgets one PC; if it was the one in use, the first remaining takes its place. */
    fun forget(hostId: String): HostBook {
        val left = hosts.filter { it.hostId != hostId }
        return HostBook(left, if (activeId == hostId) left.firstOrNull()?.hostId else activeId)
    }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Reads a stored book, or a legacy single [PairedHost]; anything unreadable is an empty book. */
        fun parse(stored: String?): HostBook {
            if (stored.isNullOrBlank()) return HostBook()
            runCatching { JSON.decodeFromString(serializer(), stored) }.getOrNull()?.let { if (it.hosts.isNotEmpty()) return it }
            val legacy = runCatching { JSON.decodeFromString(PairedHost.serializer(), stored) }.getOrNull() ?: return HostBook()
            return HostBook(listOf(legacy), legacy.hostId)
        }
    }
}
