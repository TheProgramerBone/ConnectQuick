package dev.connectquick.controller

import dev.connectquick.controller.clipboard.ClipboardClient
import dev.connectquick.controller.clipboard.ClipboardOutcome
import dev.connectquick.controller.clipboard.PullOutcome
import dev.connectquick.controller.input.InputClient
import dev.connectquick.controller.input.MouseButton
import dev.connectquick.controller.media.MediaAction
import dev.connectquick.controller.media.MediaClient
import dev.connectquick.controller.power.PowerAction
import dev.connectquick.controller.power.PowerClient
import dev.connectquick.controller.power.PowerRequestOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputClientTest {
    @Test
    fun typed_messages_are_valid_and_clamped() {
        val (phone, _) = LoopbackPort.pair()
        val input = InputClient(phone)
        input.mouseMove(99999, -99999)
        input.mouseAbsolute(70000, -5)
        input.wheel(5000, -5000)
        input.click(MouseButton.RIGHT)
        input.tapKey("KeyA")
        input.releaseAll()
        val moves = phone.sentOfType("input.mouse_move").single()
        assertEquals("4096", moves.getValue("dx").jsonPrimitive.content)
        assertEquals("-4096", moves.getValue("dy").jsonPrimitive.content)
        assertEquals("65535", phone.sentOfType("input.mouse_abs").single().getValue("x").jsonPrimitive.content)
        assertEquals("1200", phone.sentOfType("input.mouse_wheel").single().getValue("dx").jsonPrimitive.content)
        assertEquals(2, phone.sentOfType("input.mouse_button").size)
        assertEquals(2, phone.sentOfType("input.key").size)
        assertEquals(1, phone.sentOfType("input.release_all").size)
    }

    @Test
    fun zero_motion_sends_nothing() {
        val (phone, _) = LoopbackPort.pair()
        InputClient(phone).apply { mouseMove(0, 0); wheel(0, 0) }
        assertTrue(phone.sent.isEmpty())
    }

    @Test
    fun long_unicode_text_is_split_without_breaking_surrogate_pairs() {
        val (phone, _) = LoopbackPort.pair()
        val text = "🙂".repeat(300) // 600 UTF-16 units
        InputClient(phone).typeText(text)
        val parts = phone.sentOfType("input.text").map { it.getValue("text").jsonPrimitive.content }
        assertTrue(parts.size >= 3)
        assertEquals(text, parts.joinToString(""))
        parts.forEach { p -> assertTrue(p.length <= 256); assertFalse(Character.isLowSurrogate(p.first())); assertFalse(Character.isHighSurrogate(p.last())) }
    }
}

