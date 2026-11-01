package com.blissplayer.music.playback

/** Platform-independent output snapshot, shared by the Android adapter and tests. */
data class AudioOutput(val id: String, val name: String, val wireless: Boolean = false)

object OutputRouting {
    const val SPEAKER = "Phone speaker / wired" // keep existing speaker presets
    val speaker = AudioOutput("speaker", SPEAKER)

    fun select(available: List<AudioOutput>, routed: List<AudioOutput>?): AudioOutput {
        // Never trust a cached route to a device which no longer exists.
        if (routed != null) {
            return routed.firstNotNullOfOrNull { route -> available.find { it.id == route.id } }
                ?: speaker
        }
        // Older Android versions cannot query the selected media route.
        return available.firstOrNull { it.wireless }
            ?: available.firstOrNull { it.id != speaker.id }
            ?: speaker
    }
}

/** Switching never copies the car's boost over an already remembered speaker. */
internal class OutputPresetMemory(
    private val read: (String) -> Float?,
    private val write: (String, Float) -> Unit,
    initialBoost: Float
) {
    var device = OutputRouting.SPEAKER
        private set
    var boost = initialBoost.coerceIn(0.01f, 20f)
        private set

    init {
        // A new install must have a safe fallback even if the first route is a car.
        if (read(device) == null) write(device, 1f)
    }

    fun select(name: String, restore: Boolean): Boolean {
        if (name == device) return false
        write(device, boost)
        device = name
        val remembered = read(name)
        if (restore && remembered != null) boost = remembered.coerceIn(0.01f, 20f)
        write(name, boost) // new output automatically inherits the current setting
        return true
    }

    fun setBoost(value: Float) {
        boost = if (value.isFinite()) value.coerceIn(0.01f, 20f) else 1f
        write(device, boost)
    }
}
