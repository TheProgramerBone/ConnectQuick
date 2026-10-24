package dev.connectquick.controller.stream

import dev.connectquick.controller.session.Tone

data class StreamPresentation(val headline: String, val detail: String, val tone: Tone, val showVideo: Boolean)

/**
 * Words for every [StreamStatus], in Spanish. Each failure names its real cause (the PC refused, the
 * screen is locked, the network blocked the media path) so the person knows what to do next; a stream
 * is only ever called "en directo" once media is actually flowing.
 */
object StreamTexts {
    fun of(s: StreamStatus): StreamPresentation = when (s.phase) {
        StreamStatus.Phase.IDLE -> StreamPresentation("Pantalla detenida", "Pulsa Iniciar para ver el PC.", Tone.IDLE, false)
        StreamStatus.Phase.REQUESTING -> if (s.reason == "reconnecting")
            StreamPresentation("Recuperando el vídeo…", "Se cortó la conexión (¿cambio de red?). Se reintenta sola.", Tone.BUSY, false)
        else StreamPresentation("Pidiendo la pantalla al PC…", "El PC prepara la captura.", Tone.BUSY, false)
        StreamStatus.Phase.NEGOTIATING -> StreamPresentation("Estableciendo la conexión de vídeo…", "Buscando el mejor camino entre el teléfono y el PC.", Tone.BUSY, false)
        StreamStatus.Phase.STREAMING -> {
            val blocked = s.blocked
            if (blocked != null) StreamPresentation(blockedHeadline(blocked), blockedDetail(blocked), Tone.WARN, true)
            else {
                val st = s.stats
                val detail = buildString {
                    if (s.width > 0) append("${s.width}×${s.height}")
                    if (st != null && st.fps > 0) append(" · ${st.fps.toInt()} fps")
                    if (st != null && st.bitrateKbps > 0) append(" · ${st.bitrateKbps} kbps")
                    if (st != null && st.rttMs >= 0) append(" · ${st.rttMs} ms")
                }.ifBlank { "Vídeo en directo." }
                StreamPresentation("En directo", detail, Tone.OK, true)
            }
        }
        StreamStatus.Phase.STOPPED -> StreamPresentation("Pantalla detenida", stoppedDetail(s.reason), Tone.IDLE, false)
        StreamStatus.Phase.ERROR -> StreamPresentation("No se pudo mostrar la pantalla", errorDetail(s.reason), Tone.BAD, false)
    }

    private fun blockedHeadline(code: String) = when (code) {
        "secure_desktop" -> "El PC muestra una pantalla protegida"
        "no_display" -> "El PC no tiene pantalla activa"
        else -> "No se puede leer la pantalla ahora"
    }

    private fun blockedDetail(code: String) = when (code) {
        "secure_desktop" -> "Es el bloqueo de Windows o una petición de administrador (UAC). Windows no permite verla ni controlarla desde aquí; hazlo en el propio PC."
        "no_display" -> "Conecta un monitor o activa la pantalla del PC."
        else -> "Se reanudará sola cuando el PC permita capturarla."
    }

    private fun stoppedDetail(reason: String?) = when (reason) {
        "stopped_by_user" -> "Detuviste la transmisión."
        "stopped_by_phone" -> "Detuviste la transmisión."
        "session_ended" -> "Terminó la sesión con el PC."
        "replaced" -> "Otra petición sustituyó a esta."
        null, "" -> "La transmisión terminó."
        else -> "La transmisión terminó (${reason})."
    }

    private fun errorDetail(reason: String?) = when (reason) {
        "forbidden" -> "Este teléfono no tiene permiso para ver la pantalla."
        "disabled_by_host" -> "El PC tiene desactivada la visualización de pantalla."
        "video_unavailable" -> "Este PC no puede emitir vídeo (falta el componente de captura o codificación)."
        "session_closed" -> "Se perdió la conexión con el PC."
        "negotiation_failed", "bad_answer", "offer_failed" -> "No se pudo acordar la conexión de vídeo. Inténtalo de nuevo."
        "connection_lost" -> "Se cortó el vídeo. Revisa la red y vuelve a iniciar."
        "ice_failed_no_relay" ->
            "La red no permitió la conexión directa y el servidor no ofrece relevo de vídeo (TURN). En la misma Wi-Fi suele funcionar; para otras redes instala ConnectQuick en el PC con «acceso desde Internet» (Tailscale Funnel), que incluye el relevo."
        "ice_failed" -> "No se logró conectar el vídeo, ni siquiera con el servidor de relevo. Revisa la red del PC y del teléfono."
        null, "" -> "Error desconocido."
        else -> "El PC respondió: $reason."
    }
}
