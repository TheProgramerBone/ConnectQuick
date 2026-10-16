package dev.connectquick.controller.power

import dev.connectquick.controller.session.Pending
import dev.connectquick.controller.session.SessionPort
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class PowerAction(val wire: String, val destructive: Boolean) {
    LOCK("lock", false), SLEEP("sleep", true), RESTART("restart", true), SHUTDOWN("shutdown", true), CANCEL("cancel", false),
}

/** A destructive action the PC will only run once the user confirms ON THE PHONE and returns [token]. */
data class PowerChallenge(val requestId: String, val action: PowerAction, val token: String, val expiresInMs: Int, val delaySeconds: Int)

data class PowerResult(val ok: Boolean, val action: PowerAction, val reason: String?, val delaySeconds: Int)

sealed class PowerRequestOutcome {
    /** Lock / cancel: the PC acted (or refused) immediately. */
    data class Done(val result: PowerResult) : PowerRequestOutcome()
    /** Sleep / restart / shutdown: show a confirmation dialog, then call [PowerClient.confirm]. */
    data class NeedsConfirmation(val challenge: PowerChallenge) : PowerRequestOutcome()
    data class Failed(val reason: String) : PowerRequestOutcome()
}

/**
 * Remote power control (Phase 9). The protocol has no "do it now" for destructive actions: the PC
 * answers a request with a one-time token, and nothing happens until [confirm] returns it. The UI
 * MUST put a clear confirmation dialog between [request] and [confirm], and must ignore double
 * taps while a request is in flight ([inFlight]).
 */
class PowerClient(private val port: SessionPort) {
    private sealed class Reply {
        data class Challenge(val c: PowerChallenge) : Reply()
        data class Result(val r: PowerResult) : Reply()
    }

    private val pending = Pending<Reply>()
    @Volatile var inFlight: Boolean = false
        private set

    init {
        port.on("power.challenge") { m ->
            val action = actionOf(m)
            pending.complete(
                m.getValue("id").jsonPrimitive.content,
                Reply.Challenge(PowerChallenge(
                    m.getValue("id").jsonPrimitive.content, action, m.getValue("token").jsonPrimitive.content,
                    m.getValue("expiresInMs").jsonPrimitive.intOrNull ?: 0, m.getValue("delaySeconds").jsonPrimitive.intOrNull ?: 0,
                )),
            )
        }
        port.on("ctl.error") { m -> m["id"]?.jsonPrimitive?.content?.let { refuse(it, m.getValue("code").jsonPrimitive.content) } }
        port.on("power.result") { m ->
            pending.complete(
                m.getValue("id").jsonPrimitive.content,
                Reply.Result(PowerResult(
                    m.getValue("ok").jsonPrimitive.booleanOrNull == true, actionOf(m),
                    m["reason"]?.jsonPrimitive?.content, m["delaySeconds"]?.jsonPrimitive?.intOrNull ?: 0,
                )),
            )
        }
    }

    /** The PC refused with a ctl.error (forbidden, disabled_by_host...): fail the waiting request now, with the reason. */
    private fun refuse(id: String, code: String) {
        pending.complete(id, Reply.Result(PowerResult(false, PowerAction.CANCEL, code, 0)))
    }

    private fun actionOf(m: JsonObject): PowerAction =
        PowerAction.values().first { it.wire == m.getValue("action").jsonPrimitive.content }

    suspend fun request(action: PowerAction, timeoutMs: Long = 8_000): PowerRequestOutcome {
        if (inFlight) return PowerRequestOutcome.Failed("busy")
        inFlight = true
        try {
            val id = pending.newId()
            val waiting = pending.register(id)
            if (!port.send("power.request") { put("id", id); put("action", action.wire) }) return PowerRequestOutcome.Failed("session_closed")
            return when (val r = pending.await(id, waiting, timeoutMs)) {
                is Reply.Challenge -> PowerRequestOutcome.NeedsConfirmation(r.c)
                is Reply.Result -> PowerRequestOutcome.Done(r.r)
            }
        } catch (e: TimeoutCancellationException) {
            return PowerRequestOutcome.Failed("timeout")
        } finally {
            inFlight = false
        }
    }

    /** Sends the one-time token back. Only call after the user explicitly confirmed on screen. */
    suspend fun confirm(challenge: PowerChallenge, timeoutMs: Long = 8_000): PowerResult {
        val waiting = pending.register(challenge.requestId)
        if (!port.send("power.confirm") { put("id", challenge.requestId); put("token", challenge.token) }) {
            return PowerResult(false, challenge.action, "session_closed", 0)
        }
        return try {
            when (val r = pending.await(challenge.requestId, waiting, timeoutMs)) {
                is Reply.Result -> r.r
                is Reply.Challenge -> PowerResult(false, challenge.action, "unexpected_challenge", 0)
            }
        } catch (e: TimeoutCancellationException) {
            PowerResult(false, challenge.action, "timeout", 0)
        }
    }
}
