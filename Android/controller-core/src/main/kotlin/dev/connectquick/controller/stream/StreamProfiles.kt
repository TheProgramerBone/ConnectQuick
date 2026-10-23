package dev.connectquick.controller.stream

/**
 * What the person wants to look at, translated into the ceiling sent with `stream.start`. The PC
 * still caps it with its own limits and adapts down when the network is tight.
 */
enum class StreamProfile(val label: String, val hint: String, val request: StreamRequest) {
    AUTO("Auto", "Elige según la red y baja sola si hay retraso", StreamRequest(1920, 1080, 30, 6000)),
    TEXT("Texto", "Máxima nitidez para leer; movimiento menos fluido", StreamRequest(1920, 1080, 15, 5000)),
    NORMAL("Normal", "Equilibrio entre nitidez y fluidez", StreamRequest(1280, 720, 30, 2500)),
    VIDEO("Video", "Movimiento fluido para videos y animaciones", StreamRequest(1920, 1080, 30, 8000)),
    GAME("Juego", "60 fps y mínima latencia (el PC debe permitir 60 fps)", StreamRequest(1280, 720, 60, 6000)),
    SAVER("Ahorro", "Pocos datos, para redes móviles", StreamRequest(854, 480, 20, 900));

    fun next(): StreamProfile = entries[(ordinal + 1) % entries.size]

    /** Low-latency profiles flush pointer motion more often. */
    val lowLatency: Boolean get() = this == GAME

    companion object {
        fun fromName(name: String?): StreamProfile = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * The "Auto" profile. Starts from the network type (metered/mobile = save data, otherwise sharp and
 * fluid) and steps down one level after a sustained high round-trip time; it never steps back up by
 * itself during a stream (oscillating quality is worse than slightly lower quality), only when the
 * network type changes. The PC's own loss-based adaptation keeps working underneath.
 */
class AutoQuality(private val highRttMs: Int = 250, private val samplesToStepDown: Int = 3) {
    private val ladder = listOf(StreamProfile.VIDEO.request, StreamProfile.NORMAL.request, StreamProfile.SAVER.request)
    private var level = 0
    private var metered: Boolean? = null
    private var slowSamples = 0

    val current: StreamRequest get() = ladder[level]

    /** Network changed. Returns the new ceiling if it differs from the current one, else null. */
    fun onNetwork(isMetered: Boolean): StreamRequest? {
        if (metered == isMetered) return null
        metered = isMetered
        slowSamples = 0
        val before = level
        level = if (isMetered) ladder.lastIndex else 0
        return if (level != before) ladder[level] else null
    }

    /** A `stream.stats` arrived. Returns a lower ceiling to restart with, or null to keep going. */
    fun onStats(rttMs: Int?): StreamRequest? {
        if (rttMs == null || rttMs <= 0) return null
        if (rttMs < highRttMs) { slowSamples = 0; return null }
        if (++slowSamples < samplesToStepDown || level == ladder.lastIndex) return null
        slowSamples = 0
        level++
        return ladder[level]
    }
}
