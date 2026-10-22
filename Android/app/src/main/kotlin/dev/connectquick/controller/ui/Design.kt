package dev.connectquick.controller.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.connectquick.controller.session.Tone

/**
 * "Liquid Crystal" for the phone: a deep calm backdrop, translucent glass surfaces with a bright
 * hairline and a faint top sheen, one cool accent. Status is always colour + shape + words. All
 * sizes are dp/sp (system font scale is honoured, screens scroll), touch targets are at least
 * 48 dp, and when the system "high contrast text" option is on the glass becomes opaque with
 * strong borders.
 *
 * Built from framework drawables and views only: no image assets and no extra libraries.
 */
object Ui {
    /** Dark is the default; flipped from the saved preference at start-up and by the toggle on the home screen. */
    @Volatile var light: Boolean = false

    private fun pick(dark: Long, lightColor: Long): Int = (if (light) lightColor else dark).toInt()

    // Text/background pairs keep >= 4.5:1 in both themes (body text ~16:1 dark, ~14:1 light).
    val BG0: Int get() = pick(0xFF070A18, 0xFFE2ECFA)
    val BG1: Int get() = pick(0xFF161C3E, 0xFFF6F9FF)
    val TEXT: Int get() = pick(0xFFF0F4FF, 0xFF111830)
    val DIM: Int get() = pick(0xFFACB8D6, 0xFF404E70)
    val ACCENT: Int get() = pick(0xFF68D0FF, 0xFF0060BE)
    val ACCENT_FILL: Int get() = pick(0xFF2E80E6, 0xFF0066CC)
    val ACCENT_TOP: Int get() = pick(0xFF5CA8FF, 0xFF3A96F5)
    val OK: Int get() = pick(0xFF5EE2A8, 0xFF008054)
    val WARN: Int get() = pick(0xFFFFCC60, 0xFFA05A00)
    val DANGER: Int get() = pick(0xFFFF7A84, 0xFFBE1E37)
    val DANGER_FILL: Int get() = 0xFFBE3448.toInt()

    /** Backdrop, system-bar colours and bar icon contrast for the active theme. Call first thing in onCreate. */
    @Suppress("DEPRECATION")
    fun styleWindow(window: android.view.Window) {
        window.setBackgroundDrawable(backdrop(window.context))
        window.statusBarColor = BG1
        window.navigationBarColor = BG0
        var flags = window.decorView.systemUiVisibility
        val bars = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (android.os.Build.VERSION.SDK_INT >= 26) android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        flags = if (light) flags or bars else flags and bars.inv()
        window.decorView.systemUiVisibility = flags
    }

    fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    fun dpf(ctx: Context, v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun highContrast(ctx: Context): Boolean =
        try { Settings.Secure.getInt(ctx.contentResolver, "high_text_contrast_enabled", 0) == 1 } catch (e: Exception) { false }

    fun toneColor(t: Tone): Int = when (t) {
        Tone.OK -> OK
        Tone.BUSY -> ACCENT
        Tone.WARN -> WARN
        Tone.BAD -> DANGER
        Tone.IDLE -> DIM
    }

    /** Vertical gradient plus soft coloured lights, like light refracting through crystal. */
    fun backdrop(ctx: Context): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG1, BG0))
        if (highContrast(ctx)) return base
        fun orb(cx: Float, cy: Float, radiusDp: Int, argb: Int): Drawable = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(cx, cy)
            gradientRadius = dpf(ctx, radiusDp.toFloat())
            colors = intArrayOf(argb, Color.argb(0, Color.red(argb), Color.green(argb), Color.blue(argb)))
        }
        val layers = if (light) arrayOf(base, orb(0.9f, 0.02f, 420, 0x6090C8FF), orb(0.05f, 0.95f, 380, 0x58B79CFF), orb(0.6f, 0.55f, 260, 0x40FFC6E8))
        else arrayOf(base, orb(0.9f, 0.02f, 420, 0x2E5AAAFF), orb(0.05f, 0.95f, 380, 0x288A5CFF), orb(0.6f, 0.55f, 260, 0x1A22D4C8))
        return LayerDrawable(layers)
    }

    /** A glass surface: translucent fill, top sheen, 1 dp hairline. */
    fun glass(ctx: Context, radiusDp: Int = 18, fillAlpha: Int = 34): Drawable {
        val hc = highContrast(ctx)
        val r = dpf(ctx, radiusDp.toFloat())
        val fill = GradientDrawable().apply {
            cornerRadius = r
            setColor(if (hc) (if (light) Color.WHITE else 0xFF1B2347.toInt()) else Color.argb(if (light) minOf(255, fillAlpha * 4 + 14) else fillAlpha, 255, 255, 255))
            val stroke = if (hc) (if (light) Color.BLACK else Color.WHITE) else if (light) Color.argb(150, 120, 140, 185) else Color.argb(84, 255, 255, 255)
            setStroke(dp(ctx, if (hc) 2 else 1), stroke)
        }
        if (hc) return fill
        // Depth: a faint shade pooling at the bottom and a bright sheen across the top read as thick crystal.
        val shade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, if (light) Color.argb(22, 90, 110, 160) else Color.argb(60, 0, 0, 20))).apply {
            cornerRadius = r
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(if (light) 170 else 56, 255, 255, 255), Color.TRANSPARENT)).apply {
            cornerRadius = r
        }
        return LayerDrawable(arrayOf(fill, shade, sheen))
    }

    private fun solid(ctx: Context, color: Int, radiusDp: Int): Drawable = GradientDrawable().apply {
        cornerRadius = dpf(ctx, radiusDp.toFloat())
        setColor(color)
        setStroke(dp(ctx, 1), Color.argb(90, 255, 255, 255))
    }

    fun ripple(ctx: Context, content: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(if (light) Color.argb(50, 0, 60, 140) else Color.argb(60, 255, 255, 255)), content, null)

    fun label(ctx: Context, text: CharSequence, size: Float = 16f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(0f, 1.1f)
        }

    fun title(ctx: Context, text: CharSequence) = label(ctx, text, 28f, TEXT, true).apply {
        accessibilityHeading()
    }

    fun heading(ctx: Context, text: CharSequence) = label(ctx, text, 18f, TEXT, true).apply { accessibilityHeading() }

    fun dim(ctx: Context, text: CharSequence) = label(ctx, text, 14f, DIM)

    enum class ButtonStyle { SECONDARY, PRIMARY, DANGER }

    fun button(ctx: Context, text: CharSequence, style: ButtonStyle = ButtonStyle.SECONDARY, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minimumHeight = dp(ctx, 52)
            minHeight = dp(ctx, 52)
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
            setTextColor(if (style == ButtonStyle.SECONDARY) TEXT else Color.WHITE)
            background = ripple(ctx, when (style) {
                ButtonStyle.PRIMARY -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(ACCENT_TOP, ACCENT_FILL)).apply {
                    cornerRadius = dpf(ctx, 16f)
                    setStroke(dp(ctx, 1), Color.argb(90, 255, 255, 255))
                }
                ButtonStyle.DANGER -> solid(ctx, DANGER_FILL, 16)
                ButtonStyle.SECONDARY -> glass(ctx, 16, 40)
            })
            stateListAnimator = null
            setOnClickListener(onClick)
        }

    fun card(ctx: Context, vararg children: View, paddingDp: Int = 18): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(ctx)
            setPadding(dp(ctx, paddingDp), dp(ctx, paddingDp), dp(ctx, paddingDp), dp(ctx, paddingDp))
            children.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }

    fun spacer(ctx: Context, heightDp: Int): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(ctx, heightDp)) }

    fun lp(ctx: Context, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, top); bottomMargin = dp(ctx, bottom)
        }

    private fun TextView.accessibilityHeading() {
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isHeading = true
            }
        }
    }
}

