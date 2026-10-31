package com.blissplayer.music

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Pure shuffle math with no Android dependencies, so it can be unit tested
 * on the JVM. Exponential race (Efraimidis-Spirakis): key = u^(1/w),
 * sorted largest first. Big weight (rarely played) -> key near 1 -> plays
 * first. Small weight (played 11 times) -> key near 0 -> plays last.
 * Sorting ascending here would invert the odds and replay favorites first.
 */
object ShuffleMath {

    fun weightedOrder(ids: List<Long>, weights: Map<Long, Double>, seed: Long): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val rnd = Random(seed)
        return ids.map { id ->
            val w = (weights[id] ?: 1.0).coerceAtLeast(1e-12)
            id to rnd.nextDouble().coerceAtLeast(1e-9).pow(1.0 / w)
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** Compact chance percentage: whole numbers stay whole, small ones get decimals. */
    fun fmtChance(frac: Double): String {
        val pct = (frac * 100).coerceIn(0.0, 100.0)
        return when {
            pct >= 10 -> "%d%%".format(pct.roundToInt())
            pct >= 1 -> "%.1f%%".format(pct)
            else -> "%.2f%%".format(pct)
        }
    }

    fun weight(count: Int, strength: Double): Double =
        (1.0 / Math.pow((1 + count).toDouble(), strength)).coerceAtLeast(1e-9)
}
