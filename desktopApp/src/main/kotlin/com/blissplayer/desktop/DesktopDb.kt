package com.blissplayer.desktop

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Same schema as the Android SQLiteOpenHelper (tables + meaning), desktop
 * driver (sqlite-jdbc). Fresh desktop databases start at the current shape;
 * no migration of Android user data is attempted — libraries are rescanned.
 */
class DesktopDb(path: String) {
    private val conn: Connection

    init {
        File(path).parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.autoCommit = true
        // Same single-sourced schema as Android (core DbSchema), idempotent
        // so existing desktop databases open unchanged.
        com.blissplayer.core.DbSchema.statements.forEach {
            exec(it.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ")
                .replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS "))
        }
    }

    @Synchronized fun close() = runCatching { conn.close() }.let { }

    @Synchronized fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    // ---- gains / measure ----
    @Synchronized fun putGain(id: Long, mult: Double) {
        conn.prepareStatement("INSERT OR REPLACE INTO gains(media_id, gain_mult) VALUES(?,?)").use {
            it.setLong(1, id); it.setDouble(2, mult); it.executeUpdate()
        }
    }

    @Synchronized fun allGains(): Map<Long, Double> {
        val m = mutableMapOf<Long, Double>()
        conn.createStatement().use { s ->
            s.executeQuery("SELECT media_id, gain_mult FROM gains").use { rs ->
                while (rs.next()) m[rs.getLong(1)] = rs.getDouble(2)
            }
        }
        return m
    }

    @Synchronized fun putMeasure(id: Long, rms: Double, modified: Long) {
        conn.prepareStatement("INSERT OR REPLACE INTO measure(media_id, rms, modified) VALUES(?,?,?)").use {
            it.setLong(1, id); it.setDouble(2, rms); it.setLong(3, modified); it.executeUpdate()
        }
    }

    @Synchronized fun allMeasures(): Map<Long, Pair<Double, Long>> {
        val m = mutableMapOf<Long, Pair<Double, Long>>()
        conn.createStatement().use { s ->
            s.executeQuery("SELECT media_id, rms, modified FROM measure").use { rs ->
                while (rs.next()) m[rs.getLong(1)] = rs.getDouble(2) to rs.getLong(3)
            }
        }
        return m
    }

    // ---- playlists ----
    @Synchronized fun playlists(): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        conn.createStatement().use { s ->
            s.executeQuery("SELECT id, name FROM playlists ORDER BY position, name").use { rs ->
                while (rs.next()) out.add(rs.getLong(1) to rs.getString(2))
            }
        }
        return out
    }

    @Synchronized fun createPlaylist(name: String): Long {
        conn.prepareStatement("INSERT INTO playlists(name, position) VALUES(?, (SELECT COALESCE(MAX(position),-1)+1 FROM playlists))").use {
            it.setString(1, name); it.executeUpdate()
        }
        conn.createStatement().use { s ->
            s.executeQuery("SELECT last_insert_rowid()").use { rs -> if (rs.next()) return rs.getLong(1) }
        }
        return -1
    }

    @Synchronized fun songsIn(pid: Long): List<Long> {
        val out = mutableListOf<Long>()
        conn.prepareStatement("SELECT media_id FROM playlist_songs WHERE playlist_id=? ORDER BY position").use {
            it.setLong(1, pid)
            it.executeQuery().use { rs -> while (rs.next()) out.add(rs.getLong(1)) }
        }
        return out
    }

