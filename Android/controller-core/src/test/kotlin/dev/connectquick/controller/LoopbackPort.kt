package dev.connectquick.controller

import dev.connectquick.controller.net.Subscription
import dev.connectquick.controller.protocol.InnerMessages
import dev.connectquick.controller.session.SessionPort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Two ends of a perfect in-memory session. Everything one end sends is serialised, validated
 * against the SHARED schema (so a client can never pass a test while emitting an invalid message)
 * and delivered to the other end on its own single thread, in order — like the real session.
 */
class LoopbackPort(override val permissions: Set<String> = setOf("mouse", "keyboard", "clipboard", "files", "media", "power")) : SessionPort {
    lateinit var peer: LoopbackPort
    @Volatile override var isOpen: Boolean = true
    val sent = CopyOnWriteArrayList<JsonObject>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(JsonObject) -> Unit>>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }

    override fun send(type: String, fields: JsonObjectBuilder.() -> Unit): Boolean {
        if (!isOpen) return false
        val bytes = InnerMessages.serialize(type, fields)
        val parsed = InnerMessages.parse(bytes)
        if (!parsed.ok) throw AssertionError("client emitted an invalid $type: ${parsed.rejection} ${String(bytes)}")
        val message = parsed.message!!
        sent += message
        peer.executor.execute { peer.dispatch(message) }
        return true
    }

    private fun dispatch(m: JsonObject) {
        listeners[m.getValue("t").jsonPrimitive.content]?.forEach { runCatching { it(m) } }
    }

    override fun on(type: String, handler: (JsonObject) -> Unit): Subscription {
        listeners.getOrPut(type) { CopyOnWriteArrayList() }.add(handler)
        return Subscription { listeners[type]?.remove(handler) }
    }

    fun sentOfType(t: String) = sent.filter { it.getValue("t").jsonPrimitive.content == t }

    companion object {
        fun pair(perms: Set<String> = setOf("mouse", "keyboard", "clipboard", "files", "media", "power")): Pair<LoopbackPort, LoopbackPort> {
            val a = LoopbackPort(perms)
            val b = LoopbackPort(perms)
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}
