package com.blissplayer.core

/**
 * Shared loudness math: the gain formula is identical on Android and Linux.
 * Measurement (MediaExtractor vs ffmpeg PCM) and application (player volume
 * vs VLC volume) differ per platform; the decision of *how much* never does.
 */
object LoudnessMath {

    /**
     * Strength blends full correction toward doing nothing: 100 percent
     * lands every song on the average, 0 percent leaves volumes alone,
     * past 100 percent pushes past the average for stubborn libraries.
     * `full` is avg/rms for one song. Result is clamped to an 18 dB window.
     */
    fun gain(full: Double, strength: Float): Double {
        val k = strength.coerceIn(0f, 2f).toDouble()
        return (1.0 + (full - 1.0) * k).coerceIn(0.125, 8.0)
    }

    /** Per-song gain multipliers for a measured rms map. Empty in, empty out. */
    fun gains(rms: Map<Long, Double>, strength: Float): Map<Long, Double> {
        if (rms.isEmpty()) return emptyMap()
        val avg = rms.values.average()
        return rms.mapValues { (_, r) ->
            gain(if (r > 0) avg / r else 1.0, strength)
        }
    }

    /** raised/lowered counts for progress copy ("up N, down M"). */
    fun counts(gains: Map<Long, Double>): Pair<Int, Int> {
        var raised = 0
        var lowered = 0
        gains.values.forEach {
            if (it > 1.03) raised++ else if (it < 0.97) lowered++
        }
        return raised to lowered
    }
}
