package com.blissplayer.music

import com.blissplayer.music.data.SongAnalyzer
import org.junit.Assert.*
import org.junit.Test

/** Grouping merges + never-live album rules. */
class GroupMergeTest {

    @Test fun spacelessVariantsMerge() {
        assertEquals(
            SongAnalyzer.artistMergeKey("Panic at the disco"),
            SongAnalyzer.artistMergeKey("panicatthedisco")
        )
        assertEquals(
            SongAnalyzer.artistMergeKey("Poor man's poison"),
            SongAnalyzer.artistMergeKey("poormanspoison")
        )
    }

    @Test fun channelPrefixMerges() {
        assertEquals(
            SongAnalyzer.artistMergeKey("Red"),
            SongAnalyzer.artistMergeKey("thebandred")
        )
        assertEquals(
            SongAnalyzer.artistMergeKey("Red"),
            SongAnalyzer.artistMergeKey("TheBandRed")
        )
    }

    @Test fun distinctArtistsStayDistinct() {
        assertNotEquals(
            SongAnalyzer.artistMergeKey("Red"),
            SongAnalyzer.artistMergeKey("Red Hot Chili Peppers")
        )
        assertNotEquals(
            SongAnalyzer.artistMergeKey("Queen"),
            SongAnalyzer.artistMergeKey("Queens of the Stone Age")
        )
    }

    @Test fun spacedDisplayWins() {
        assertEquals(
            "Panic at the disco",
            SongAnalyzer.preferArtistDisplay("panicatthedisco", "Panic at the disco")
        )
        assertEquals(
            "Panic at the disco",
            SongAnalyzer.preferArtistDisplay("Panic at the disco", "panicatthedisco")
        )
    }

    @Test fun liveAlbumsRejected() {
        assertTrue(SongAnalyzer.isBadAlbumTitle("Live at Wembley"))
        assertTrue(SongAnalyzer.isBadAlbumTitle("Greatest Hits"))
        assertTrue(SongAnalyzer.isBadAlbumTitle("Best of Queen"))
        assertTrue(SongAnalyzer.isBadAlbumTitle("MTV Unplugged"))
        assertTrue(SongAnalyzer.isBadAlbumTitle("The Very Best of Adele"))
    }

    @Test fun studioAlbumsAndSinglesPass() {
        assertFalse(SongAnalyzer.isBadAlbumTitle("A Night at the Opera"))
        assertFalse(SongAnalyzer.isBadAlbumTitle("Back in Black"))
        assertFalse(SongAnalyzer.isBadAlbumTitle("Blinding Lights"))
        // "Live" inside a normal word is not a concert.
        assertFalse(SongAnalyzer.isBadAlbumTitle("Delivered"))
        assertFalse(SongAnalyzer.isBadAlbumTitle("Alive"))
    }
}
