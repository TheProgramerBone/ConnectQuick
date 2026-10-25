package dev.connectquick.controller.ui

import android.widget.LinearLayout
import android.widget.TextView
import dev.connectquick.controller.input.Shortcut
import dev.connectquick.controller.input.Shortcuts
import dev.connectquick.controller.session.ControllerHub

/**
 * Slide-show remote: big Previous/Next buttons plus start, black/white screen and exit. Only sends the
 * ordinary keys of a keyboard (keyboard permission); it cannot tell whether a presentation is running.
 */
class PresentationActivity : HubActivity() {
    private var banner: Banner? = null
    private var status: TextView? = null
    private val controls = mutableListOf<android.widget.Button>()

    override fun buildContent(hub: ControllerHub?) {
        controls.clear()
        content.addView(backRow("Presentación"), Ui.lp(this, bottom = 12))
        banner = Banner(this) { hub?.retryNow() }.also { content.addView(it, Ui.lp(this, bottom = 12)) }

        fun key(shortcut: Shortcut, style: Ui.ButtonStyle = Ui.ButtonStyle.SECONDARY) =
            Ui.key(this, shortcut.label) { send(shortcut) }.also {
                it.contentDescription = shortcut.description
                controls += it
            }

        fun row(heightDp: Int, vararg buttons: android.widget.Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { i, b ->
                addView(b, LinearLayout.LayoutParams(0, Ui.dp(this@PresentationActivity, heightDp), 1f).apply {
                    marginStart = if (i == 0) 0 else Ui.dp(this@PresentationActivity, 6)
                })
            }
        }

        content.addView(Ui.card(this,
            Ui.heading(this, "Diapositivas"),
            Ui.spacer(this, 10),
            row(120, key(Shortcuts.presentationPrevious), key(Shortcuts.presentationNext, Ui.ButtonStyle.PRIMARY)),
        ), Ui.lp(this, bottom = 12))
        content.addView(Ui.card(this,
            Ui.heading(this, "Presentación"),
            Ui.spacer(this, 10),
            row(64, key(Shortcuts.presentationStart), key(Shortcuts.presentationStartHere)),
            Ui.spacer(this, 10),
            row(64, key(Shortcuts.presentationBlack), key(Shortcuts.presentationWhite)),
            Ui.spacer(this, 10),
            row(64, key(Shortcuts.presentationEnd)),
        ), Ui.lp(this, bottom = 12))
        status = Ui.dim(this, "Envía las teclas de siempre (flechas, F5, B, W, Esc) a la ventana activa del PC. No puede saber si hay una presentación abierta.")
        content.addView(status, Ui.lp(this, bottom = 12))
    }

    override fun render() {
        presentation()?.let { banner?.update(it) }
        val reason = unavailableReason("keyboard")
        controls.forEach { it.isEnabled = reason == null; it.alpha = if (reason == null) 1f else 0.5f }
        if (reason != null) status?.text = reason
    }

    private fun send(shortcut: Shortcut) {
        val input = hub?.input ?: return
        Thread { input.combo(shortcut.keys) }.apply { isDaemon = true }.start()
        status?.text = "Enviado: ${shortcut.description}."
    }
}
