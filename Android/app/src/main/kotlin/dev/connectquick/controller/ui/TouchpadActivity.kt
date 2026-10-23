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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.connectquick.controller.connectQuick
import dev.connectquick.controller.input.InputClient
import dev.connectquick.controller.input.InputSink
import dev.connectquick.controller.input.Shortcut
import dev.connectquick.controller.input.Shortcuts
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
        val lines = if (enabledForInput) listOf("Un dedo: mover · toque: clic", "Dos dedos: desplazar · toque: clic derecho", "Pellizcar: zoom en el PC", "Tres dedos: cambiar de app, escritorio, tareas", "Toque y arrastre, o «Arrastrar»: seleccionar")
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

/** Touchpad + keyboard: pointer, click buttons, drag lock, typing, shortcuts and the keys a touch keyboard does not have. */
class TouchpadActivity : HubActivity() {
    private var banner: Banner? = null
    private var surface: TouchSurface? = null
    private var engine: TouchpadEngine? = null
    private var engineFor: InputClient? = null
    private val sticky = linkedMapOf("ControlLeft" to "Ctrl", "AltLeft" to "Alt", "ShiftLeft" to "Shift", "MetaLeft" to "Win")
    private val heldModifiers = mutableSetOf<String>()
    private val modifierButtons = mutableMapOf<String, android.widget.Button>()
    private var typing: EditText? = null
    private var keyBar: View? = null
    private var shortcutsView: View? = null
    private var clickRow: LinearLayout? = null
    private var dragButton: android.widget.Button? = null
    private var buildOpenKeyboard = false

    override fun onCreated(savedInstanceState: Bundle?) {
        buildOpenKeyboard = intent.getBooleanExtra(EXTRA_OPEN_KEYBOARD, false)
    }

