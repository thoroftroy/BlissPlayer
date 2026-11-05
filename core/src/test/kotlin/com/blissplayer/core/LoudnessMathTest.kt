package com.blissplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessMathTest {
    @Test fun unityAtFullStrengthForAverageSong() {
        assertEquals(1.0, LoudnessMath.gain(1.0, 1f), 1e-9)
        assertEquals(1.0, LoudnessMath.gain(2.0, 0f), 1e-9)
    }

    @Test fun matchesDocumentedFormulaAndWindow() {
        // full=2 (quiet song), strength 1 -> 2x; strength 0.5 -> 1.5x.
        assertEquals(2.0, LoudnessMath.gain(2.0, 1f), 1e-9)
        assertEquals(1.5, LoudnessMath.gain(2.0, 0.5f), 1e-9)
        // 18 dB window: clamped to [0.125, 8.0].
        assertEquals(8.0, LoudnessMath.gain(100.0, 2f), 1e-9)
        assertEquals(0.125, LoudnessMath.gain(0.0, 2f), 1e-9)
    }

    @Test fun gainsAverageOutAndCount() {
        val rms = mapOf(1L to 0.5, 2L to 1.5)
        val g = LoudnessMath.gains(rms, 1f)
        // avg=1.0: quiet song doubled, loud song pulled to 2/3.
        assertEquals(2.0, g[1L]!!, 1e-9)
        assertEquals(1.0 / 1.5, g[2L]!!, 1e-9)
        assertEquals(1 to 1, LoudnessMath.counts(g))
        assertTrue(LoudnessMath.gains(emptyMap(), 1f).isEmpty())
    }
}
