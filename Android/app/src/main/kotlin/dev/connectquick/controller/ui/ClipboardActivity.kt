package dev.connectquick.controller.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import dev.connectquick.controller.clipboard.ClipboardClient
import dev.connectquick.controller.clipboard.ClipboardOutcome
import dev.connectquick.controller.clipboard.PullOutcome
import dev.connectquick.controller.session.ControllerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Plain-text clipboard in both directions, always an explicit tap. */
class ClipboardActivity : HubActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var banner: Banner? = null
    private var status: TextView? = null
    private var preview: EditText? = null
    private val buttons = mutableListOf<android.widget.Button>()

    override fun buildContent(hub: ControllerHub?) {
        buttons.clear()
        content.addView(backRow("Portapapeles"), Ui.lp(this, bottom = 12))
        banner = Banner(this) { hub?.retryNow() }.also { content.addView(it, Ui.lp(this, bottom = 12)) }

        val send = Ui.button(this, "Enviar al PC lo que he copiado aquí", Ui.ButtonStyle.PRIMARY) { sendLocal() }
        val get = Ui.button(this, "Traer lo copiado en el PC") { pullRemote() }
        buttons += listOf(send, get)
        val box = EditText(this).apply {
            hint = "Aquí aparece el texto que traes del PC. También puedes escribir algo y enviarlo."
            setHintTextColor(Ui.DIM); setTextColor(Ui.TEXT)
            background = Ui.glass(this@ClipboardActivity, 14, 28)
            minLines = 4; gravity = android.view.Gravity.TOP
            setPadding(Ui.dp(this@ClipboardActivity, 14), Ui.dp(this@ClipboardActivity, 12), Ui.dp(this@ClipboardActivity, 14), Ui.dp(this@ClipboardActivity, 12))
            contentDescription = "Texto del portapapeles"
        }
        preview = box
        val sendBox = Ui.button(this, "Enviar este texto al PC") { sendText(box.text.toString()) }
        buttons += sendBox
        status = Ui.dim(this, "Solo texto, hasta ${ClipboardClient.MAX_TEXT_BYTES / 1024} KB. Nada se envía sin que lo pidas.")
        content.addView(Ui.card(this, send, Ui.spacer(this, 10), get), Ui.lp(this, bottom = 12))
        content.addView(Ui.card(this, box, Ui.spacer(this, 10), sendBox), Ui.lp(this, bottom = 12))
        content.addView(status, Ui.lp(this, bottom = 12))
    }

    override fun render() {
        presentation()?.let { banner?.update(it) }
        val reason = unavailableReason("clipboard")
        buttons.forEach { it.isEnabled = reason == null; it.alpha = if (reason == null) 1f else 0.5f }
        if (reason != null) status?.text = reason
    }

    private fun localClipText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }
    }

    private fun sendLocal() {
        val text = localClipText() ?: return setStatus("No hay texto copiado en este teléfono.")
        sendText(text)
    }

    private fun sendText(text: String) {
        val client = hub?.clipboard ?: return
        if (text.isEmpty()) return setStatus("No hay nada que enviar.")
        setStatus("Enviando…")
        scope.launch {
            when (val r = withIo { client.push(text) }) {
                ClipboardOutcome.Sent -> setStatus("Enviado al portapapeles del PC.")
                ClipboardOutcome.Duplicate -> setStatus("El PC ya tiene ese mismo texto.")
                is ClipboardOutcome.Failed -> setStatus(failure(r.reason))
            }
        }
    }

    private fun pullRemote() {
        val client = hub?.clipboard ?: return
        setStatus("Pidiendo el portapapeles del PC…")
        scope.launch {
            when (val r = withIo { client.pull() }) {
                is PullOutcome.Text -> {
                    preview?.setText(r.text)
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ConnectQuick", r.text))
                    client.noteApplied(r.text)
                    setStatus("Copiado en este teléfono (${r.text.length} caracteres).")
                }
                is PullOutcome.Failed -> setStatus(failure(r.reason))
            }
        }
    }

    private suspend fun <T> withIo(block: suspend () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private fun setStatus(text: String) { status?.text = text; status?.announceForAccessibility(text) }

    private fun failure(code: String) = when (code) {
        "empty" -> "No hay texto que enviar."
        "too_large" -> "Es demasiado largo (máximo ${ClipboardClient.MAX_TEXT_BYTES / 1024} KB)."
        "timeout" -> "El PC no respondió a tiempo."
        "session_closed" -> "Se perdió la conexión con el PC."
        "forbidden", "disabled_by_host" -> "El PC tiene el portapapeles desactivado."
        "empty_or_not_text" -> "El portapapeles del PC está vacío o no contiene texto."
        else -> "No se pudo ($code)."
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
