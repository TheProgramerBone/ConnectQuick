package dev.connectquick.controller.session

import dev.connectquick.controller.net.Subscription
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder

/**
 * What a feature client (input, clipboard, files, ...) needs from a session: send an inner
 * message, listen for inner messages, know the granted permissions. [ControllerSession]
 * implements it for real; tests implement it with an in-memory pipe.
 */
interface SessionPort {
    val permissions: Set<String>
    /** Queues one inner message for encryption and delivery. Returns false when the session is over. */
    fun send(type: String, fields: JsonObjectBuilder.() -> Unit = {}): Boolean
    /** Listens for decrypted, schema-validated messages of [type] (dispatched in arrival order, off the network thread). */
    fun on(type: String, handler: (JsonObject) -> Unit): Subscription
    val isOpen: Boolean
}
