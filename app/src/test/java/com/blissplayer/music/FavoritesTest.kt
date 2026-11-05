package com.blissplayer.music

import org.junit.Assert.*
import org.junit.Test

/** Favorites math: exemption from rarity decay + rarity slider scaling. */
class FavoritesTest {

    @Test fun favoritesIgnorePlayCounts() {
        // A favorite played 500 times keeps full weight while a normal song decays.
        val fav = ShuffleMath.favoriteWeight(1.0)
        val normal = ShuffleMath.weight(500, 1.0)
        assertEquals(1.0, fav, 1e-9)
        assertTrue("normal song should have decayed, was $normal", normal < 0.01)
        assertTrue(fav > normal)
    }

    @Test fun raritySliderScalesFavorites() {
        assertEquals(1.0, ShuffleMath.favoriteWeight(1.0), 1e-9)
        assertEquals(2.5, ShuffleMath.favoriteWeight(2.5), 1e-9)
        // Clamped, never zero/negative (would break the exponential race).
        assertTrue(ShuffleMath.favoriteWeight(0.0) > 0)
        assertTrue(ShuffleMath.favoriteWeight(99.0) <= 3.0)
    }

    @Test fun favoritesWinTheRaceAtHighRarity() {
        // With rarity cranked, a heavily-played favorite still beats a fresh song.
        // P(fav first) = 3/(3+1) = 75%, so over 200 trials ~150 wins; >110 is ~7σ safe.
        val weights = mapOf(1L to ShuffleMath.favoriteWeight(3.0), 2L to ShuffleMath.weight(0, 1.0))
        var favFirst = 0
        repeat(200) { seed ->
            val order = ShuffleMath.weightedOrder(listOf(1L, 2L), weights, seed.toLong())
            if (order.first() == 1L) favFirst++
        }
        assertTrue("favorite should usually win at 3x, won $favFirst/200", favFirst > 110)
    }
}
