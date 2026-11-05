package com.blissplayer.desktop

import com.blissplayer.core.AudioFx
import com.blissplayer.core.EqState

/**
 * Shared EQ state model; desktop DSP is best-effort (D-7a): presets and the
 * volume/normalization path are honored, VLC's engine does the rendering.
 * Android keeps its Equalizer/LoudnessEnhancer behavior; nothing is
 * re-tuned to fake parity.
 */
class DesktopFx(private val settings: DesktopSettings, private val applyVolume: (Double) -> Unit) : AudioFx {
    private var boostMult = settings.getFloat("boost_mult", 1f).toDouble()
    private var preampDb = settings.getFloat("mixer_preamp", 0f)
    private var bands = List(5) { 0f }

    fun setBoost(mult: Double, warn: (String) -> Unit = {}) {
        boostMult = mult.coerceIn(0.01, 5.0)
        settings.putFloat("boost_mult", boostMult.toFloat())
        if (boostMult > 2.0) warn("Above 200%: loud passages may clip or distort.")
        applyVolume(boostMult)
    }

    fun boost(): Double = boostMult

    fun setLoudnessGain(mult: Double) {
        // Loudness gain multiplies under the user boost, same as Android.
        applyVolume(boostMult * mult)
    }

    override fun apply(state: EqState) {
        preampDb = state.preampDb
        bands = state.bandsDb
        settings.putFloat("mixer_preamp", preampDb)
        // Gentle loudness approximation on the volume path; bands are state
        // kept for parity even where VLC renders them its own way.
        val tilt = 1.0 + (preampDb / 40.0).coerceIn(-0.5, 0.5)
        applyVolume(boostMult * tilt)
    }

    override fun current(): EqState = EqState(true, bands.toList(), preampDb)

    override fun describe(state: EqState): String {
        val p = state.preampDb
        val shape = when {
            state.bandsDb.isEmpty() -> "flat"
            state.bandsDb.first() > 2f -> "bass-heavy"
            state.bandsDb.last() > 2f -> "bright"
            else -> "shaped"
        }
        val base = if (p == 0f && shape == "flat") "Flat: nothing boosted, nothing cut."
        else "Overall ${if (p >= 0) "+" else ""}${"%.1f".format(p)} dB, $shape."
        return "$base Big boosts can clip on loud songs."
    }
}
