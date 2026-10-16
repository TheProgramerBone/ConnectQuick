package dev.connectquick.controller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.connectquick.controller.connectQuick
import dev.connectquick.controller.input.InputClient
import dev.connectquick.controller.input.MouseButton
import dev.connectquick.controller.input.TouchAction
import dev.connectquick.controller.input.TouchEvent
import dev.connectquick.controller.input.TouchSample
import dev.connectquick.controller.input.TouchpadEngine
import dev.connectquick.controller.session.ControllerHub

/**
 * The touch surface. It only translates Android touches into the platform-neutral events the (unit
 * tested) TouchpadEngine understands; every decision about taps, drags and scrolling lives there.
 */
class TouchSurface(context: Context) : View(context) {
    var engine: TouchpadEngine? = null
    var enabledForInput = false
        set(value) { field = value; invalidate() }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.DIM; textAlign = Paint.Align.CENTER }

    init {
        background = Ui.glass(context, 22, 26)
        isFocusable = true
        contentDescription = "Superficie táctil. Un dedo mueve el puntero, un toque hace clic, dos dedos desplazan."
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hintPaint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        val lines = if (enabledForInput) listOf("Un dedo: mover", "Toque: clic · dos dedos: clic derecho", "Dos dedos deslizando: desplazar", "Toca y arrastra: seleccionar")
        else listOf("Sin conexión con el PC", "El touchpad se activa al conectar")
        val lh = hintPaint.fontSpacing
        var y = height / 2f - lh * (lines.size - 1) / 2f
        lines.forEach { canvas.drawText(it, width / 2f, y, hintPaint); y += lh }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val eng = engine
        if (!enabledForInput || eng == null) return true
        parent?.requestDisallowInterceptTouchEvent(true)
        fun all(skip: Int = -1): List<TouchSample> =
            (0 until e.pointerCount).filter { it != skip }.map { TouchSample(e.getPointerId(it), e.getX(it), e.getY(it)) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> eng.onTouch(TouchEvent(TouchAction.DOWN, e.eventTime, all()))
            MotionEvent.ACTION_MOVE -> eng.onTouch(TouchEvent(TouchAction.MOVE, e.eventTime, all()))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> eng.onTouch(TouchEvent(TouchAction.UP, e.eventTime, all(skip = e.actionIndex)))
            MotionEvent.ACTION_CANCEL -> eng.onTouch(TouchEvent(TouchAction.CANCEL, e.eventTime, emptyList()))
        }
        return true
    }
}

/** Touchpad + keyboard: pointer, click buttons, typing, and the keys a touch keyboard does not have. */
class TouchpadActivity : HubActivity() {
    private var banner: Banner? = null
    private var surface: TouchSurface? = null
    private var engine: TouchpadEngine? = null
    private var engineFor: InputClient? = null
    private val sticky = linkedMapOf("ControlLeft" to "Ctrl", "AltLeft" to "Alt", "ShiftLeft" to "Shift", "MetaLeft" to "Win")
    private val heldModifiers = mutableSetOf<String>()
    private val modifierButtons = mutableMapOf<String, android.widget.Button>()
    private var typing: EditText? = null
    private var keyRow: LinearLayout? = null
    private var modRow: LinearLayout? = null
    private var clickRow: LinearLayout? = null
    private var buildOpenKeyboard = false

    override fun onCreated(savedInstanceState: Bundle?) {
        buildOpenKeyboard = intent.getBooleanExtra(EXTRA_OPEN_KEYBOARD, false)
    }

