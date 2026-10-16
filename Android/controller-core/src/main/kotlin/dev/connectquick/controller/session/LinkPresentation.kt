package dev.connectquick.controller.session

import dev.connectquick.controller.net.SignalingState

/** How a state should feel on screen: drives colour AND shape/wording, so it never relies on colour alone. */
enum class Tone { OK, BUSY, WARN, BAD, IDLE }

/** What the screen offers the person as the one obvious next step. */
enum class LinkAction { NONE, CONNECT, RETRY, REPAIR }

data class LinkPresentation(
    val headline: String,
    val detail: String,
    val tone: Tone,
    val action: LinkAction,
    /** True only when control screens may send input; everything else shows an honest "not connected" state. */
    val canControl: Boolean,
)

/**
 * Words for every [LinkState], in Spanish. A pure function so the wording (and the rule "never say
 * connected unless a verified session exists") is unit-tested rather than scattered across screens.
 */
object LinkTexts {
    fun of(state: LinkState, pcName: String): LinkPresentation = when (state.phase) {
        LinkState.Phase.STOPPED ->
            LinkPresentation("Desconectado", "Pulsa Conectar para controlar «$pcName».", Tone.IDLE, LinkAction.CONNECT, false)
        LinkState.Phase.CONNECTING_SIGNALING -> LinkPresentation(
            when (state.signaling) {
                SignalingState.AUTHENTICATING -> "Verificando tu identidad…"
                else -> "Conectando con el servidor…"
            },
            "Esto suele tardar un par de segundos.", Tone.BUSY, LinkAction.NONE, false,
        )
        LinkState.Phase.OPENING_SESSION ->
            LinkPresentation("Abriendo sesión segura…", "Comprobando que «$pcName» es el PC que vinculaste.", Tone.BUSY, LinkAction.NONE, false)
        LinkState.Phase.HOST_OFFLINE ->
            LinkPresentation("«$pcName» no está disponible", "Enciende el PC y abre ConnectQuick. Se conectará solo cuando aparezca.", Tone.WARN, LinkAction.NONE, false)
        LinkState.Phase.ONLINE ->
            LinkPresentation("Conectado a «$pcName»", "Sesión cifrada de extremo a extremo.", Tone.OK, LinkAction.NONE, true)
        LinkState.Phase.RECONNECTING -> LinkPresentation(
            "Reconectando…",
            buildString {
                append(reasonText(state.reason))
                if (state.attempt > 0) append(" Intento ${state.attempt} de ${RemoteLink.MAX_ATTEMPTS}")
                if (state.nextRetryInMs > 0) append(" · en ${(state.nextRetryInMs + 999) / 1000} s")
                append('.')
            }.replace("..", "."),
            Tone.WARN, LinkAction.RETRY, false,
        )
        LinkState.Phase.REVOKED ->
            LinkPresentation("Acceso revocado", "«$pcName» ya no autoriza este teléfono. Vincúlalo de nuevo para volver a usarlo.", Tone.BAD, LinkAction.REPAIR, false)
        LinkState.Phase.FAILED -> LinkPresentation("No se pudo conectar", failureText(state.reason, pcName), Tone.BAD, LinkAction.RETRY, false)
    }

    private fun reasonText(reason: String?): String = when {
        reason == null || reason.isEmpty() -> "La conexión se interrumpió."
        reason == "signaling_lost" || reason == "network_lost" -> "Se perdió la red."
        reason == "host_unresponsive" -> "El PC dejó de responder."
        reason == "replaced" -> "Otra sesión sustituyó a esta."
        reason == "idle_timeout" -> "La sesión estuvo inactiva demasiado tiempo."
        reason == "host_shutdown" -> "ConnectQuick se cerró en el PC."
        reason == "protocol_violations" -> "Se recibieron mensajes no válidos."
        reason == "connect_timeout" || reason == "signaling_unavailable" -> "No hubo respuesta del servidor."
        else -> "La conexión se interrumpió."
    }

    private fun failureText(reason: String?, pcName: String): String = when {
        reason == "user_denied" -> "«$pcName» rechazó la conexión. Pídele a quien esté frente al PC que la permita."
        reason == "host_not_accepting" -> "«$pcName» tiene las conexiones en pausa."
        reason == "host_busy" -> "«$pcName» ya está siendo controlado desde otro teléfono."
        reason != null && reason.startsWith("too_many_attempts") -> "Tras varios intentos no hubo forma de conectar. Revisa la red y vuelve a intentarlo."
        reason == "key_confirmation_failed" -> "No se pudo verificar la sesión segura. Si se repite, vuelve a vincular el PC."
        else -> "Vuelve a intentarlo en unos segundos."
    }
}
