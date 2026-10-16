package dev.connectquick.controller.ui

import android.widget.TextView
import dev.connectquick.controller.power.PowerAction
import dev.connectquick.controller.power.PowerRequestOutcome
import dev.connectquick.controller.power.PowerResult
import dev.connectquick.controller.session.ControllerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Power control. Locking is one tap. Sleep, restart and shut down always go through an explicit
 * confirmation dialog that names the action and warns about unsaved work: the PC only acts once the
 * token it handed out is sent back after that confirmation. There is no way to run any other command.
 */
class PowerActivity : HubActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var banner: Banner? = null
    private var status: TextView? = null
    private val controls = mutableListOf<android.widget.Button>()

    override fun buildContent(hub: ControllerHub?) {
        controls.clear()
        content.addView(backRow("Energía"), Ui.lp(this, bottom = 12))
        banner = Banner(this) { hub?.retryNow() }.also { content.addView(it, Ui.lp(this, bottom = 12)) }

        fun action(label: String, a: PowerAction, style: Ui.ButtonStyle) = Ui.button(this, label, style) { request(a) }.also { controls += it }
        content.addView(Ui.card(this,
            Ui.heading(this, "Seguro"),
            Ui.dim(this, "Bloquea la sesión de Windows. No se pierde nada.").apply { setPadding(0, 0, 0, Ui.dp(this@PowerActivity, 10)) },
            action("Bloquear el PC", PowerAction.LOCK, Ui.ButtonStyle.PRIMARY),
        ), Ui.lp(this, bottom = 12))
        content.addView(Ui.card(this,
            Ui.heading(this, "Pide confirmación"),
            Ui.dim(this, "Puede cerrar tu trabajo sin guardar. Te preguntaremos antes de hacer nada. Si el PC lo permite, da unos segundos para cancelar.").apply { setPadding(0, 0, 0, Ui.dp(this@PowerActivity, 10)) },
            action("Suspender", PowerAction.SLEEP, Ui.ButtonStyle.SECONDARY),
            Ui.spacer(this, 8),
            action("Reiniciar", PowerAction.RESTART, Ui.ButtonStyle.DANGER),
            Ui.spacer(this, 8),
            action("Apagar", PowerAction.SHUTDOWN, Ui.ButtonStyle.DANGER),
            Ui.spacer(this, 8),
            action("Cancelar apagado o reinicio pendiente", PowerAction.CANCEL, Ui.ButtonStyle.SECONDARY),
        ), Ui.lp(this, bottom = 12))
        status = Ui.dim(this, "El PC puede limitar estas acciones (por defecto solo permite bloquear).")
        content.addView(status, Ui.lp(this, bottom = 12))
    }

    override fun render() {
        presentation()?.let { banner?.update(it) }
        val reason = unavailableReason("power")
        controls.forEach { it.isEnabled = reason == null; it.alpha = if (reason == null) 1f else 0.5f }
        if (reason != null) status?.text = reason
    }

    private fun request(action: PowerAction) {
        val client = hub?.power ?: return
        if (client.inFlight) return
        scope.launch {
            when (val outcome = kotlinx.coroutines.withContext(Dispatchers.IO) { client.request(action) }) {
                is PowerRequestOutcome.Done -> report(outcome.result)
                is PowerRequestOutcome.Failed -> say(failure(outcome.reason))
                is PowerRequestOutcome.NeedsConfirmation -> {
                    val c = outcome.challenge
                    val name = nameOf(c.action)
                    val grace = if (c.delaySeconds > 0) " Tendrás ${c.delaySeconds} segundos para cancelarlo desde aquí." else ""
                    confirm(
                        "¿$name el PC?",
                        "Se perderá el trabajo que no esté guardado.$grace",
                        name, danger = true,
                    ) {
                        scope.launch {
                            val result = kotlinx.coroutines.withContext(Dispatchers.IO) { client.confirm(c) }
                            report(result)
                        }
                    }
                }
            }
        }
    }

    private fun nameOf(a: PowerAction) = when (a) {
        PowerAction.SLEEP -> "Suspender"; PowerAction.RESTART -> "Reiniciar"; PowerAction.SHUTDOWN -> "Apagar"
        PowerAction.LOCK -> "Bloquear"; PowerAction.CANCEL -> "Cancelar"
    }

    private fun report(r: PowerResult) = say(
        if (r.ok) when (r.action) {
            PowerAction.LOCK -> "PC bloqueado."
            PowerAction.CANCEL -> "Cancelado."
            else -> "${nameOf(r.action)}: orden aceptada por el PC." + if (r.delaySeconds > 0) " Se ejecutará en ${r.delaySeconds} s." else ""
        } else failure(r.reason ?: "error"),
    )

    private fun say(text: String) { status?.text = text; status?.announceForAccessibility(text) }

    private fun failure(code: String) = when (code) {
        "policy_lock_only" -> "El PC solo permite bloquear. Cámbialo en Permisos y ajustes del PC."
        "disabled_by_host" -> "El PC tiene el control de energía desactivado."
        "forbidden" -> "Este teléfono no tiene permiso de energía."
        "too_fast" -> "Espera unos segundos antes de repetir."
        "invalid_or_expired" -> "La confirmación caducó. Vuelve a empezar."
        "policy_changed" -> "El PC cambió la política mientras confirmabas. No se hizo nada."
        "nothing_to_cancel" -> "No había nada pendiente que cancelar."
        "timeout" -> "El PC no respondió a tiempo."
        "session_closed" -> "Se perdió la conexión con el PC."
        "busy" -> "Ya hay una petición en curso."
        else -> "El PC no pudo hacerlo ($code)."
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