    /**
     * Modifiers and navigation keys in one strip fixed at the bottom: with the phone's keyboard open it
     * sits right on top of it, so Ctrl/Alt/arrows are one tap away while typing.
     */
    override fun createBottomBar(): View {
        val ctx = this
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6)) }
        fun add(b: android.widget.Button) = row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(ctx, 6) })
        sticky.forEach { (code, label) ->
            val b = Ui.key(ctx, label) { v -> toggleModifier(code, v as android.widget.Button) }.apply { contentDescription = "Modificador $label: mantener pulsada en el PC" }
            modifierButtons[code] = b
            add(b)
        }
        (Shortcuts.keyBar + listOf("⌫" to "Backspace", "↵" to "Enter")).forEach { (label, code) ->
            add(Ui.key(ctx, label) { hub?.input?.tapKey(code) }.apply { contentDescription = "Tecla $label" })
        }
        val bar = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            background = Ui.glass(ctx, 0, 46)
            addView(row)
        }
        keyBar = bar
        return bar
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
                val input = this@TouchpadActivity.hub?.input
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { input?.mouseButton(button, true); v.isPressed = true; Ui.haptic(v) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { input?.mouseButton(button, false); v.isPressed = false }
                }
                true
            }
            contentDescription = "Botón $label del ratón"
        }
        val drag = Ui.button(ctx, "Arrastrar") { v ->
            val e = engine ?: return@button
            Ui.haptic(v, strong = true)
            e.setDragLock(!e.dragLocked)
        }.apply { contentDescription = "Bloquear arrastre: mantiene pulsado el clic izquierdo hasta que toques la superficie" }
        dragButton = drag
        listOf(mouseButton("Izquierdo", MouseButton.LEFT), drag, mouseButton("Derecho", MouseButton.RIGHT)).forEachIndexed { i, b ->
            b.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0)
            clicks.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = Ui.dp(ctx, 6) })
        }
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
        // Scroll speed (finger travel per wheel notch, inverted so right = faster) and pointer acceleration.
        val scroll = SeekBar(ctx).apply {
            max = 84
            progress = (96 - config.scrollPixelsPerNotch).toInt().coerceIn(0, 84)
            contentDescription = "Velocidad del desplazamiento"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val cfg = (engine?.config ?: connectQuick.store.touchpad()).copy(scrollPixelsPerNotch = 96f - p)
                    engine?.config = cfg
                    if (fromUser) connectQuick.store.saveTouchpad(cfg)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val accel = SeekBar(ctx).apply {
            max = 15
            progress = (config.acceleration * 10).toInt().coerceIn(0, 15)
            contentDescription = "Aceleración del puntero"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val cfg = (engine?.config ?: connectQuick.store.touchpad()).copy(acceleration = p / 10f)
                    engine?.config = cfg
                    if (fromUser) connectQuick.store.saveTouchpad(cfg)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        content.addView(Ui.card(ctx,
            Ui.label(ctx, "Velocidad del puntero", 14f, Ui.DIM), sens,
            Ui.label(ctx, "Aceleración (más rápido al deslizar deprisa)", 14f, Ui.DIM), accel,
            Ui.label(ctx, "Velocidad del desplazamiento", 14f, Ui.DIM), scroll,
            Ui.spacer(ctx, 8), natural), Ui.lp(ctx, bottom = 16))

        // Keyboard: free text; modifiers and navigation keys live in the bar at the bottom.
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
        content.addView(field, Ui.lp(ctx, bottom = 6))
        // Long text (a link, an address, a paragraph): type what the phone has copied, in one go.
        content.addView(Ui.button(ctx, "Escribir en el PC lo que copié en el teléfono") { v ->
            val input = hub?.input ?: return@button
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            if (text.isEmpty()) { toast("No hay texto copiado en el teléfono"); return@button }
            val clean = text.take(MAX_PASTE).replace("\r\n", "\n")
            Ui.haptic(v)
            clean.split('\n').forEachIndexed { i, line ->
                if (i > 0) input.tapKey("Enter")
                if (line.isNotEmpty()) input.typeText(line)
            }
            toast(if (text.length > MAX_PASTE) "Escritos $MAX_PASTE de ${text.length} caracteres" else "Escritos ${clean.length} caracteres")
        }.apply { contentDescription = "Teclea en el PC el texto copiado en el portapapeles del teléfono" }, Ui.lp(ctx, bottom = 6))
        content.addView(Ui.dim(ctx, "Ctrl, Alt, Shift y Win se quedan pulsadas hasta que las sueltas abajo, y se sueltan solas si sales de esta pantalla o se pierde la conexión."), Ui.lp(ctx, bottom = 14))

        // Shortcuts and function keys: fixed key combinations, never commands.
        val shortcuts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        shortcutsView = shortcuts
        shortcuts.addView(Ui.heading(ctx, "Atajos"), Ui.lp(ctx, bottom = 8))
        shortcuts.addView(grid(Shortcuts.editing, 4), Ui.lp(ctx, bottom = 8))
        shortcuts.addView(grid(Shortcuts.windows, 4), Ui.lp(ctx, bottom = 12))
        shortcuts.addView(Ui.heading(ctx, "Teclas de función"), Ui.lp(ctx, bottom = 8))
        val fRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        Shortcuts.functionKeys.forEach { code ->
            fRow.addView(Ui.key(ctx, code) { hub?.input?.tapKey(code) }.apply { contentDescription = "Tecla $code" },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(ctx, 6) })
        }
        shortcuts.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(fRow) }, Ui.lp(ctx, bottom = 12))
        content.addView(shortcuts, Ui.lp(ctx, bottom = 8))
    }

    /** Shortcut buttons, [columns] per row. With a modifier held below, a shortcut adds to it (e.g. Shift + Alt+Tab). */
    private fun grid(items: List<Shortcut>, columns: Int): View {
        val ctx = this
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(columns).forEach { chunk ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { i, sc ->
                val b = Ui.key(ctx, sc.label) { hub?.input?.combo(sc.keys) }.apply {
                    contentDescription = "${sc.label}: ${sc.description}"
                    textSize = 13f
                }
                row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = Ui.dp(ctx, 6) })
            }
            repeat(columns - chunk.size) { row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = Ui.dp(ctx, 6) }) }
            box.addView(row, Ui.lp(ctx, bottom = 6))
        }
        return box
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    private fun styleOn(button: android.widget.Button, on: Boolean) {
        button.background = if (on) {
            Ui.ripple(this, android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dpf(this@TouchpadActivity, 16f); setColor(Ui.ACCENT_FILL) })
        } else {
            Ui.ripple(this, Ui.glass(this, 16, 40))
        }
        button.setTextColor(if (on) android.graphics.Color.WHITE else Ui.TEXT)
    }

    private fun toggleModifier(code: String, button: android.widget.Button) {
        val input = hub?.input ?: return
        if (heldModifiers.remove(code)) {
            input.key(code, false)
            styleOn(button, false)
        } else {
            heldModifiers.add(code)
            input.key(code, true)
            styleOn(button, true)
        }
        button.contentDescription = "Modificador ${sticky[code]}: " + if (code in heldModifiers) "activado" else "desactivado"
    }

    private fun clearModifierButtons() {
        heldModifiers.clear()
        modifierButtons.forEach { (_, b) -> styleOn(b, false) }
    }

    private fun releaseEverything() {
        engine?.cancel()
        clearModifierButtons()
        hub?.input?.releaseAll()
    }

    /** Engine sink that adds a haptic tick to every click the touch surface produces. */
    private inner class HapticSink(private val input: InputClient) : InputSink by input {
        override fun mouseButton(button: MouseButton, down: Boolean) {
            input.mouseButton(button, down)
            if (down) surface?.let { Ui.haptic(it) }
        }
    }

    override fun render() {
        val h = hub ?: return
        presentation()?.let { banner?.update(it) }
        val can = unavailableReason("mouse") == null
        val canKeys = unavailableReason("keyboard") == null
        surface?.enabledForInput = can
        clickRow?.alpha = if (can) 1f else 0.5f
        typing?.isEnabled = canKeys
        keyBar?.alpha = if (canKeys) 1f else 0.5f
        shortcutsView?.alpha = if (canKeys) 1f else 0.5f
        val input = h.input
        if (input !== engineFor) {
            engine?.cancel()
            engineFor = input
            engine = input?.let { TouchpadEngine(HapticSink(it), connectQuick.store.touchpad()) }
            engine?.onDragLockChanged = { on -> dragButton?.let { styleOn(it, on); it.text = if (on) "Soltar" else "Arrastrar" } }
            engine?.onGesture = { g ->
                val sc = Shortcuts.forGesture(g)
                input?.combo(sc.keys)
                surface?.let { Ui.haptic(it, strong = true) }
                toast(sc.label)
            }
            dragButton?.let { styleOn(it, false); it.text = "Arrastrar" }
            surface?.engine = engine
            if (input == null && heldModifiers.isNotEmpty()) clearModifierButtons()
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

    companion object {
        const val EXTRA_OPEN_KEYBOARD = "open_keyboard"
        /** Typing is ~1 message per 256 chars; this keeps a paste well under the PC's rate limit. */
        const val MAX_PASTE = 4000
    }
}
