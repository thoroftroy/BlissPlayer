package com.blissplayer.music.model

/**
 * Pure end-of-listen classification, so the fun stats are unit-testable.
 * A song counts as completed when it played itself to the end (auto
 * advance) or a human let more than half of it through; bailing before
 * half is a skip. Tiny fragments (under 3s heard) count as neither.
 */
object SongStats {

    enum class Outcome { COMPLETION, SKIP, NEITHER }

    fun decide(autoAdvanced: Boolean, heardMs: Long, durationMs: Long): Outcome {
        if (durationMs <= 0 || heardMs < 3000) return Outcome.NEITHER
        if (autoAdvanced) return Outcome.COMPLETION
        return if (heardMs * 2 >= durationMs) Outcome.COMPLETION else Outcome.SKIP
    }

    /** Listened slice of one stint, clamped to the song length. Never negative. */
    fun slice(startMs: Long, endMs: Long, durationMs: Long): Long =
        (endMs - startMs).coerceIn(0, durationMs.coerceAtLeast(0))

    /** Start of the local month containing nowMs (for "this month" buckets). */
    fun monthStartMs(nowMs: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = nowMs
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Start of the local year containing nowMs (for "this year" buckets). */
    fun yearStartMs(nowMs: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = nowMs
        c.set(java.util.Calendar.MONTH, 0)
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** "March 2026" style label for a bucket header. */
    fun monthLabel(nowMs: Long): String {
        val months = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = nowMs
        return "${months[c.get(java.util.Calendar.MONTH)]} ${c.get(java.util.Calendar.YEAR)}"
    }
}