class ClipboardClientTest {
    @Test
    fun push_waits_for_the_pc_ack_and_rejects_oversized_or_empty_text_locally() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        pc.on("clipboard.push") { m -> pc.send("clipboard.ack") { put("id", m.getValue("id").jsonPrimitive.content); put("ok", true) } }
        val cb = ClipboardClient(phone)
        assertEquals(ClipboardOutcome.Sent, cb.push("hola"))
        assertEquals(ClipboardOutcome.Duplicate, cb.push("hola"))        // same content twice: no second message
        assertEquals(1, phone.sentOfType("clipboard.push").size)
        assertEquals(ClipboardOutcome.Failed("too_large"), cb.push("€".repeat(6000)))
        assertEquals(ClipboardOutcome.Failed("empty"), cb.push(""))
    }

    @Test
    fun a_refusal_or_silence_from_the_pc_is_reported() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        pc.on("clipboard.push") { m -> pc.send("clipboard.ack") { put("id", m.getValue("id").jsonPrimitive.content); put("ok", false); put("reason", "write_failed") } }
        assertEquals(ClipboardOutcome.Failed("write_failed"), ClipboardClient(phone).push("x"))
        val (silentPhone, _) = LoopbackPort.pair()
        assertEquals(ClipboardOutcome.Failed("timeout"), ClipboardClient(silentPhone).push("y", timeoutMs = 200))
    }

    @Test
    fun pull_returns_the_pc_text_and_verifies_its_hash() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        pc.on("clipboard.request") { m ->
            val id = m.getValue("id").jsonPrimitive.content
            pc.send("clipboard.push") { put("id", id); put("text", "del PC"); put("sha256", ClipboardClient.hashOf("del PC")) }
        }
        assertEquals(PullOutcome.Text("del PC"), ClipboardClient(phone).pull())

        val (phone2, pc2) = LoopbackPort.pair()
        pc2.on("clipboard.request") { m ->
            pc2.send("clipboard.push") { put("id", m.getValue("id").jsonPrimitive.content); put("text", "tampered"); put("sha256", "a".repeat(64)) }
        }
        assertEquals(PullOutcome.Failed("invalid_remote_text"), ClipboardClient(phone2).pull())
    }

    @Test
    fun an_empty_pc_clipboard_is_reported_as_such() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        pc.on("clipboard.request") { m -> pc.send("clipboard.ack") { put("id", m.getValue("id").jsonPrimitive.content); put("ok", false); put("reason", "empty_or_not_text") } }
        assertEquals(PullOutcome.Failed("empty_or_not_text"), ClipboardClient(phone).pull())
    }

    @Test
    fun auto_pushed_text_reaches_the_listener_and_is_never_echoed_back() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        val cb = ClipboardClient(phone)
        val got = java.util.concurrent.LinkedBlockingQueue<String>()
        cb.onRemoteText = { got.add(it) }
        pc.send("clipboard.push") { put("id", "h1"); put("text", "auto"); put("sha256", ClipboardClient.hashOf("auto")) }
        assertEquals("auto", got.poll(2, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse("the system clipboard listener must not bounce it back", cb.shouldSend("auto"))
        assertTrue(cb.shouldSend("something else"))
    }

    @Test
    fun clipboard_clients_never_send_acks_the_pc_would_treat_as_violations() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        ClipboardClient(phone).onRemoteText = {}
        pc.send("clipboard.push") { put("id", "h1"); put("text", "a"); put("sha256", ClipboardClient.hashOf("a")) }
        kotlinx.coroutines.delay(200)
        assertTrue(phone.sentOfType("clipboard.ack").isEmpty())
    }
}

class MediaClientTest {
    @Test
    fun result_is_reported_and_confirmed_is_false() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        pc.on("media.control") { m ->
            pc.send("media.result") { put("id", m.getValue("id").jsonPrimitive.content); put("ok", true); put("confirmed", false) }
        }
        val out = MediaClient(phone).control(MediaAction.PLAY_PAUSE)
        assertTrue(out.delivered)
        assertFalse(out.confirmed)
    }

    @Test
    fun volume_steps_are_sent_only_for_volume_keys_and_capped() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        pc.on("media.control") { m -> pc.send("media.result") { put("id", m.getValue("id").jsonPrimitive.content); put("ok", true); put("confirmed", false) } }
        val media = MediaClient(phone)
        media.control(MediaAction.VOLUME_UP, steps = 50)
        media.control(MediaAction.NEXT, steps = 5)
        val sent = phone.sentOfType("media.control")
        assertEquals("10", sent[0].getValue("steps").jsonPrimitive.content)
        assertNull(sent[1]["steps"])
    }

    @Test
    fun failure_and_timeout_are_reported() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        pc.on("media.control") { m -> pc.send("media.result") { put("id", m.getValue("id").jsonPrimitive.content); put("ok", false); put("confirmed", false); put("reason", "os_refused") } }
        assertEquals("os_refused", MediaClient(phone).control(MediaAction.NEXT).reason)
        val (silent, _) = LoopbackPort.pair()
        assertEquals("timeout", MediaClient(silent).control(MediaAction.NEXT, timeoutMs = 150).reason)
    }
}

