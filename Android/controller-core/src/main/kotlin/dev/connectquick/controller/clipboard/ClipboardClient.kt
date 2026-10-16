package dev.connectquick.controller.clipboard

import dev.connectquick.controller.crypto.CryptoUtil
import dev.connectquick.controller.session.Pending
import dev.connectquick.controller.session.SessionPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed class ClipboardOutcome {
    object Sent : ClipboardOutcome()
    object Duplicate : ClipboardOutcome()
    data class Failed(val reason: String) : ClipboardOutcome()
}

sealed class PullOutcome {
    data class Text(val text: String) : PullOutcome()
    data class Failed(val reason: String) : PullOutcome()
}

/**
 * Plain-text clipboard sync (Phase 6), always explicit from the phone's side: the user taps
 * "send" or "get" (or enables auto-sync, which the PC must also have enabled). Loop prevention: the
 * hash of whatever was just applied or sent is remembered, so [shouldSend] returns false for the
 * echo of a remote write that a clipboard listener would otherwise bounce straight back.
 */
class ClipboardClient(private val port: SessionPort) {
    /** Text the PC pushed (auto-sync). The UI writes it to the system clipboard and calls [noteApplied]. */
    @Volatile var onRemoteText: ((String) -> Unit)? = null

    private val acks = Pending<Pair<Boolean, String?>>()
    private val pulls = Pending<PullOutcome>()
    @Volatile private var lastAppliedHash: String? = null
    @Volatile private var lastSentHash: String? = null

    init {
        port.on("clipboard.ack") { m ->
            val id = m.getValue("id").jsonPrimitive.content
            val ok = m.getValue("ok").jsonPrimitive.booleanOrNull == true
            val reason = m["reason"]?.jsonPrimitive?.content
            if (!acks.complete(id, ok to reason)) pulls.complete(id, PullOutcome.Failed(reason ?: "refused"))
        }
        port.on("clipboard.push") { m -> onPush(m) }
        port.on("ctl.error") { m ->
            val id = m["id"]?.jsonPrimitive?.content ?: return@on
            val code = m.getValue("code").jsonPrimitive.content
            if (!acks.complete(id, false to code)) pulls.complete(id, PullOutcome.Failed(code))
        }
    }

    private fun onPush(m: JsonObject) {
        val id = m.getValue("id").jsonPrimitive.content
        val text = m.getValue("text").jsonPrimitive.content
        val claimed = m.getValue("sha256").jsonPrimitive.content
        val bytes = text.toByteArray(Charsets.UTF_8)
        // The PC is trusted less than it looks: verify size and integrity like the PC verifies ours.
        if (bytes.size > MAX_TEXT_BYTES || hashOf(text) != claimed) {
            pulls.complete(id, PullOutcome.Failed("invalid_remote_text"))
            return
        }
        lastAppliedHash = claimed
        if (!pulls.complete(id, PullOutcome.Text(text))) onRemoteText?.invoke(text)
    }

    /** Call after writing text that came from the PC to the system clipboard (prevents echo). */
    fun noteApplied(text: String) {
        lastAppliedHash = hashOf(text)
    }

    /** False when [text] is what we just received or sent: pushing it again would be a loop or a no-op. */
    fun shouldSend(text: String): Boolean {
        val h = hashOf(text)
        return h != lastAppliedHash && h != lastSentHash
    }

    suspend fun push(text: String, timeoutMs: Long = 8_000): ClipboardOutcome {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return ClipboardOutcome.Failed("empty")
        if (bytes.size > MAX_TEXT_BYTES) return ClipboardOutcome.Failed("too_large")
        val hash = hashOf(text)
        if (hash == lastSentHash) return ClipboardOutcome.Duplicate
        val id = acks.newId()
        val waiting = acks.register(id)
        val sent = port.send("clipboard.push") { put("id", id); put("text", text); put("sha256", hash) }
        if (!sent) return ClipboardOutcome.Failed("session_closed")
        return try {
            val (ok, reason) = acks.await(id, waiting, timeoutMs)
            if (ok) { lastSentHash = hash; ClipboardOutcome.Sent } else ClipboardOutcome.Failed(reason ?: "refused")
        } catch (e: TimeoutCancellationException) {
            ClipboardOutcome.Failed("timeout")
        }
    }

    suspend fun pull(timeoutMs: Long = 8_000): PullOutcome {
        val id = pulls.newId()
        val waiting = pulls.register(id)
        if (!port.send("clipboard.request") { put("id", id) }) return PullOutcome.Failed("session_closed")
        return try {
            pulls.await(id, waiting, timeoutMs)
        } catch (e: TimeoutCancellationException) {
            PullOutcome.Failed("timeout")
        }
    }

    companion object {
        const val MAX_TEXT_BYTES = 16 * 1024
        fun hashOf(text: String): String = CryptoUtil.sha256(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
