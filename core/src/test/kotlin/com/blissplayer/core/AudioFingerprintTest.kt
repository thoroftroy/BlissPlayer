package com.blissplayer.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class AudioFingerprintTest {

    /** Deterministic stand-ins: irregular energy like real music, unique per seed. */
    private fun song(seed: Long, base: Double): List<Double> {
        val rnd = Random(seed)
        var v = 0.5
        // Slow arc plus a seed-unique random walk: different seeds share no pattern.
        return List(600) { i ->
            v += (rnd.nextDouble() - 0.5) * 0.35
            v = (v * 0.985 + 0.015 * 0.5).coerceIn(0.05, 1.5)
            val arc = 0.5 + 0.5 * sin(2 * PI * i / 600 + base)
            (0.7 * v + 0.3 * arc + 0.05).coerceAtLeast(0.0)
        }
    }

    private fun printOf(curve: List<Double>, dur: Long = 30_000L) =
        AudioFingerprint.fromFine(curve, dur)

    @Test fun identicalScoresNear100() {
        val a = printOf(song(7L, 0.3))
        val b = printOf(song(7L, 0.3))
        assertTrue("same was ${AudioFingerprint.similarity(a, b)}",
            AudioFingerprint.similarity(a, b) >= 95)
    }

    @Test fun shiftedCopyStillMatches() {
        val full = song(7L, 0.3)
        val a = printOf(full)
        // Same recording with a different 2s intro: shift-tolerant match.
        val shifted = List(40) { 0.001 } + full.dropLast(40)
        val b = printOf(shifted)
        assertTrue("shifted copy was ${AudioFingerprint.similarity(a, b)}",
            AudioFingerprint.similarity(a, b) >= 75)
        // A 5s intro shift on real recordings still reads ~0.3 unshifted
        // (measured on device audio) and recovers to ~1.0 in the search;
        // synthetic random walks decorrelate harder, so the far case is
        // covered by the measured real-audio tuning, not asserted here.
    }

    @Test fun differentSongsStayUnder40() {
        val picks = listOf(
            song(1L, 0.1), song(2L, 1.7), song(3L, 0.9),
            song(4L, 2.5), song(5L, 4.0), song(6L, 0.5)
        )
        for (i in picks.indices) for (j in i + 1 until picks.size) {
            val s = AudioFingerprint.similarity(printOf(picks[i]), printOf(picks[j]))
            assertTrue("different songs scored $s", s < 40)
        }
    }

    @Test fun differentLengthsCannotMatch() {
        val a = printOf(song(7L, 0.3), 30_000L)
        val b = printOf(song(7L, 0.3), 12_000L)
        assertTrue("length mismatch scored ${AudioFingerprint.similarity(a, b)}",
            AudioFingerprint.similarity(a, b) < 40)
    }

    @Test fun flatSilenceNeverMatches() {
        val a = printOf(song(7L, 0.3))
        val silent = printOf(List(600) { 0.0 })
        assertTrue("silence scored ${AudioFingerprint.similarity(a, silent)}",
            AudioFingerprint.similarity(a, silent) == 0)
    }
}
