package com.blissplayer.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Listening based helpers shared by Android and Linux.
 * Fingerprints are normalized per second RMS buckets plus overall RMS,
 * so the same recording scores near 100 even with different tags, while
 * different songs with the same title score low.
 */
object AudioFingerprint {

    data class Print(
        val rms: Double,
        val buckets: FloatArray,
        val durationMs: Long
    )

    fun fromBuckets(rmsValues: List<Double>, durationMs: Long): Print {
        val rms = if (rmsValues.isEmpty()) 0.0 else sqrt(rmsValues.map { it * it }.average())
        val norm = rmsValues.map { (it / (rms.coerceAtLeast(1e-6))).coerceIn(0.0, 3.0).toFloat() }
        val fixed = FloatArray(16) { i ->
            if (norm.isEmpty()) 0f else norm[(i * norm.size / 16).coerceIn(0, norm.size - 1)]
        }
        return Print(rms, fixed, durationMs)
    }

    /** 0..100 similarity. Same recording is 90+. Different songs rarely pass 50. */
    fun similarity(a: Print, b: Print): Int {
        if (a.buckets.isEmpty() || b.buckets.isEmpty()) return 0
        if (a.rms <= 0 || b.rms <= 0) return 0
        val durRatio = if (a.durationMs <= 0 || b.durationMs <= 0) 1.0
        else minOf(a.durationMs, b.durationMs).toDouble() / maxOf(a.durationMs, b.durationMs).toDouble()
        // Very different lengths cannot be the same recording.
        if (durRatio < 0.7) return (durRatio * 40).toInt().coerceIn(0, 40)
        var diff = 0.0
        for (i in a.buckets.indices) {
            diff += abs(a.buckets[i] - b.buckets[i]).toDouble()
        }
        val avgDiff = diff / a.buckets.size
        // avgDiff 0 -> 100, avgDiff 0.6 -> ~50, avgDiff 1.2+ -> near 0.
        val shape = ((1.0 - avgDiff / 1.2).coerceIn(0.0, 1.0) * 100).toInt()
        // Blend in duration agreement so edits score slightly lower.
        return ((shape * 0.85) + (durRatio * 100 * 0.15)).toInt().coerceIn(0, 100)
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
