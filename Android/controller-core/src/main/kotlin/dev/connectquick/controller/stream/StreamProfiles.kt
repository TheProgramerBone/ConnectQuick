package dev.connectquick.controller.stream

/**
 * What the person wants to look at, translated into the ceiling sent with `stream.start`. The PC
 * still caps it with its own limits and adapts down when the network is tight.
 */
enum class StreamProfile(val label: String, val hint: String, val request: StreamRequest) {
    TEXT("Texto", "Máxima nitidez para leer; movimiento menos fluido", StreamRequest(1920, 1080, 15, 5000)),
    NORMAL("Normal", "Equilibrio entre nitidez y fluidez", StreamRequest(1280, 720, 30, 2500)),
    VIDEO("Video", "Movimiento fluido para videos y animaciones", StreamRequest(1920, 1080, 30, 8000)),
    SAVER("Ahorro", "Pocos datos, para redes móviles", StreamRequest(854, 480, 20, 900));

    fun next(): StreamProfile = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): StreamProfile = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}
