package com.blissplayer.music.playback

import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import kotlin.math.pow

/** Session-scoped system EQ. Disabled/flat by default, never changes audio files. */
class ToneControl(private val prefs: SharedPreferences) {
    data class Band(val index: Int, val hz: Int, val min: Int, val max: Int, val level: Int)
    private var effect: Equalizer? = null
    var problem: String? = "Play a song first so Android can open its audio session."
        private set
    val enabled: Boolean get() = prefs.getBoolean("tone_on", false)
    val headroom: Boolean get() = prefs.getBoolean("tone_headroom", true)
    val preampDb: Int get() = prefs.getInt("tone_preamp", 0).coerceIn(-24, 12)

    fun attach(session: Int) {
        release()
        problem = null
        var candidate: Equalizer? = null
        try {
            candidate = Equalizer(0, session)
            effect = candidate
            val available = bands()
            check(available.isNotEmpty()) { "The audio output reported no equalizer bands." }
            available.forEach { band ->
                candidate.setBandLevel(band.index.toShort(),
                    prefs.getInt("tone_band_${band.hz}", 0).coerceIn(band.min, band.max).toShort())
            }
            candidate.enabled = enabled
        } catch (e: Exception) {
            runCatching { candidate?.release() }
            effect = null
            problem = "This audio output does not support a live equalizer (${e.message ?: "unavailable"})."
        }
    }

    fun bands(): List<Band> = runCatching {
        val eq = effect ?: return emptyList()
        val range = eq.bandLevelRange
        (0 until eq.numberOfBands.toInt()).map { i ->
            Band(i, eq.getCenterFreq(i.toShort()) / 1000, range[0].toInt(), range[1].toInt(),
                eq.getBandLevel(i.toShort()).toInt())
        }
    }.getOrElse {
        problem = "Could not read this output's equalizer: ${it.message}"
        emptyList()
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("tone_on", on).apply()
        runCatching { effect?.enabled = on }.onFailure { problem = it.message }
    }

    fun setBand(index: Int, millibels: Int) {
        val band = bands().firstOrNull { it.index == index } ?: return
        val value = millibels.coerceIn(band.min, band.max)
        runCatching { effect?.setBandLevel(index.toShort(), value.toShort()) }
            .onSuccess { prefs.edit().putInt("tone_band_${band.hz}", value).apply() }
            .onFailure { problem = "Could not change frequency: ${it.message}" }
    }

    fun setHeadroom(on: Boolean) { prefs.edit().putBoolean("tone_headroom", on).apply() }
    fun setPreamp(db: Int) { prefs.edit().putInt("tone_preamp", db.coerceIn(-24, 12)).apply() }

    fun reset() {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("tone_band_") }.forEach { remove(it) }
            putInt("tone_preamp", 0)
            putBoolean("tone_on", false)
            putBoolean("tone_headroom", true)
        }.apply()
        bands().forEach { runCatching { effect?.setBandLevel(it.index.toShort(), 0) } }
        runCatching { effect?.enabled = false }
    }

    fun gain(): Float = if (!enabled || effect == null) 1f else ToneMath.gain(
        preampDb, if (headroom) (bands().maxOfOrNull { it.level } ?: 0).coerceAtLeast(0) else 0)

    fun release() {
        runCatching { effect?.release() }
        effect = null
        problem = "The audio session is not ready. Play a song, then reopen the mixer."
    }
}

internal object ToneMath {
    fun gain(preampDb: Int, headroomMillibels: Int): Float =
        10.0.pow((preampDb - headroomMillibels / 100.0) / 20.0).toFloat()
}