class PowerClientTest {
    @Test
    fun lock_completes_in_one_step() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        pc.on("power.request") { m -> pc.send("power.result") { put("id", m.getValue("id").jsonPrimitive.content); put("ok", true); put("action", "lock") } }
        val out = PowerClient(phone).request(PowerAction.LOCK)
        assertTrue(out is PowerRequestOutcome.Done && out.result.ok)
        assertTrue(phone.sentOfType("power.confirm").isEmpty())
    }

    @Test
    fun a_destructive_action_needs_the_token_to_be_returned_by_an_explicit_confirm() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        pc.on("power.request") { m ->
            pc.send("power.challenge") {
                put("id", m.getValue("id").jsonPrimitive.content); put("action", m.getValue("action").jsonPrimitive.content)
                put("token", "A".repeat(22)); put("expiresInMs", 20000); put("delaySeconds", 15)
            }
        }
        pc.on("power.confirm") { m ->
            pc.send("power.result") { put("id", m.getValue("id").jsonPrimitive.content); put("ok", true); put("action", "shutdown"); put("delaySeconds", 15) }
        }
        val power = PowerClient(phone)
        val out = power.request(PowerAction.SHUTDOWN)
        assertTrue(out is PowerRequestOutcome.NeedsConfirmation)
        assertTrue("nothing may be confirmed automatically", phone.sentOfType("power.confirm").isEmpty())
        val result = power.confirm((out as PowerRequestOutcome.NeedsConfirmation).challenge)
        assertTrue(result.ok)
        assertEquals(15, result.delaySeconds)
        assertEquals("A".repeat(22), phone.sentOfType("power.confirm").single().getValue("token").jsonPrimitive.content)
    }

    @Test
    fun a_second_request_while_one_is_in_flight_is_ignored() = runBlocking {
        val (phone, _) = LoopbackPort.pair() // the PC never answers
        val power = PowerClient(phone)
        val first = async { power.request(PowerAction.LOCK, timeoutMs = 400) }
        kotlinx.coroutines.delay(100)
        assertEquals(PowerRequestOutcome.Failed("busy"), power.request(PowerAction.SHUTDOWN))
        assertEquals(PowerRequestOutcome.Failed("timeout"), first.await())
        assertEquals(1, phone.sentOfType("power.request").size)
    }

    @Test
    fun refusal_by_policy_is_surfaced() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        pc.on("power.request") { m -> pc.send("power.result") { put("id", m.getValue("id").jsonPrimitive.content); put("ok", false); put("action", "shutdown"); put("reason", "policy_lock_only") } }
        val out = PowerClient(phone).request(PowerAction.SHUTDOWN)
        assertTrue(out is PowerRequestOutcome.Done && !out.result.ok && out.result.reason == "policy_lock_only")
    }
}

class FastFailTest {
    private fun refuse(pc: LoopbackPort, type: String, code: String, idField: String = "id") =
        pc.on(type) { m -> pc.send("ctl.error") { put("id", m.getValue(idField).jsonPrimitive.content); put("code", code); put("message", "no") } }

    @Test
    fun a_refusal_from_the_pc_fails_the_request_at_once_not_after_the_timeout() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        refuse(pc, "power.request", "disabled_by_host")
        val started = System.nanoTime()
        val out = PowerClient(phone).request(PowerAction.LOCK, timeoutMs = 8_000)
        assertTrue(out is PowerRequestOutcome.Done && !out.result.ok && out.result.reason == "disabled_by_host")
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) < 2_000_000_000L)

        val (p2, pc2) = LoopbackPort.pair()
        refuse(pc2, "media.control", "forbidden")
        assertEquals("forbidden", MediaClient(p2).control(MediaAction.NEXT, timeoutMs = 8_000).reason)

        val (p3, pc3) = LoopbackPort.pair()
        refuse(pc3, "clipboard.push", "forbidden")
        assertEquals(ClipboardOutcome.Failed("forbidden"), ClipboardClient(p3).push("x", timeoutMs = 8_000))

        val (p4, pc4) = LoopbackPort.pair()
        refuse(pc4, "clipboard.request", "disabled_by_host")
        assertEquals(PullOutcome.Failed("disabled_by_host"), ClipboardClient(p4).pull(timeoutMs = 8_000))
    }
}
