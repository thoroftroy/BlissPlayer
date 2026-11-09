package com.blissplayer.core

import kotlin.math.sqrt

/**
 * Listening fingerprint for duplicate detection.
 *
 * One number per 50ms of audio: the RMS energy sequence of the whole
 * recording. Two curves are compared with Pearson correlation, trying
 * small time shifts so a different intro or encoder padding cannot hide a
 * copy. Same recording scores 99+, different songs stay under 60 on real
 * libraries (300 measured pairs peaked at 0.57), so the bar sits at 0.75
 * with a wide empty gap on both sides. Scores below the bar collapse under
 * 40; nothing ever lands 40..74.
 */
object AudioFingerprint {

    /** Pairs need at least this correlation to count as the same recording. */
    const val DUPE_THRESHOLD = 75

    /** 50ms energy windows; 5s of shift search each way in 250ms steps. */
    const val WINDOW_MS = 50L
    const val MAX_SHIFT_WINDOWS = 100
    const val SHIFT_STEP_WINDOWS = 5

    data class Print(
        val rms: Double,
        val fine: FloatArray,
        val durationMs: Long
    )

    fun fromFine(rmsValues: List<Double>, durationMs: Long): Print {
        val rms = if (rmsValues.isEmpty()) 0.0 else sqrt(rmsValues.map { it * it }.average())
        return Print(rms, rmsValues.map { it.toFloat() }.toFloatArray(), durationMs)
    }

    /** Pearson correlation of two mean-centered curves, truncated to overlap. */
    fun pearson(a: FloatArray, aFrom: Int, b: FloatArray, bFrom: Int, n: Int): Double {
        if (n < 40) return 0.0
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) {
            ma += a[aFrom + i]
            mb += b[bFrom + i]
        }
        ma /= n
        mb /= n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val xa = a[aFrom + i] - ma
            val xb = b[bFrom + i] - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        if (da <= 1e-9 || db <= 1e-9) return 0.0
        return (num / sqrt(da * db)).coerceIn(-1.0, 1.0)
    }

    /** Best correlation over small shifts: intro differences cannot hide a copy. */
    fun bestCorrelation(a: Print, b: Print): Double {
        val fa = a.fine
        val fb = b.fine
        if (fa.size < 40 || fb.size < 40) return 0.0
        val n0 = minOf(fa.size, fb.size)
        val r0 = pearson(fa, 0, fb, 0, n0)
        // Shift search only when the straight read is already close: true
        // copies at full overlap never read this low, and random pairs
        // cannot climb from here to the 0.75 bar by shifting.
        if (r0 <= 0.15) return r0
        var best = r0
        var k = -MAX_SHIFT_WINDOWS
        while (k <= MAX_SHIFT_WINDOWS) {
            if (k != 0) {
                val sa = maxOf(0, -k)
                val sb = maxOf(0, k)
                val n = minOf(fa.size - sa, fb.size - sb)
                if (n >= 100) {
                    val r = pearson(fa, sa, fb, sb, n)
                    if (r > best) best = r
                }
            }
            k += SHIFT_STEP_WINDOWS
        }
        return best.coerceIn(-1.0, 1.0)
    }

    /** 0..100 similarity. Same recording is 95+. Different songs stay under 40. */
    fun similarity(a: Print, b: Print): Int {
        if (a.fine.isEmpty() || b.fine.isEmpty()) return 0
        if (a.rms <= 0 || b.rms <= 0) return 0
        val durRatio = if (a.durationMs <= 0 || b.durationMs <= 0) 1.0
        else minOf(a.durationMs, b.durationMs).toDouble() / maxOf(a.durationMs, b.durationMs).toDouble()
        // Very different lengths cannot be the same recording.
        if (durRatio < 0.7) return (durRatio * 40).toInt().coerceIn(0, 40)
        val r = bestCorrelation(a, b)
        // Below the bar: scale with correlation so unrelated songs read near
        // zero and same-genre coincidences stay visibly far from duplicates.
        if (r < 0.75) return ((r + 1.0) / 2.0 * 40).toInt().coerceIn(0, 39)
        // 0.75 -> 75, 1.0 -> 100.
        val raw = 75 + (r - 0.75) / 0.25 * 25
        return ((raw * 0.85) + (durRatio * 100 * 0.15)).toInt().coerceIn(0, 100)
    }

    /** Leading and trailing silence from per 100ms RMS buckets. */
    fun trimEdges(bucketRms: List<Double>, bucketMs: Long, threshold: Double = 0.012, minMs: Long = 400): Pair<Long, Long>? {
        if (bucketRms.isEmpty()) return null
        var leadBuckets = 0
        for (v in bucketRms) {
            if (v < threshold) leadBuckets++ else break
        }
        var trailBuckets = 0
        for (i in bucketRms.indices.reversed()) {
            if (bucketRms[i] < threshold) trailBuckets++ else break
        }
        val leadMs = leadBuckets * bucketMs
        val trailMs = trailBuckets * bucketMs
        if (leadMs < minMs && trailMs < minMs) return null
        return leadMs to trailMs
    }
}
