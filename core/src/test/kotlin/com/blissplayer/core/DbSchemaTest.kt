package com.blissplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DbSchemaTest {
    @Test fun versionAndTablesAreComplete() {
        assertEquals(16, DbSchema.VERSION)
        assertEquals(18, DbSchema.tables.size)
        assertEquals(DbSchema.tables.size, DbSchema.tables.toSet().size)
        for (t in listOf("gains", "playlists", "playlist_songs", "boost", "plays",
            "measure", "preset", "plays_total", "segments", "song_genre", "song_album",
            "lookup_cache", "song_state", "favorites", "song_stats", "stat_events", "learned_artists",
            "dupe_fine")) {
            assertTrue("missing table $t", t in DbSchema.tables)
        }
        // Every statement is single-statement SQLite (safe to exec one by one).
        assertTrue(DbSchema.statements.none { it.trim().endsWith(";") })
    }
}
