package dev.connectquick.controller.ui

import android.app.Activity
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import dev.connectquick.controller.files.AndroidFileSource
import dev.connectquick.controller.files.RemoteFile
import dev.connectquick.controller.files.TransferDirection
import dev.connectquick.controller.files.TransferProgress
import dev.connectquick.controller.files.TransferStatus
import dev.connectquick.controller.session.ControllerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Send files to the PC (they land in the PC's own inbox folder) and fetch files the PC shares from its
 * "Para compartir" folder. Neither side ever names a path: only names, sizes and verified bytes move.
 */
class FilesActivity : HubActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var banner: Banner? = null
    private var send: android.widget.Button? = null
    private var refresh: android.widget.Button? = null
    private var status: TextView? = null
    private var shared: LinearLayout? = null
    private var transfers: LinearLayout? = null
    private val rows = linkedMapOf<String, TextView>()
    private var wired: Any? = null

    override fun buildContent(hub: ControllerHub?) {
        rows.clear()
        content.addView(backRow("Archivos"), Ui.lp(this, bottom = 12))
        banner = Banner(this) { hub?.retryNow() }.also { content.addView(it, Ui.lp(this, bottom = 12)) }

        send = Ui.button(this, "Enviar un archivo al PC", Ui.ButtonStyle.PRIMARY) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQ_PICK)
        }
        status = Ui.dim(this, "Los archivos del PC se guardan en Descargas/ConnectQuick de este teléfono.")
        content.addView(Ui.card(this, Ui.heading(this, "Del teléfono al PC"), Ui.spacer(this, 8), send!!), Ui.lp(this, bottom = 12))

        refresh = Ui.button(this, "Ver archivos que comparte el PC") { listShared() }
        shared = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(Ui.card(this, Ui.heading(this, "Del PC al teléfono"), Ui.spacer(this, 8), refresh!!, Ui.spacer(this, 8), shared!!), Ui.lp(this, bottom = 12))

        transfers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(Ui.card(this, Ui.heading(this, "Transferencias"), Ui.spacer(this, 6), transfers!!), Ui.lp(this, bottom = 12))
        content.addView(status, Ui.lp(this, bottom = 12))
    }

    override fun render() {
        presentation()?.let { banner?.update(it) }
        val reason = unavailableReason("files")
        listOfNotNull(send, refresh).forEach { it.isEnabled = reason == null; it.alpha = if (reason == null) 1f else 0.5f }
        if (reason != null) status?.text = reason
        val h = hub ?: return
        // (Re)connect progress callbacks to the clients of the current session.
        val up = h.uploader; val down = h.downloader
        if (wired !== (up to down)) {
            wired = up to down
            up?.onProgress = { p -> runOnUiThread { progress(p) } }
            down?.onProgress = { p -> runOnUiThread { progress(p) } }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val uploader = hub?.uploader ?: return say("Sin conexión con el PC.")
        scope.launch {
            val source = kotlinx.coroutines.withContext(Dispatchers.IO) { AndroidFileSource.from(contentResolver, uri) }
                ?: return@launch say("No se pudo leer ese archivo.")
            say("Preparando «${source.name}»…")
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) { uploader.upload(source) }
            say(
                if (result.ok) "«${source.name}» llegó al PC${result.savedAs?.let { " como «$it»" } ?: ""}."
                else "No se envió: ${reasonText(result.reason)}",
            )
        }
    }

    private fun listShared() {
        val d = hub?.downloader ?: return
        say("Consultando la carpeta compartida del PC…")
        scope.launch {
            val files = kotlinx.coroutines.withContext(Dispatchers.IO) { d.list() }
            val box = shared ?: return@launch
            box.removeAllViews()
            when {
                files == null -> say("El PC no respondió.")
                files.isEmpty() -> say("La carpeta «Para compartir» del PC está vacía. Copia allí lo que quieras traer.")
                else -> { say("${files.size} archivo(s) disponibles."); files.forEach { box.addView(fileRow(it), Ui.lp(this@FilesActivity, bottom = 6)) } }
            }
        }
    }

    private fun fileRow(f: RemoteFile): android.view.View =
        Ui.button(this, "${f.name}  ·  ${size(f.size)}") { hub?.downloader?.request(f.name); say("Pidiendo «${f.name}»…") }.also {
            it.contentDescription = "Traer ${f.name}, ${size(f.size)}"
            it.isAllCaps = false
        }

    private fun progress(p: TransferProgress) {
        val box = transfers ?: return
        val view = rows.getOrPut(p.transferId) { Ui.dim(this, "").also { box.addView(it, 0, Ui.lp(this, bottom = 6)) } }
        val dir = if (p.direction == TransferDirection.UPLOAD) "→ PC" else "← PC"
        val name = p.name.ifBlank { "archivo" }
        view.text = when (p.status) {
            TransferStatus.RUNNING -> "$dir  $name  ${percent(p)}"
            TransferStatus.DONE -> "$dir  $name  completado" + (p.savedAs?.let { " (guardado como $it)" } ?: "")
            TransferStatus.CANCELLED -> "$dir  $name  cancelado"
            TransferStatus.FAILED -> "$dir  $name  falló: ${reasonText(p.reason)}"
        }
        view.setTextColor(if (p.status == TransferStatus.FAILED) Ui.DANGER else if (p.status == TransferStatus.DONE) Ui.OK else Ui.DIM)
    }

    private fun percent(p: TransferProgress) = if (p.total > 0) "${(p.bytes * 100 / p.total).toInt()} % (${size(p.bytes)} de ${size(p.total)})" else size(p.bytes)

    private fun size(n: Long) = when {
        n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
        n >= 1L shl 10 -> "%d KB".format(n shr 10)
        else -> "$n B"
    }

    private fun reasonText(code: String?) = when (code) {
        "too_large" -> "supera el límite del PC"
        "no_space", "disk_full" -> "no hay espacio suficiente"
        "declined" -> "se rechazó"
        "busy" -> "ya hay otra transferencia en curso"
        "duplicate", "exists" -> "el PC ya tiene ese archivo"
        "forbidden", "disabled_by_host" -> "el PC no permite archivos"
        "hash_mismatch" -> "el contenido no coincidía; se descartó"
        "not_found" -> "el archivo ya no está en el PC"
        "unsafe_destination" -> "el PC rechazó la carpeta de destino"
        null, "" -> "error desconocido"
        else -> code
    }

    private fun say(text: String) { status?.text = text; status?.announceForAccessibility(text) }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object { const val REQ_PICK = 52 }
}
