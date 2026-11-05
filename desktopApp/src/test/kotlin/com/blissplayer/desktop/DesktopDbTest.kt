package com.blissplayer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopDbTest {
    @Test fun playlistsFavoritesAndMigrationSurviveOrganize() {
        val dir = Files.createTempDirectory("blissdb").toFile()
        val db = DesktopDb(File(dir, "t.db").absolutePath)
        val pid = db.createPlaylist("Mix")
        db.addToPlaylist(pid, listOf(11L, 22L))
        assertEquals(listOf(11L, 22L), db.songsIn(pid))
        db.setFavorite(11L, true)
        assertTrue(11L in db.favorites())
        db.putGain(11L, 1.5)
        db.setGenre(11L, "Rock")
        db.setAlbum(11L, "Album")
        assertEquals(mapOf(11L to 1.5), db.allGains())
        db.migrateId(11L, 99L)
        assertEquals(mapOf(99L to 1.5), db.allGains())
        assertTrue(99L in db.favorites())
        assertEquals("Rock", db.genreOf(99L))
        assertEquals("Album", db.albumOf(99L))
        assertEquals(listOf(99L, 22L), db.songsIn(pid))
        db.close()
    }
}
