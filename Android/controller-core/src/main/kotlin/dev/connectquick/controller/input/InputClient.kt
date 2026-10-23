package dev.connectquick.controller.input

import dev.connectquick.controller.session.SessionPort
import kotlinx.serialization.json.put

enum class MouseButton(val wire: String) { LEFT("left"), RIGHT("right"), MIDDLE("middle"), X1("x1"), X2("x2") }

/** Where the gesture engine sends its decisions. [InputClient] is the production sink. */
interface InputSink {
    fun mouseMove(dx: Int, dy: Int)
    fun mouseButton(button: MouseButton, down: Boolean)
    /** Wheel deltas in 1/120 notch units (positive: up / right), as `input.mouse_wheel` defines them. */
    fun wheel(dx: Int, dy: Int)
    /** Key down/up (W3C code). Only gestures that map to a key (pinch = Ctrl+wheel) use it; default: ignored. */
    fun key(code: String, down: Boolean) {}
}

/**
 * Typed `input.*` messages for one session. Coordinates policy (identical on both platforms):
 * relative motion is in device-independent pixels of the PC's primary monitor, AFTER the phone
 * applied sensitivity; absolute positions are normalised 0..65535 across that monitor.
 * Out-of-range values are clamped here so the PC's validator never has to reject honest input.
 */
class InputClient(private val port: SessionPort) : InputSink {
    override fun mouseMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        port.send("input.mouse_move") { put("dx", dx.coerceIn(-MAX_MOVE, MAX_MOVE)); put("dy", dy.coerceIn(-MAX_MOVE, MAX_MOVE)) }
    }

    fun mouseAbsolute(x: Int, y: Int) {
        port.send("input.mouse_abs") { put("x", x.coerceIn(0, 65535)); put("y", y.coerceIn(0, 65535)) }
    }

    override fun mouseButton(button: MouseButton, down: Boolean) {
        port.send("input.mouse_button") { put("button", button.wire); put("down", down) }
    }

    override fun wheel(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        port.send("input.mouse_wheel") { put("dx", dx.coerceIn(-MAX_WHEEL, MAX_WHEEL)); put("dy", dy.coerceIn(-MAX_WHEEL, MAX_WHEEL)) }
    }

    fun click(button: MouseButton) {
        mouseButton(button, true)
        mouseButton(button, false)
    }

    override fun key(code: String, down: Boolean) {
        port.send("input.key") { put("code", code); put("down", down) }
    }

    fun tapKey(code: String) {
        key(code, true)
        key(code, false)
    }

    /** Presses [keys] in order and releases them in reverse, like a person holding a shortcut. */
    fun combo(keys: List<String>) {
        keys.forEach { key(it, true) }
        keys.asReversed().forEach { key(it, false) }
    }

    /** Types text as-is (Unicode). Long text is split to respect the 256-character message limit. */
    fun typeText(text: String) {
        var i = 0
        while (i < text.length) {
            var end = minOf(text.length, i + MAX_TEXT)
            // never split a surrogate pair across two messages
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            port.send("input.text") { put("text", text.substring(i, end)) }
            i = end
        }
    }

    fun releaseAll() {
        port.send("input.release_all")
    }

    companion object {
        const val MAX_MOVE = 4096
        const val MAX_WHEEL = 1200
        const val MAX_TEXT = 256
    }
}
