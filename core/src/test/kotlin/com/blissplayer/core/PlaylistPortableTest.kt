package com.blissplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPortableTest {
    private fun entry(title: String, path: String = "Music/Artist/$title.m4a") =
        PlaylistPortable.Entry(path, "$title.m4a", title, "Artist", "Album", 180000)

    @Test fun roundTripKeepsEverything() {
        val json = PlaylistPortable.export("Mix", listOf(entry("One"), entry("Two")), listOf(entry("Fav")))
        val back = PlaylistPortable.parse(json)!!
        assertEquals("Mix", back.name)
        assertEquals(2, back.entries.size)
        assertEquals(1, back.favorites.size)
        assertEquals(1, PlaylistPortable.FORMAT_VERSION)
    }

    @Test fun rejectsWrongVersionAndGarbage() {
        assertNull(PlaylistPortable.parse("{}"))
        assertNull(PlaylistPortable.parse("{\"formatVersion\":999,\"name\":\"x\"}"))
        assertNull(PlaylistPortable.parse("not json"))
    }

    @Test fun relinkPrefersExactPathThenFuzzyThenMissing() {
        val lib = listOf(
            PlaylistPortable.LibTrack("Music/Artist/One.m4a", "One.m4a", "One", "Artist", "Album", 180000),
            PlaylistPortable.LibTrack("Other/Two.m4a", "Two.m4a", "Two", "Artist", "Album", 181000)
        )
        val entries = listOf(
            entry("One", "Music/Artist/One.m4a"),
            entry("Two", "Somewhere/Two.m4a"),
            entry("Gone", "Nowhere/Gone.m4a")
        )
        val out = PlaylistPortable.relink(entries, lib) { it }
        assertEquals(2, out.found.size)
        assertEquals(1, out.missing.size)
        assertEquals("Gone", out.missing.single().title)
    }

    @Test fun missingEntriesAreKeptNeverDropped() {
        val out = PlaylistPortable.relink<PlaylistPortable.LibTrack>(listOf(entry("Ghost")), emptyList()) {
            PlaylistPortable.LibTrack("", "", "", "", "", 0)
        }
        assertTrue(out.found.isEmpty())
        assertEquals("Ghost", out.missing.single().title)
    }

    @Test fun favoritesToggleIsJustOmission() {
        val without = PlaylistPortable.export("Mix", listOf(entry("One")), emptyList())
        assertTrue(PlaylistPortable.parse(without)!!.favorites.isEmpty())
    }
}