    override fun buildContent(hub: ControllerHub?) {
        val ctx = this
        content.addView(backRow("Touchpad y teclado"), Ui.lp(ctx, bottom = 12))
        banner = Banner(ctx) { a -> if (a != dev.connectquick.controller.session.LinkAction.NONE) hub?.retryNow() }.also { content.addView(it, Ui.lp(ctx, bottom = 12)) }

        val s = TouchSurface(ctx)
        surface = s
        content.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 280)).apply { bottomMargin = Ui.dp(ctx, 12) })

        val clicks = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        clickRow = clicks
        fun mouseButton(label: String, button: MouseButton) = Ui.button(ctx, label) { }.apply {
            // Hold-to-press so drag and long-press work: down on touch, up on release.
            setOnTouchListener { v, ev ->
                val input = hub?.input
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { input?.mouseButton(button, true); v.isPressed = true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { input?.mouseButton(button, false); v.isPressed = false }
                }
                true
            }
            contentDescription = "Botón $label del ratón"
        }
        clicks.addView(mouseButton("Clic izquierdo", MouseButton.LEFT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(ctx, 6) })
        clicks.addView(mouseButton("Clic derecho", MouseButton.RIGHT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 6) })
        content.addView(clicks, Ui.lp(ctx, bottom = 12))

        // Sensitivity (persisted) and scroll direction.
        val config = connectQuick.store.touchpad()
        val sens = SeekBar(ctx).apply {
            max = 35
            progress = ((config.sensitivity - 0.5f) * 10).toInt().coerceIn(0, 35)
            contentDescription = "Velocidad del puntero"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val cfg = (engine?.config ?: config).copy(sensitivity = 0.5f + p / 10f)
                    engine?.config = cfg
                    if (fromUser) connectQuick.store.saveTouchpad(cfg)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val natural = Ui.button(ctx, if (config.naturalScroll) "Desplazamiento natural: sí" else "Desplazamiento natural: no") { v ->
            val cur = engine?.config ?: connectQuick.store.touchpad()
            val next = cur.copy(naturalScroll = !cur.naturalScroll)
            engine?.config = next
            connectQuick.store.saveTouchpad(next)
            (v as android.widget.Button).text = if (next.naturalScroll) "Desplazamiento natural: sí" else "Desplazamiento natural: no"
        }
        content.addView(Ui.card(ctx, Ui.label(ctx, "Velocidad del puntero", 14f, Ui.DIM), sens, Ui.spacer(ctx, 8), natural), Ui.lp(ctx, bottom = 16))

        // Keyboard: free text + the keys a touch keyboard lacks.
        content.addView(Ui.heading(ctx, "Teclado"), Ui.lp(ctx, bottom = 8))
        val field = EditText(ctx).apply {
            hint = "Escribe aquí y se teclea en el PC"
            setHintTextColor(Ui.DIM)
            setTextColor(Ui.TEXT)
            background = Ui.glass(ctx, 14, 28)
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12))
            minimumHeight = Ui.dp(ctx, 52)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            contentDescription = "Campo de texto: lo que escribas se teclea en el PC"
        }
        typing = field
        // Send exactly what was typed, as it is typed; a diff keeps IME composition from re-sending text.
        field.addTextChangedListener(object : android.text.TextWatcher {
            private var before = ""
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { before = s?.toString() ?: "" }
            override fun onTextChanged(s: CharSequence?, start: Int, bef: Int, count: Int) {
                val now = s?.toString() ?: ""
                val input = hub?.input ?: return
                val removed = before.length - commonPrefix(before, now)
                repeat(removed.coerceAtMost(64)) { input.tapKey("Backspace") }
                val added = now.substring(commonPrefix(before, now))
                if (added.isNotEmpty()) input.typeText(added.replace("\n", ""))
                if (added.contains('\n')) input.tapKey("Enter")
                if (now.length > 200) field.setText("") // keep the local box small; the PC already has the text
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        content.addView(field, Ui.lp(ctx, bottom = 10))

        val mods = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        modRow = mods
        sticky.forEach { (code, label) ->
            val b = Ui.button(ctx, label) { v -> toggleModifier(code, v as android.widget.Button) }.apply { contentDescription = "Modificador $label: mantener pulsada en el PC" }
            modifierButtons[code] = b
            mods.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(ctx, 4); marginStart = Ui.dp(ctx, 4) })
        }
        content.addView(mods, Ui.lp(ctx, bottom = 8))

        val keys = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        keyRow = keys
        listOf(
            listOf("Esc" to "Escape", "Tab" to "Tab", "Supr" to "Delete", "⌫" to "Backspace", "↵" to "Enter"),
            listOf("←" to "ArrowLeft", "↑" to "ArrowUp", "↓" to "ArrowDown", "→" to "ArrowRight", "Inicio" to "Home", "Fin" to "End"),
        ).forEach { rowKeys ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowKeys.forEach { (label, code) ->
                row.addView(Ui.button(ctx, label) { hub?.input?.tapKey(code) }.apply {
                    contentDescription = "Tecla $label"
                    setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(ctx, 3); marginStart = Ui.dp(ctx, 3) })
            }
            keys.addView(row, Ui.lp(ctx, bottom = 6))
        }
        content.addView(keys, Ui.lp(ctx, bottom = 8))
        content.addView(Ui.dim(ctx, "Los modificadores se mantienen hasta que los sueltas aquí, y se sueltan solos si sales de esta pantalla o se pierde la conexión."), Ui.lp(ctx, bottom = 12))
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    private fun toggleModifier(code: String, button: android.widget.Button) {
        val input = hub?.input ?: return
        if (heldModifiers.remove(code)) {
            input.key(code, false)
            button.background = Ui.ripple(this, Ui.glass(this, 16, 40))
        } else {
            heldModifiers.add(code)
            input.key(code, true)
            button.background = Ui.ripple(this, android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dpf(this@TouchpadActivity, 16f); setColor(Ui.ACCENT_FILL) })
        }
        button.contentDescription = "Modificador ${sticky[code]}: " + if (code in heldModifiers) "activado" else "desactivado"
    }

    private fun releaseEverything() {
        engine?.cancel()
        heldModifiers.clear()
        modifierButtons.forEach { (_, b) -> b.background = Ui.ripple(this, Ui.glass(this, 16, 40)) }
        hub?.input?.releaseAll()
    }

    override fun render() {
        val h = hub ?: return
        presentation()?.let { banner?.update(it) }
        val can = unavailableReason("mouse") == null
        val canKeys = unavailableReason("keyboard") == null
        surface?.enabledForInput = can
        clickRow?.alpha = if (can) 1f else 0.5f
        typing?.isEnabled = canKeys
        modRow?.alpha = if (canKeys) 1f else 0.5f
        keyRow?.alpha = if (canKeys) 1f else 0.5f
        val input = h.input
        if (input !== engineFor) {
            engine?.cancel()
            engineFor = input
            engine = input?.let { TouchpadEngine(it, connectQuick.store.touchpad()) }
            surface?.engine = engine
            if (input == null && heldModifiers.isNotEmpty()) {
                heldModifiers.clear()
                modifierButtons.forEach { (_, b) -> b.background = Ui.ripple(this, Ui.glass(this, 16, 40)) }
            }
        }
        if (buildOpenKeyboard && canKeys) {
            buildOpenKeyboard = false
            typing?.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(typing, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onPause() {
        // Leaving the screen must never leave a key or button held down on the PC.
        releaseEverything()
        super.onPause()
    }

    companion object { const val EXTRA_OPEN_KEYBOARD = "open_keyboard" }
}