/** Colour + shape + words for a link/connection state. The shape differs per tone, so it is readable without colour. */
class StatusPill(ctx: Context) : View(ctx) {
    private var tone: Tone = Tone.IDLE
    private var text: String = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; textSize = Ui.dpf(ctx, 0f) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumHeight = Ui.dp(ctx, 40)
    }

    fun set(tone: Tone, text: String) {
        if (tone == this.tone && text == this.text) return
        this.tone = tone
        this.text = text
        contentDescription = "Estado: $text"
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        val h = maxOf(Ui.dp(context, 40), (textPaint.fontMetrics.let { it.descent - it.ascent } + Ui.dp(context, 12)).toInt())
        setMeasuredDimension(resolveSize(Ui.dp(context, 200), widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val d = Ui.dpf(context, 14f)
        val cx = d / 2 + paddingLeft
        val cy = height / 2f
        paint.color = Ui.toneColor(tone)
        paint.style = Paint.Style.FILL
        when (tone) {
            Tone.OK -> canvas.drawCircle(cx, cy, d / 2, paint)
            Tone.BUSY -> { paint.style = Paint.Style.STROKE; paint.strokeWidth = Ui.dpf(context, 2.5f); canvas.drawCircle(cx, cy, d / 2 - 1, paint) }
            Tone.WARN -> canvas.drawPath(Path().apply { moveTo(cx, cy - d / 2); lineTo(cx + d / 2, cy + d / 2); lineTo(cx - d / 2, cy + d / 2); close() }, paint)
            Tone.BAD -> canvas.drawRect(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2, paint)
            Tone.IDLE -> { paint.style = Paint.Style.STROKE; paint.strokeWidth = Ui.dpf(context, 2f); canvas.drawRect(cx - d / 2 + 1, cy - d / 2 + 1, cx + d / 2 - 1, cy + d / 2 - 1, paint) }
        }
        val baseline = cy - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2
        canvas.drawText(text, cx + d / 2 + Ui.dpf(context, 12f), baseline, textPaint)
    }
}

/** Icons drawn in code (no assets). Each glyph is a few strokes in a 24x24 box. */
class GlyphView(ctx: Context, private val glyph: Glyph, private val color: Int = Ui.ACCENT) : View(ctx) {
    enum class Glyph { TOUCHPAD, KEYBOARD, SCREEN, CLIPBOARD, MEDIA, FOLDER, POWER, PC }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onMeasure(w: Int, h: Int) {
        val s = Ui.dp(context, 32)
        setMeasuredDimension(resolveSize(s, w), resolveSize(s, h))
    }

    override fun onDraw(c: Canvas) {
        paint.color = color
        paint.strokeWidth = width / 12f
        val u = width / 24f
        fun r(l: Float, t: Float, rr: Float, b: Float, rad: Float = 2f) = c.drawRoundRect(RectF(l * u, t * u, rr * u, b * u), rad * u, rad * u, paint)
        fun ln(x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1 * u, y1 * u, x2 * u, y2 * u, paint)
        when (glyph) {
            Glyph.TOUCHPAD -> { r(3f, 5f, 21f, 19f, 3f); ln(3f, 15f, 21f, 15f); ln(12f, 15f, 12f, 19f) }
            Glyph.KEYBOARD -> { r(2f, 6f, 22f, 18f, 3f); ln(6f, 10f, 7f, 10f); ln(10f, 10f, 11f, 10f); ln(14f, 10f, 15f, 10f); ln(18f, 10f, 19f, 10f); ln(7f, 14f, 17f, 14f) }
            Glyph.SCREEN -> { r(2f, 4f, 22f, 16f, 2f); ln(8f, 20f, 16f, 20f); ln(12f, 16f, 12f, 20f) }
            Glyph.CLIPBOARD -> { r(5f, 4f, 19f, 21f, 2f); r(9f, 2f, 15f, 6f, 1.5f); ln(8f, 11f, 16f, 11f); ln(8f, 15f, 14f, 15f) }
            Glyph.MEDIA -> { c.drawPath(Path().apply { moveTo(8 * u, 5 * u); lineTo(19 * u, 12 * u); lineTo(8 * u, 19 * u); close() }, paint) }
            Glyph.FOLDER -> { c.drawPath(Path().apply { moveTo(3 * u, 7 * u); lineTo(10 * u, 7 * u); lineTo(12 * u, 9 * u); lineTo(21 * u, 9 * u); lineTo(21 * u, 19 * u); lineTo(3 * u, 19 * u); close() }, paint) }
            Glyph.POWER -> { ln(12f, 3f, 12f, 12f); c.drawArc(RectF(5 * u, 5 * u, 19 * u, 19 * u), -55f, 290f, false, paint) }
            Glyph.PC -> { r(3f, 3f, 21f, 15f, 2f); ln(8f, 20f, 16f, 20f); ln(12f, 15f, 12f, 20f) }
        }
    }
}

/** A tappable feature tile: glyph, title, one-line state. Disabled tiles say WHY, never just grey out. */
class FeatureTile(ctx: Context, glyph: GlyphView.Glyph, private val title: String) : LinearLayout(ctx) {
    private val subtitle = Ui.label(ctx, "", 13f, Ui.DIM)
    private val titleView = Ui.label(ctx, title, 16f, Ui.TEXT, true)
    private val icon = GlyphView(ctx, glyph)

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        minimumHeight = Ui.dp(ctx, 112)
        setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 14))
        background = Ui.ripple(ctx, Ui.glass(ctx, 18))
        isClickable = true
        isFocusable = true
        addView(icon, LayoutParams(Ui.dp(ctx, 32), Ui.dp(ctx, 32)))
        addView(titleView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 10) })
        addView(subtitle, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** [reason] is shown (and spoken) when the tile cannot be used; null means ready. */
    fun update(reason: String?, readyHint: String) {
        val ok = reason == null
        subtitle.text = reason ?: readyHint
        alpha = if (ok) 1f else 0.62f
        contentDescription = if (ok) "$title. $readyHint" else "$title. No disponible: $reason"
        isEnabled = ok
        titleView.setTextColor(if (ok) Ui.TEXT else Ui.DIM)
    }
}
