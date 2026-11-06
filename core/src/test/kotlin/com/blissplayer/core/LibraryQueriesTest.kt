package com.blissplayer.core

import com.blissplayer.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueriesTest {
    private fun song(id: Long, title: String, artist: String = "A", album: String = "Al", dur: Long = 1000) =
        Song(id, "c://$id", "/m/$title.m4a", title, artist, album, dur, 10, "audio/mp4", 1)

    @Test fun sortsMirrorBothPlatforms() {
        val songs = listOf(song(1, "b"), song(2, "a"), song(3, "C"))
        assertEquals(listOf(2L, 1L, 3L), LibraryQueries.sortSongs(songs, "AZ").map { it.mediaId })
        assertEquals(listOf(3L, 1L, 2L), LibraryQueries.sortSongs(songs, "ZA").map { it.mediaId })
        val plays = mapOf(1L to 5, 2L to 1)
        assertEquals(listOf(1L, 2L, 3L), LibraryQueries.sortSongs(songs, "MostPlayed", plays).map { it.mediaId })
        assertEquals(listOf(3L, 2L, 1L), LibraryQueries.sortSongs(songs, "LeastPlayed", plays).map { it.mediaId })
        assertEquals(listOf(2L, 1L, 3L), LibraryQueries.sortSongs(songs, "bogus").map { it.mediaId })
    }

    @Test fun narrowAppliesChipsAndQuery() {
        val songs = listOf(
            song(1, "Hello", "Adele", "25"),
            song(2, "Rolling", "Adele", "21"),
            song(3, "Thunder", "ACDC", "Live at Hampden")
        )
        assertEquals(2, LibraryQueries.narrow(songs, "", artistSub = "adele").size)
        assertEquals(1, LibraryQueries.narrow(songs, "hell").size)
        assertEquals(2, LibraryQueries.narrow(songs, "", playlistIds = setOf(1L, 2L)).size)
        assertEquals(1, LibraryQueries.narrow(songs, "", genre = "Rock", genreOf = { if (it == 3L) "Rock" else "" }).size)
        assertEquals(3, LibraryQueries.narrow(songs, "").size)
    }

    @Test fun groupingMergesGluedVariants() {
        val songs = listOf(
            song(1, "One", "Panic at the disco"),
            song(2, "Two", "panicatthedisco"),
            song(3, "Three", "Red"),
            song(4, "Four", "thebandred")
        )
        val groups = LibraryQueries.groupArtists(songs) { listOf(it.artist) }
        assertEquals(2, groups.size)
        assertTrue(groups.keys.any { "Panic" in it })
        assertEquals(2, groups.values.map { it.size }.sorted()[1])
    }

    @Test fun albumsPreferOriginals() {
        val songs = listOf(song(1, "A", "X", "First Album"), song(2, "B", "X", "First Album (Live)"))
        val groups = LibraryQueries.groupAlbums(songs) { it.album }
        assertEquals(1, groups.size)
        assertEquals("First Album", groups.keys.single())
    }
}
