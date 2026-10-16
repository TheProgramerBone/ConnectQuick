package dev.connectquick.controller.media

import dev.connectquick.controller.session.Pending
import dev.connectquick.controller.session.SessionPort
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class MediaAction(val wire: String) {
    PLAY_PAUSE("play_pause"), NEXT("next"), PREVIOUS("prev"), STOP("stop"),
    VOLUME_UP("vol_up"), VOLUME_DOWN("vol_down"), MUTE("mute"),
}

/**
 * Outcome of a media key. [delivered] means the PC injected the key; [confirmed] is whether
 * Windows could confirm a player acted on it, which it cannot, so it is always false in practice
 * and the UI must say "sent", never "playing".
 */
data class MediaOutcome(val delivered: Boolean, val confirmed: Boolean, val reason: String?)

class MediaClient(private val port: SessionPort) {
    private val pending = Pending<MediaOutcome>()

    init {
        port.on("media.result") { m ->
            pending.complete(
                m.getValue("id").jsonPrimitive.content,
                MediaOutcome(
                    m.getValue("ok").jsonPrimitive.booleanOrNull == true,
                    m.getValue("confirmed").jsonPrimitive.booleanOrNull == true,
                    m["reason"]?.jsonPrimitive?.content,
                ),
            )
        }
    }

    init {
        port.on("ctl.error") { m ->
            val id = m["id"]?.jsonPrimitive?.content ?: return@on
            pending.complete(id, MediaOutcome(false, false, m.getValue("code").jsonPrimitive.content))
        }
    }

    suspend fun control(action: MediaAction, steps: Int = 1, timeoutMs: Long = 5_000): MediaOutcome {
        val id = pending.newId()
        val waiting = pending.register(id)
        val sent = port.send("media.control") {
            put("id", id)
            put("action", action.wire)
            if (steps > 1 && (action == MediaAction.VOLUME_UP || action == MediaAction.VOLUME_DOWN)) put("steps", steps.coerceIn(1, 10))
        }
        if (!sent) return MediaOutcome(false, false, "session_closed")
        return try {
            pending.await(id, waiting, timeoutMs)
        } catch (e: TimeoutCancellationException) {
            MediaOutcome(false, false, "timeout")
        }
    }
}
