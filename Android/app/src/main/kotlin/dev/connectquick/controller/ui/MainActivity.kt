package dev.connectquick.controller.ui

import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.connectquick.controller.connectQuick
import dev.connectquick.controller.session.ControllerHub
import dev.connectquick.controller.session.LinkAction
import dev.connectquick.controller.session.LinkState

/**
 * Home. Without a paired PC it explains what to do; with one it shows the real link state and a tile
 * per capability, each of which says WHY it is unavailable instead of silently graying out.
 */
class MainActivity : HubActivity() {
    private data class Entry(val tile: FeatureTile, val permission: String?, val ready: String, val target: Class<*>)

    private var banner: Banner? = null
    private val entries = mutableListOf<Entry>()
    private var pcSummary: android.widget.TextView? = null

    override fun buildContent(hub: ControllerHub?) {
        entries.clear()
        banner = null
        pcSummary = null
        content.addView(Ui.title(this, "ConnectQuick"), Ui.lp(this, bottom = 4))
        content.addView(Ui.dim(this, "Controla tu PC desde el teléfono, con cifrado de extremo a extremo."), Ui.lp(this, bottom = 18))

        if (hub == null) {
            content.addView(
                Ui.card(
                    this,
                    Ui.heading(this, "Todavía no hay ningún PC vinculado"),
                    Ui.dim(this, "1. Abre ConnectQuick en tu PC con Windows.\n2. En el PC elige «Vincular un teléfono».\n3. Aquí pulsa el botón y escanea el código QR.").apply { setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 14)) },
                    Ui.button(this, "Vincular un PC", Ui.ButtonStyle.PRIMARY) { startActivity(Intent(this, PairActivity::class.java)) },
                ),
                Ui.lp(this, bottom = 14),
            )
            content.addView(appearanceCard(), Ui.lp(this, bottom = 14))
            return
        }

        val b = Banner(this) { action ->
            when (action) {
                LinkAction.CONNECT, LinkAction.RETRY -> hub.retryNow()
                LinkAction.REPAIR -> startActivity(Intent(this, PairActivity::class.java))
                LinkAction.NONE -> Unit
            }
        }
        banner = b
        content.addView(b, Ui.lp(this, bottom = 16))

        content.addView(Ui.heading(this, "Qué puedes hacer"), Ui.lp(this, bottom = 8))
        val items = listOf(
            Entry(FeatureTile(this, GlyphView.Glyph.TOUCHPAD, "Touchpad"), "mouse", "Mover y hacer clic", TouchpadActivity::class.java),
            Entry(FeatureTile(this, GlyphView.Glyph.SCREEN, "Pantalla"), "remote_desktop", "Ver y controlar", ScreenActivity::class.java),
            Entry(FeatureTile(this, GlyphView.Glyph.KEYBOARD, "Teclado"), "keyboard", "Escribir en el PC", TouchpadActivity::class.java),
            Entry(FeatureTile(this, GlyphView.Glyph.CLIPBOARD, "Portapapeles"), "clipboard", "Copiar y pegar", ClipboardActivity::class.java),
            Entry(FeatureTile(this, GlyphView.Glyph.MEDIA, "Multimedia"), "media", "Volumen y reproducción", MediaActivity::class.java),
            Entry(FeatureTile(this, GlyphView.Glyph.FOLDER, "Archivos"), "files", "Enviar y recibir", FilesActivity::class.java),
            Entry(FeatureTile(this, GlyphView.Glyph.POWER, "Energía"), "power", "Bloquear o apagar", PowerActivity::class.java),
        )
        entries += items
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, e ->
                val keyboardTile = e.tile
                keyboardTile.setOnClickListener {
                    val intent = Intent(this, e.target)
                    if (e.permission == "keyboard") intent.putExtra(TouchpadActivity.EXTRA_OPEN_KEYBOARD, true)
                    startActivity(intent)
                }
                row.addView(keyboardTile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 0) marginEnd = Ui.dp(this@MainActivity, 6) else marginStart = Ui.dp(this@MainActivity, 6)
                })
            }
            if (pair.size == 1) row.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
            content.addView(row, Ui.lp(this, bottom = 12))
        }

        val summary = Ui.dim(this, "")
        pcSummary = summary
        content.addView(
            Ui.card(
                this,
                Ui.heading(this, hub.host.hostName),
                summary,
                Ui.spacer(this, 10),
                Ui.button(this, "Vincular otro PC") { startActivity(Intent(this, PairActivity::class.java)) },
                Ui.spacer(this, 8),
                Ui.button(this, "Olvidar este PC", Ui.ButtonStyle.DANGER) {
                    confirm(
                        "Olvidar este PC",
                        "Este teléfono dejará de recordar «${hub.host.hostName}». Para que además pierda el acceso, revoca también este teléfono desde ConnectQuick en el PC.",
                        "Olvidar", danger = true,
                    ) { connectQuick.forgetHost(); recreate() }
                },
            ),
            Ui.lp(this, top = 6, bottom = 8),
        )
        content.addView(appearanceCard(), Ui.lp(this, top = 6, bottom = 8))
        content.gravity = Gravity.TOP
    }

    /** Dark/light switch. Shown with or without a paired PC; applies at once by recreating the screen. */
    private fun appearanceCard(): LinearLayout {
        fun choose(light: Boolean) {
            if (Ui.light == light) return
            Ui.light = light
            connectQuick.store.saveLightTheme(light)
            recreate()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dark = Ui.button(this, "Oscuro", if (Ui.light) Ui.ButtonStyle.SECONDARY else Ui.ButtonStyle.PRIMARY) { choose(false) }
        val light = Ui.button(this, "Claro", if (Ui.light) Ui.ButtonStyle.PRIMARY else Ui.ButtonStyle.SECONDARY) { choose(true) }
        row.addView(dark, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@MainActivity, 6) })
        row.addView(light, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(this@MainActivity, 6) })
        return Ui.card(this, Ui.heading(this, "Apariencia"), Ui.dim(this, "Oscuro es el predeterminado."), Ui.spacer(this, 8), row)
    }

    override fun render() {
        val h = hub ?: return
        val p = presentation() ?: return
        banner?.update(p)
        entries.forEach { e -> e.tile.update(unavailableReason(e.permission), e.ready) }
        val granted = h.host.permissions.joinToString(" · ") { permissionName(it) }
        pcSummary?.text = "Permisos concedidos al vincular: ${if (granted.isEmpty()) "ninguno" else granted}.\nEl PC puede limitarlos en cualquier momento."
    }

    private fun permissionName(p: String) = when (p) {
        "remote_desktop" -> "pantalla"; "mouse" -> "ratón"; "keyboard" -> "teclado"; "clipboard" -> "portapapeles"
        "files" -> "archivos"; "media" -> "multimedia"; "power" -> "energía"; else -> p
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the app after a long pause: if the link gave up while away, try again right now.
        hub?.let { if (it.state.phase == LinkState.Phase.STOPPED) it.start() }
    }
}
