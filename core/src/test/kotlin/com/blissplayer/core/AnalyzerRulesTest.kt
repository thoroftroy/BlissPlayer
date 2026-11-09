package com.blissplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerRulesTest {
    @Test fun groupingKeysMergeGluedAndSpacedNames() {
        assertEquals("panicatthedisco", AnalyzerRules.artistMergeKey("Panic at the disco"))
        assertEquals(AnalyzerRules.artistMergeKey("Panic at the disco"), AnalyzerRules.artistMergeKey("panicatthedisco"))
        assertEquals(AnalyzerRules.artistMergeKey("Red"), AnalyzerRules.artistMergeKey("thebandred"))
        assertEquals("eminem", AnalyzerRules.artistKey("Eminem from the 8 Mile soundtrack"))
    }

    @Test fun liveAndBestOfAreNeverAlbums() {
        assertTrue(AnalyzerRules.isBadAlbumTitle("Greatest Hits"))
        assertTrue(AnalyzerRules.isBadAlbumTitle("Live in Tilburg"))
        assertFalse(AnalyzerRules.isBadAlbumTitle("Captain Morgan's Revenge"))
        assertEquals("Back In Black", AnalyzerRules.canonicalAlbum("Back In Black (Live)"))
    }

    @Test fun versionPackagingResolvesToTheOriginal() {
        assertEquals("Trouble", AnalyzerRules.resolveAlbumTitle("Trouble (Deluxe Edition)"))
        assertEquals("Back in Black", AnalyzerRules.resolveAlbumTitle("Back in Black Remastered"))
        assertEquals("Back In Black", AnalyzerRules.canonicalAlbum("Back In Black (Live)"))
        assertNull(AnalyzerRules.resolveAlbumTitle("Live in Tilburg"))
        assertNull(AnalyzerRules.resolveAlbumTitle("Greatest Hits"))
        assertNull(AnalyzerRules.resolveAlbumTitle("Best of Queen"))
        assertNull(AnalyzerRules.resolveAlbumTitle("MTV Unplugged"))
        assertNull(AnalyzerRules.resolveAlbumTitle("Live"))
        assertNull(AnalyzerRules.resolveAlbumTitle("Hits"))
        assertNull(AnalyzerRules.resolveAlbumTitle(""))
        assertEquals("A Night at the Opera", AnalyzerRules.resolveAlbumTitle("A Night at the Opera"))
        assertEquals("Alive", AnalyzerRules.resolveAlbumTitle("Alive"))
    }

    @Test fun cleanupStripsVideoNoiseButKeepsPerformers() {
        val c = AnalyzerRules.cleanLocal("16 Stitch - Things They Say", "TheKrow", "16-stitch-things-they-say")
        assertEquals("Things They Say", c.title)
        assertEquals("16 Stitch", c.artist)
    }

    @Test fun genreComesFromCategoriesOnly() {
        assertEquals("Rock", AnalyzerRules.genreFromCategories(listOf("Category:American rock songs")))
        assertNull(AnalyzerRules.genreFromCategories(listOf("Category:1999 singles")))
    }
}
