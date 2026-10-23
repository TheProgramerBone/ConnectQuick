package dev.connectquick.controller.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.connectquick.controller.connectQuick
import dev.connectquick.controller.net.Subscription
import dev.connectquick.controller.session.ControllerHub
import dev.connectquick.controller.session.LinkAction
import dev.connectquick.controller.session.LinkPresentation
import dev.connectquick.controller.session.LinkTexts

/**
 * Base for every screen that talks to the PC. It owns the glass backdrop and a scrolling column
 * (so large system fonts never clip anything), subscribes to the connection while visible, and
 * calls [render] on the UI thread whenever the link or the session changes. Screens build their
 * views once in [buildContent] and only update them in [render].
 */
abstract class HubActivity : Activity() {
    protected val hub: ControllerHub? get() = connectQuick.hub
    protected lateinit var content: LinearLayout
    private var subscription: Subscription? = null
    private var builtFor: ControllerHub? = null

    /** Override to false for screens that draw their own full-screen content instead of a scrolling column. */
    protected open val scrolling: Boolean = true

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.styleWindow(window)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(this@HubActivity, 20)
            setPadding(pad, pad, pad, pad)
        }
        if (scrolling) {
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val bar = createBottomBar()
            if (bar == null) {
                scroll.fitsSystemWindows = true
                setContentView(scroll)
            } else {
                // The bar sits outside the scrolling column, so with adjustResize it rides right above the phone's keyboard.
                setContentView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    fitsSystemWindows = true
                    addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                    addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                })
            }
        } else {
            content.fitsSystemWindows = true
            setContentView(content)
        }
        onCreated(savedInstanceState)
        rebuildIfNeeded()
    }

    /** A strip fixed under the scrolling column (scrolling screens only), or null for none. */
    protected open fun createBottomBar(): View? = null

    /** Hook after the shell exists; build static views here only if they do not depend on the paired PC. */
    protected open fun onCreated(savedInstanceState: Bundle?) {}

    /** Builds the screen for [hub] (called again if the paired PC changes). */
    protected abstract fun buildContent(hub: ControllerHub?)

    protected abstract fun render()

    private fun rebuildIfNeeded() {
        val current = hub
        if (builtFor !== current || content.childCount == 0) {
            builtFor = current
            content.removeAllViews()
            buildContent(current)
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        rebuildIfNeeded()
        subscription = hub?.addListener {
            runOnUiThread { if (!isFinishing && !isDestroyed) rebuildIfNeeded() }
        }
    }

    override fun onStop() {
        subscription?.cancel()
        subscription = null
        super.onStop()
    }

    protected fun presentation(): LinkPresentation? = hub?.let { LinkTexts.of(it.state, it.host.hostName) }

    /** Reason a feature cannot be used right now, or null when it can. Always names the real cause. */
    protected fun unavailableReason(permission: String?): String? {
        val h = hub ?: return "Sin PC vinculado"
        val p = LinkTexts.of(h.state, h.host.hostName)
        if (!p.canControl) return p.headline
        if (permission != null && !h.can(permission)) return "Sin permiso: se concede en el PC"
        return null
    }

    protected fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    protected fun backRow(titleText: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val back = Ui.button(this, "‹  Volver") { finish() }.apply { contentDescription = "Volver" }
        row.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(Ui.title(this, titleText).apply { textSize = 22f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(this@HubActivity, 14) })
        return row
    }

    protected fun confirm(title: String, message: String, positive: String, danger: Boolean = false, onYes: () -> Unit) {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(positive) { _, _ -> onYes() }
            .create()
        dialog.show()
        if (danger) dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(Ui.DANGER)
    }

    protected fun requireOnlineOrNull(permission: String): ControllerHub? {
        val h = hub ?: return null
        return if (unavailableReason(permission) == null) h else null
    }
}

/** Connection summary shown at the top of every control screen: shape + colour + words, plus the one useful action. */
class Banner(context: Context, private val onAction: (LinkAction) -> Unit) : LinearLayout(context) {
    private val pill = StatusPill(context)
    private val detail = Ui.dim(context, "")
    private val action: Button = Ui.button(context, "Conectar") { onAction(current) }
    private var current = LinkAction.NONE

    init {
        orientation = VERTICAL
        background = Ui.glass(context)
        val pad = Ui.dp(context, 16)
        setPadding(pad, pad, pad, pad)
        addView(pill, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(detail, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 4) })
        addView(action, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 12) })
        isFocusable = true
    }

    fun update(p: LinkPresentation) {
        pill.set(p.tone, p.headline)
        detail.text = p.detail
        current = p.action
        action.visibility = if (p.action == LinkAction.NONE) View.GONE else View.VISIBLE
        action.text = when (p.action) {
            LinkAction.CONNECT -> "Conectar"
            LinkAction.RETRY -> "Reintentar ahora"
            LinkAction.REPAIR -> "Vincular de nuevo"
            LinkAction.NONE -> ""
        }
        contentDescription = "${p.headline}. ${p.detail}"
    }
}
