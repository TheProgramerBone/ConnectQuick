package dev.connectquick.controller.ui

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.connectquick.controller.media.MediaAction
import dev.connectquick.controller.session.ControllerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Media keys. Windows cannot confirm that a player acted on a key, so the screen only ever says the
 * key was SENT, never that something is playing.
 */
class MediaActivity : HubActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var banner: Banner? = null
    private var status: TextView? = null
    private val controls = mutableListOf<android.widget.Button>()

    override fun buildContent(hub: ControllerHub?) {
        controls.clear()
        content.addView(backRow("Multimedia"), Ui.lp(this, bottom = 12))
        banner = Banner(this) { hub?.retryNow() }.also { content.addView(it, Ui.lp(this, bottom = 12)) }

        fun key(label: String, desc: String, action: MediaAction, steps: Int = 1, style: Ui.ButtonStyle = Ui.ButtonStyle.SECONDARY) =
            Ui.button(this, label, style) { send(action, steps, desc) }.also { it.contentDescription = desc; controls += it }

        fun row(vararg buttons: android.widget.Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { i, b ->
                addView(b, LinearLayout.LayoutParams(0, Ui.dp(this@MediaActivity, 64), 1f).apply {
                    marginStart = if (i == 0) 0 else Ui.dp(this@MediaActivity, 6)
                })
            }
        }

        content.addView(Ui.card(this,
            Ui.heading(this, "Reproducción"),
            Ui.spacer(this, 10),
            row(key("⏮", "Pista anterior", MediaAction.PREVIOUS), key("⏯", "Reproducir o pausar", MediaAction.PLAY_PAUSE, style = Ui.ButtonStyle.PRIMARY), key("⏭", "Pista siguiente", MediaAction.NEXT)),
            Ui.spacer(this, 10),
            row(key("⏹  Detener", "Detener", MediaAction.STOP)),
        ), Ui.lp(this, bottom = 12))
        content.addView(Ui.card(this,
            Ui.heading(this, "Volumen"),
            Ui.spacer(this, 10),
            row(key("−", "Bajar volumen", MediaAction.VOLUME_DOWN, 2), key("Silenciar", "Silenciar o activar el sonido", MediaAction.MUTE), key("+", "Subir volumen", MediaAction.VOLUME_UP, 2)),
        ), Ui.lp(this, bottom = 12))
        status = Ui.dim(this, "Se envía la tecla multimedia al PC. Windows no confirma qué reproductor responde.")
        content.addView(status, Ui.lp(this, bottom = 12))
    }

    override fun render() {
        presentation()?.let { banner?.update(it) }
        val reason = unavailableReason("media")
        controls.forEach { it.isEnabled = reason == null; it.alpha = if (reason == null) 1f else 0.5f }
        if (reason != null) status?.text = reason
    }

    private fun send(action: MediaAction, steps: Int, description: String) {
        val client = hub?.media ?: return
        scope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) { client.control(action, steps) }
            val text = when {
                r.delivered -> "Enviado: $description."
                r.reason == "timeout" -> "El PC no respondió a tiempo."
                r.reason == "disabled_by_host" -> "El PC tiene el control multimedia desactivado."
                r.reason == "forbidden" -> "Sin permiso de multimedia."
                else -> "No se pudo enviar (${r.reason ?: "error"})."
            }
            status?.text = text
            status?.announceForAccessibility(text)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
