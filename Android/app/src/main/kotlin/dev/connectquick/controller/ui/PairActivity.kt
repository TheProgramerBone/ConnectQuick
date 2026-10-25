package dev.connectquick.controller.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.connectquick.controller.connectQuick
import dev.connectquick.controller.net.BackendClient
import dev.connectquick.controller.pairing.PairingController
import dev.connectquick.controller.pairing.PairingOutcome
import dev.connectquick.controller.pairing.QrPairingPayload
import dev.connectquick.controller.session.PairedHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Pairing, step by step: scan the PC's QR, show the comparison code, wait for the person at the PC to
 * authorize. The phone derives the code itself, so seeing the same digits on both screens proves nobody
 * is in between. The pairing uses its own connection; the normal link is paused meanwhile because both
 * use this phone's identity (the server keeps only one connection per identity).
 */
class PairActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var client: BackendClient? = null
    private lateinit var column: LinearLayout
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.styleWindow(window)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(this@PairActivity, 20)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        connectQuick.hub?.stop() // same identity, one connection: pause the normal link while pairing
        showIntro()
    }

    private fun show(vararg views: android.view.View) {
        column.removeAllViews()
        column.addView(Ui.title(this, "Vincular un PC"), Ui.lp(this, bottom = 14))
        views.forEach { column.addView(it, Ui.lp(this, bottom = 12)) }
    }

    private fun showIntro() = show(
        Ui.card(
            this,
            Ui.heading(this, "Escanea el código del PC"),
            Ui.dim(this, "En el PC, abre ConnectQuick y elige «Vincular un teléfono». Se mostrará un código QR que caduca en un minuto.").apply { setPadding(0, Ui.dp(this@PairActivity, 8), 0, Ui.dp(this@PairActivity, 14)) },
            Ui.button(this, "Escanear código QR", Ui.ButtonStyle.PRIMARY) { startActivityForResult(Intent(this, ScanActivity::class.java), REQ_SCAN) },
        ),
        Ui.dim(this, "La cámara solo se usa para leer este código. Nada se graba ni se envía."),
        Ui.button(this, "Cancelar") { finish() },
    )

    private fun showProgress(title: String, detail: String, sas: String? = null) {
        val views = mutableListOf<android.view.View>()
        val parts = mutableListOf<android.view.View>(Ui.heading(this, title), Ui.dim(this, detail).apply { setPadding(0, Ui.dp(this@PairActivity, 6), 0, 0) })
        if (sas != null) {
            val spaced = if (sas.length == 6) "${sas.substring(0, 3)} ${sas.substring(3)}" else sas
            parts += Ui.label(this, spaced, 44f, Ui.ACCENT, true).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, Ui.dp(this@PairActivity, 18), 0, Ui.dp(this@PairActivity, 6))
                contentDescription = "Código de comparación: " + sas.toCharArray().joinToString(" ")
            }
            parts += Ui.dim(this, "Si el PC muestra otro código, pulsa «No» allí y cancela aquí.")
        }
        views += Ui.card(this, *parts.toTypedArray())
        views += Ui.button(this, "Cancelar") { cancelPairing(); finish() }
        show(*views.toTypedArray())
        column.announceForAccessibility(title)
    }

    private fun showFailure(reason: String) = show(
        Ui.card(
            this,
            Ui.heading(this, "No se pudo vincular"),
            Ui.dim(this, friendly(reason)).apply { setPadding(0, Ui.dp(this@PairActivity, 8), 0, Ui.dp(this@PairActivity, 14)) },
            Ui.button(this, "Intentar de nuevo", Ui.ButtonStyle.PRIMARY) { showIntro() },
        ),
        Ui.button(this, "Cerrar") { finish() },
    )

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCAN) return
        val text = data?.getStringExtra(ScanActivity.EXTRA_QR_TEXT)
        if (resultCode != RESULT_OK || text == null) return
        val qr = try {
            Json { ignoreUnknownKeys = true }.decodeFromString(QrPairingPayload.serializer(), text)
        } catch (e: Exception) {
            showFailure("invalid_qr"); return
        }
        startPairing(qr)
    }

    private fun startPairing(qr: QrPairingPayload) {
        cancelPairing()
        showProgress("Conectando con «${qr.hostName}»…", "Un momento.")
        val c = BackendClient(qr.relay)
        client = c
        val controller = PairingController(c, connectQuick.identity, deviceName = deviceLabel())
        var armed = true
        controller.onReady = {
            if (armed) { armed = false; controller.submitPairing(qr) }
        }
        controller.onSasReady = { sas ->
            runOnUiThread { if (!finished) showProgress("Compara este código con el del PC", "Pulsa «Sí, autorizar» en el PC cuando coincida.", sas) }
        }
        controller.onOutcome = { outcome -> runOnUiThread { onOutcome(qr, outcome) } }
        job = scope.launch { c.runForever() }
    }

    private fun onOutcome(qr: QrPairingPayload, outcome: PairingOutcome) {
        if (finished) return
        when (outcome) {
            is PairingOutcome.Failure -> { cancelPairing(); showFailure(outcome.reason) }
            is PairingOutcome.Success -> {
                finished = true
                cancelPairing() // close this connection BEFORE the normal link opens its own
                connectQuick.adoptHost(PairedHost(outcome.hostId, outcome.hostName, qr.hostPub, qr.relay, outcome.permissions, System.currentTimeMillis(), qr.relays))
                toast("Vinculado con «${outcome.hostName}»")
                finish()
            }
        }
    }

    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()

    private fun cancelPairing() {
        job?.cancel(); job = null
        client = null
    }

    private fun deviceLabel(): String = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" ").take(60).ifBlank { "Teléfono Android" }

    private fun friendly(reason: String): String = when {
        reason == "invalid_qr" -> "Ese código no es de ConnectQuick. Escanea el que muestra el PC."
        reason.contains("denied", true) || reason.contains("rejected", true) -> "El PC rechazó la vinculación."
        reason.contains("expired", true) -> "El código caducó. Genera uno nuevo en el PC."
        reason.contains("invalid_host_signature") -> "No se pudo verificar la respuesta del PC. Por seguridad no se vinculó."
        reason.contains("rate", true) -> "Demasiados intentos. Espera un momento."
        else -> "Comprueba que el PC está conectado a internet y vuelve a escanear. ($reason)"
    }

    override fun onDestroy() {
        cancelPairing()
        scope.cancel()
        super.onDestroy()
    }

    private companion object { const val REQ_SCAN = 41 }
}
