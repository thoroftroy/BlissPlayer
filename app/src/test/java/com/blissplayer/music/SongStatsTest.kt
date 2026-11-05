package com.blissplayer.music

import com.blissplayer.music.model.SongStats
import org.junit.Assert.*
import org.junit.Test

/** Fun-stats classification: completions, skips, listened slices. */
class SongStatsTest {

    @Test fun autoAdvanceIsCompletion() {
        assertEquals(SongStats.Outcome.COMPLETION, SongStats.decide(true, 180000, 180000))
    }

    @Test fun bailingEarlyIsSkip() {
        assertEquals(SongStats.Outcome.SKIP, SongStats.decide(false, 20000, 200000))
    }

    @Test fun mostOfItCountsAsCompletion() {
        assertEquals(SongStats.Outcome.COMPLETION, SongStats.decide(false, 150000, 200000))
    }

    @Test fun tinyFragmentIsNeither() {
        assertEquals(SongStats.Outcome.NEITHER, SongStats.decide(false, 1500, 200000))
        assertEquals(SongStats.Outcome.NEITHER, SongStats.decide(true, 0, 0))
    }

    @Test fun sliceClamps() {
        assertEquals(5000L, SongStats.slice(10000, 15000, 200000))
        assertEquals(0L, SongStats.slice(15000, 10000, 200000))
        assertEquals(200000L, SongStats.slice(0, 9999999, 200000))
    }

    @Test fun bucketsNest() {
        // Fixed instant: Nov 5 2026 midday UTC (timezone-independent ordering).
        val now = 1793659200000L
        val ms = SongStats.monthStartMs(now)
        val ys = SongStats.yearStartMs(now)
        assertTrue("month starts before now", ms <= now)
        assertTrue("year starts before month", ys <= ms)
        assertTrue("month label names a month", SongStats.monthLabel(now).matches(Regex("[A-Z][a-z]+ 20\\d\\d")))
        // A month start is always the 1st at midnight.
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        assertEquals(1, c.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(0, c.get(java.util.Calendar.HOUR_OF_DAY))
    }
}