    @Synchronized fun addToPlaylist(pid: Long, ids: List<Long>) {
        var pos = songsIn(pid).size
        conn.prepareStatement("INSERT INTO playlist_songs(playlist_id, media_id, position) VALUES(?,?,?)").use { ps ->
            for (id in ids) {
                ps.setLong(1, pid); ps.setLong(2, id); ps.setInt(3, pos++); ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    @Synchronized fun removePlaylist(pid: Long) {
        conn.prepareStatement("DELETE FROM playlist_songs WHERE playlist_id=?").use { it.setLong(1, pid); it.executeUpdate() }
        conn.prepareStatement("DELETE FROM playlists WHERE id=?").use { it.setLong(1, pid); it.executeUpdate() }
    }

    @Synchronized fun renamePlaylist(pid: Long, name: String) {
        conn.prepareStatement("UPDATE playlists SET name=? WHERE id=?").use {
            it.setString(1, name); it.setLong(2, pid); it.executeUpdate()
        }
    }

    @Synchronized fun removeFromPlaylist(pid: Long, mediaId: Long) {
        conn.prepareStatement("DELETE FROM playlist_songs WHERE playlist_id=? AND media_id=?").use {
            it.setLong(1, pid); it.setLong(2, mediaId); it.executeUpdate()
        }
    }

    // ---- favorites / genre / album ----
    @Synchronized fun favorites(): Set<Long> {
        val out = mutableSetOf<Long>()
        conn.createStatement().use { s ->
            s.executeQuery("SELECT media_id FROM favorites").use { rs -> while (rs.next()) out.add(rs.getLong(1)) }
        }
        return out
    }

    @Synchronized fun setFavorite(id: Long, loved: Boolean) {
        if (loved) conn.prepareStatement("INSERT OR IGNORE INTO favorites(media_id) VALUES(?)").use {
            it.setLong(1, id); it.executeUpdate()
        } else conn.prepareStatement("DELETE FROM favorites WHERE media_id=?").use {
            it.setLong(1, id); it.executeUpdate()
        }
    }

    @Synchronized fun genreOf(id: Long): String {
        conn.prepareStatement("SELECT genre FROM song_genre WHERE media_id=?").use {
            it.setLong(1, id)
            it.executeQuery().use { rs -> if (rs.next()) return rs.getString(1).takeIf { g -> g.isNotBlank() } ?: "Uncategorized" }
        }
        return "Uncategorized"
    }

    @Synchronized fun setGenre(id: Long, genre: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO song_genre(media_id, genre) VALUES(?,?)").use {
            it.setLong(1, id); it.setString(2, genre); it.executeUpdate()
        }
    }

    @Synchronized fun albumOf(id: Long): String? {
        conn.prepareStatement("SELECT album FROM song_album WHERE media_id=?").use {
            it.setLong(1, id)
            it.executeQuery().use { rs -> if (rs.next()) return rs.getString(1) }
        }
        return null
    }

    @Synchronized fun setAlbum(id: Long, album: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO song_album(media_id, album) VALUES(?,?)").use {
            it.setLong(1, id); it.setString(2, album); it.executeUpdate()
        }
    }

    // ---- lookup cache / song state / learned artists ----
    data class LookupHit(val title: String, val artist: String, val genre: String, val album: String,
        val pageId: Long, val hitTitle: String, val high: Boolean, val confirmed: Boolean, val found: Boolean)

    @Synchronized fun getLookup(key: String): LookupHit? {
        conn.prepareStatement("SELECT title, artist, genre, album, pageid, hittitle, high, confirmed, found FROM lookup_cache WHERE key=?").use {
            it.setString(1, key)
            it.executeQuery().use { rs ->
                if (rs.next()) return LookupHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getLong(5), rs.getString(6), rs.getInt(7) != 0, rs.getInt(8) != 0, rs.getInt(9) != 0)
            }
        }
        return null
    }

    @Synchronized fun putLookup(key: String, h: LookupHit) {
        conn.prepareStatement("INSERT OR REPLACE INTO lookup_cache(key, title, artist, genre, album, pageid, hittitle, high, confirmed, found) VALUES(?,?,?,?,?,?,?,?,?,?)").use {
            it.setString(1, key); it.setString(2, h.title); it.setString(3, h.artist); it.setString(4, h.genre)
            it.setString(5, h.album); it.setLong(6, h.pageId); it.setString(7, h.hitTitle)
            it.setInt(8, if (h.high) 1 else 0); it.setInt(9, if (h.confirmed) 1 else 0); it.setInt(10, if (h.found) 1 else 0)
            it.executeUpdate()
        }
    }

    @Synchronized fun markAnalyzed(id: Long, fingerprint: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO song_state(media_id, fingerprint, analyzed, analyzed_at) VALUES(?,?,1,?)").use {
            it.setLong(1, id); it.setString(2, fingerprint); it.setLong(3, System.currentTimeMillis()); it.executeUpdate()
        }
    }

    @Synchronized fun markEq(id: Long, fingerprint: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO song_state(media_id, fingerprint, eq, eq_at) VALUES(?,?,1,?)").use {
            it.setLong(1, id); it.setString(2, fingerprint); it.setLong(3, System.currentTimeMillis()); it.executeUpdate()
        }
    }

    @Synchronized fun needsWork(id: Long, fingerprint: String, kind: String): Boolean {
        conn.prepareStatement("SELECT fingerprint, analyzed, eq FROM song_state WHERE media_id=?").use {
            it.setLong(1, id)
            it.executeQuery().use { rs ->
                if (!rs.next() || rs.getString(1) != fingerprint) return true
                return if (kind == "eq") rs.getInt(3) == 0 else rs.getInt(2) == 0
            }
        }
    }

    @Synchronized fun learnArtist(aliasKey: String, name: String) {
        if (aliasKey.isBlank() || name.isBlank()) return
        conn.prepareStatement("INSERT OR REPLACE INTO learned_artists(alias_key, name) VALUES(?,?)").use {
            it.setString(1, aliasKey); it.setString(2, name); it.executeUpdate()
        }
    }

    @Synchronized fun learnedArtists(): Map<String, String> {
        val m = mutableMapOf<String, String>()
        conn.createStatement().use { s ->
            s.executeQuery("SELECT alias_key, name FROM learned_artists").use { rs ->
                while (rs.next()) m[rs.getString(1)] = rs.getString(2)
            }
        }
        return m
    }

    // ---- stats ----
    @Synchronized fun bumpPlay(id: Long) {        conn.prepareStatement("INSERT INTO plays(media_id, count) VALUES(?,1) ON CONFLICT(media_id) DO UPDATE SET count=count+1").use {
            it.setLong(1, id); it.executeUpdate()
        }
        val now = System.currentTimeMillis()
        conn.prepareStatement("INSERT INTO stat_events(media_id, kind, at) VALUES(?, 'play', ?)").use {
            it.setLong(1, id); it.setLong(2, now); it.executeUpdate()
        }
    }

    @Synchronized fun totalPlays(): Map<Long, Int> {
        val m = mutableMapOf<Long, Int>()
        conn.createStatement().use { s ->
            s.executeQuery("SELECT media_id, count FROM plays").use { rs ->
                while (rs.next()) m[rs.getLong(1)] = rs.getInt(2)
            }
        }
        return m
    }

    /** mediaIds with a play event at/after `since` (month/year buckets). */
    @Synchronized fun playsSince(since: Long): List<Long> {
        val out = mutableListOf<Long>()
        conn.prepareStatement("SELECT media_id FROM stat_events WHERE kind='play' AND at>=?").use {
            it.setLong(1, since)
            it.executeQuery().use { rs -> while (rs.next()) out.add(rs.getLong(1)) }
        }
        return out
    }

    @Synchronized fun segmentsFor(id: Long): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        conn.prepareStatement("SELECT start_ms, end_ms FROM segments WHERE media_id=? ORDER BY start_ms").use {
            it.setLong(1, id)
            it.executeQuery().use { rs -> while (rs.next()) out.add(rs.getLong(1) to rs.getLong(2)) }
        }
        return out
    }

    data class Seg(val id: Long, val mediaId: Long, val startMs: Long, val endMs: Long, val label: String)

    @Synchronized fun segmentsFull(id: Long): List<Seg> {
        val out = mutableListOf<Seg>()
        conn.prepareStatement("SELECT id, start_ms, end_ms, label FROM segments WHERE media_id=? ORDER BY start_ms").use {
            it.setLong(1, id)
            it.executeQuery().use { rs ->
                while (rs.next()) out.add(Seg(rs.getLong(1), id, rs.getLong(2), rs.getLong(3), rs.getString(4) ?: ""))
            }
        }
        return out
    }

    @Synchronized fun addSegment(mediaId: Long, startMs: Long, endMs: Long, label: String): Long {
        conn.prepareStatement("INSERT INTO segments(media_id, start_ms, end_ms, label) VALUES(?,?,?,?)").use {
            it.setLong(1, mediaId); it.setLong(2, startMs); it.setLong(3, endMs); it.setString(4, label)
            it.executeUpdate()
        }
        conn.createStatement().use { s ->
            s.executeQuery("SELECT last_insert_rowid()").use { rs -> if (rs.next()) return rs.getLong(1) }
        }
        return -1
    }

    @Synchronized fun deleteSegment(id: Long) {
        conn.prepareStatement("DELETE FROM segments WHERE id=?").use { it.setLong(1, id); it.executeUpdate() }
    }

    /** Forget a deleted file everywhere: playlists, favorites, tags, stats, gains. */
    @Synchronized fun deleteSongEverywhere(id: Long) {
        for (t in listOf("gains", "measure", "favorites", "song_genre", "song_album",
            "song_state", "plays", "plays_total", "song_stats")) {
            conn.prepareStatement("DELETE FROM $t WHERE media_id=?").use {
                it.setLong(1, id); it.executeUpdate()
            }
        }
        for (t in listOf("playlist_songs", "segments", "stat_events")) {
            conn.prepareStatement("DELETE FROM $t WHERE media_id=?").use {
                it.setLong(1, id); it.executeUpdate()
            }
        }
    }

    /**
     * Organize moved a file: its path-hash id changed. Carry gains, tags,
     * favorites, playlists and stats from the old id to the new one so
     * nothing is lost (Android keeps MediaStore ids; desktop remaps).
     */
    @Synchronized fun migrateId(oldId: Long, newId: Long) {
        if (oldId == newId) return
        // Explicit per-table copies (schemas differ, so no generic SQL).
        copyRow("gains", oldId, newId)
        copyRow("measure", oldId, newId)
        conn.prepareStatement("INSERT OR IGNORE INTO favorites(media_id) VALUES(?)").use {
            it.setLong(1, newId)
            if (isFavorite(oldId)) it.executeUpdate()
        }
        copyRow("song_genre", oldId, newId)
        copyRow("song_album", oldId, newId)
        copyRow("song_state", oldId, newId)
        copyRow("plays", oldId, newId)
        copyRow("plays_total", oldId, newId)
        copyRow("song_stats", oldId, newId)
        for (t in listOf("playlist_songs", "segments", "stat_events")) {
            conn.prepareStatement("UPDATE $t SET media_id=? WHERE media_id=?").use {
                it.setLong(1, newId); it.setLong(2, oldId); it.executeUpdate()
            }
        }
        for (t in listOf("gains", "measure", "song_genre", "song_album", "song_state",
            "plays", "plays_total", "song_stats")) {
            conn.prepareStatement("DELETE FROM $t WHERE media_id=?").use {
                it.setLong(1, oldId); it.executeUpdate()
            }
        }
        conn.prepareStatement("DELETE FROM favorites WHERE media_id=?").use {
            it.setLong(1, oldId); it.executeUpdate()
        }
    }

    private fun isFavorite(id: Long): Boolean {
        conn.prepareStatement("SELECT 1 FROM favorites WHERE media_id=?").use {
            it.setLong(1, id)
            it.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun copyRow(table: String, oldId: Long, newId: Long) {
        val cols = runCatching {
            val md = conn.metaData.getColumns(null, null, table, null)
            val c = mutableListOf<String>()
            while (md.next()) c.add(md.getString("COLUMN_NAME"))
            md.close(); c
        }.getOrNull() ?: return
        if ("media_id" !in cols) return
        val others = cols.filter { it != "media_id" }.joinToString(", ")
        runCatching {
            conn.prepareStatement("INSERT OR IGNORE INTO $table(media_id, $others) SELECT ?, $others FROM $table WHERE media_id=?").use {
                it.setLong(1, newId); it.setLong(2, oldId); it.executeUpdate()
            }
        }
    }
}
