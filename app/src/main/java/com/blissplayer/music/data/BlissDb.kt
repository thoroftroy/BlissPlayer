package com.blissplayer.music.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Tiny hand-rolled SQLite store (no Room, keeps APK small and battery use flat).
 * Tables:
 *  gains(media_id PRIMARY KEY, gain_mult)     , baked loudness-normalize factors
 *  playlists(id, name) + playlist_songs(playlist_id, media_id, position)
 *  boost(device_name PRIMARY KEY, gain_mult)  , per-device amp memory (car vs headphones)
 */
class BlissDb(context: Context) : SQLiteOpenHelper(context, "bliss.db", null, 9) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE gains(media_id INTEGER PRIMARY KEY, gain_mult REAL NOT NULL)")
        db.execSQL("CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE playlist_songs(playlist_id INTEGER NOT NULL, media_id INTEGER NOT NULL, position INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_ps ON playlist_songs(playlist_id, position)")
        db.execSQL("CREATE TABLE boost(device_name TEXT PRIMARY KEY, gain_mult REAL NOT NULL)")
        db.execSQL("CREATE TABLE plays(media_id INTEGER PRIMARY KEY, count INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE measure(media_id INTEGER PRIMARY KEY, rms REAL NOT NULL, modified INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE preset(name TEXT PRIMARY KEY, gain_mult REAL NOT NULL)")
        db.execSQL("CREATE TABLE plays_total(media_id INTEGER PRIMARY KEY, count INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE segments(id INTEGER PRIMARY KEY AUTOINCREMENT, media_id INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, label TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX idx_seg ON segments(media_id)")
        db.execSQL("CREATE TABLE song_genre(media_id INTEGER PRIMARY KEY, genre TEXT NOT NULL)")
        db.execSQL("CREATE TABLE song_album(media_id INTEGER PRIMARY KEY, album TEXT NOT NULL)")
        db.execSQL("CREATE TABLE lookup_cache(key TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, genre TEXT NOT NULL DEFAULT '', album TEXT NOT NULL DEFAULT '', pageid INTEGER NOT NULL DEFAULT 0, hittitle TEXT NOT NULL DEFAULT '', high INTEGER NOT NULL DEFAULT 0, confirmed INTEGER NOT NULL DEFAULT 0, found INTEGER NOT NULL DEFAULT 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) db.execSQL("CREATE TABLE IF NOT EXISTS boost(device_name TEXT PRIMARY KEY, gain_mult REAL NOT NULL)")
        if (old < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS plays(media_id INTEGER PRIMARY KEY, count INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS measure(media_id INTEGER PRIMARY KEY, rms REAL NOT NULL, modified INTEGER NOT NULL)")
        }
        if (old < 4) db.execSQL("CREATE TABLE IF NOT EXISTS preset(name TEXT PRIMARY KEY, gain_mult REAL NOT NULL)")
        if (old < 5) db.execSQL("CREATE TABLE IF NOT EXISTS plays_total(media_id INTEGER PRIMARY KEY, count INTEGER NOT NULL)")
        if (old < 6) {
            db.execSQL("CREATE TABLE IF NOT EXISTS segments(id INTEGER PRIMARY KEY AUTOINCREMENT, media_id INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, label TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_seg ON segments(media_id)")
            db.execSQL("CREATE TABLE IF NOT EXISTS song_genre(media_id INTEGER PRIMARY KEY, genre TEXT NOT NULL)")
        }
        if (old < 7) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE playlists SET position = (SELECT COUNT(*) FROM playlists p2 WHERE p2.name < playlists.name OR (p2.name = playlists.name AND p2.id < playlists.id))")
        }
        if (old < 8) {
            db.execSQL("CREATE TABLE IF NOT EXISTS song_album(media_id INTEGER PRIMARY KEY, album TEXT NOT NULL)")
        }
        if (old < 9) {
            db.execSQL("CREATE TABLE IF NOT EXISTS lookup_cache(key TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, genre TEXT NOT NULL DEFAULT '', album TEXT NOT NULL DEFAULT '', pageid INTEGER NOT NULL DEFAULT 0, hittitle TEXT NOT NULL DEFAULT '', high INTEGER NOT NULL DEFAULT 0, confirmed INTEGER NOT NULL DEFAULT 0, found INTEGER NOT NULL DEFAULT 0)")
        }
    }

    // ---- gains ----
    fun putGain(mediaId: Long, mult: Double) {
        writableDatabase.insertWithOnConflict(
            "gains", null,
            ContentValues().apply {
                put("media_id", mediaId)
                put("gain_mult", mult)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun allGains(): Map<Long, Double> {
        val m = mutableMapOf<Long, Double>()
        readableDatabase.rawQuery("SELECT media_id, gain_mult FROM gains", null)?.use { c ->
            while (c.moveToNext()) m[c.getLong(0)] = c.getDouble(1)
        }
        return m
    }

    fun clearGains() = writableDatabase.delete("gains", null, null)

    // ---- playlists ----
    fun createPlaylist(name: String): Long {
        val max = runCatching {
            readableDatabase.compileStatement("SELECT COALESCE(MAX(position),-1) FROM playlists").simpleQueryForLong()
        }.getOrDefault(-1)
        return writableDatabase.insert(
            "playlists", null,
            ContentValues().apply {
                put("name", name)
                put("position", max + 1)
            }
        )
    }

    fun renamePlaylist(id: Long, name: String) {
        writableDatabase.update("playlists", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        writableDatabase.delete("playlist_songs", "playlist_id=?", arrayOf(id.toString()))
        writableDatabase.delete("playlists", "id=?", arrayOf(id.toString()))
    }

    fun playlists(): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT id, name FROM playlists ORDER BY position, name", null)?.use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1))
        }
        return out
    }

    /** Persist a user drag-reordered playlist list. */
    fun reorderPlaylists(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { i, id ->
                db.execSQL("UPDATE playlists SET position=? WHERE id=?", arrayOf(i.toLong(), id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun songsIn(playlistId: Long): List<Long> {
        val out = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT media_id FROM playlist_songs WHERE playlist_id=? ORDER BY position",
            arrayOf(playlistId.toString())
        )?.use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    fun addToPlaylist(playlistId: Long, mediaIds: List<Long>) {
        val db = writableDatabase
        var pos = db.compileStatement("SELECT COALESCE(MAX(position),-1)+1 FROM playlist_songs WHERE playlist_id=$playlistId")
            .simpleQueryForLong()
        db.beginTransaction()
        try {
            mediaIds.forEach { id ->
                db.execSQL(
                    "INSERT INTO playlist_songs(playlist_id, media_id, position) VALUES(?,?,?)",
                    arrayOf(playlistId, id, pos++)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun removeFromPlaylist(playlistId: Long, mediaId: Long) {
        writableDatabase.delete("playlist_songs", "playlist_id=? AND media_id=?", arrayOf(playlistId.toString(), mediaId.toString()))
    }

    /** Persist a user drag-reordered playlist. */
    fun reorderPlaylist(playlistId: Long, mediaIds: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("playlist_songs", "playlist_id=?", arrayOf(playlistId.toString()))
            mediaIds.forEachIndexed { i, id ->
                db.execSQL(
                    "INSERT INTO playlist_songs(playlist_id, media_id, position) VALUES(?,?,?)",
                    arrayOf(playlistId, id, i.toLong())
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- per-device boost memory ----
    fun putBoost(device: String, mult: Double) {
        writableDatabase.insertWithOnConflict(
            "boost", null,
            ContentValues().apply {
                put("device_name", device)
                put("gain_mult", mult)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getBoost(device: String, default: Double): Double {
        readableDatabase.rawQuery("SELECT gain_mult FROM boost WHERE device_name=?", arrayOf(device))?.use { c ->
            if (c.moveToFirst()) return c.getDouble(0)
        }
        return default
    }

    // ---- lifetime play counts (Smart Shuffle + stats) ----
    fun bumpPlay(mediaId: Long) {
        writableDatabase.execSQL(
            "INSERT INTO plays(media_id, count) VALUES(?,1) ON CONFLICT(media_id) DO UPDATE SET count=count+1",
            arrayOf(mediaId)
        )
        writableDatabase.execSQL(
            "INSERT INTO plays_total(media_id, count) VALUES(?,1) ON CONFLICT(media_id) DO UPDATE SET count=count+1",
            arrayOf(mediaId)
        )
    }

    fun playCounts(): Map<Long, Int> {
        val m = mutableMapOf<Long, Int>()
        readableDatabase.rawQuery("SELECT media_id, count FROM plays", null)?.use { c ->
            while (c.moveToNext()) m[c.getLong(0)] = c.getInt(1)
        }
        return m
    }

    fun resetPlays() = writableDatabase.delete("plays", null, null)

    fun clearTotals() = writableDatabase.delete("plays_total", null, null)

    /** Never reset. Total plays per song since install. */
    fun totalPlays(): Map<Long, Int> {
        val m = mutableMapOf<Long, Int>()
        readableDatabase.rawQuery("SELECT media_id, count FROM plays_total", null)?.use { c ->
            while (c.moveToNext()) m[c.getLong(0)] = c.getInt(1)
        }
        return m
    }

    // ---- skip segments (per song, milliseconds) ----
    fun segmentsFor(mediaId: Long): List<com.blissplayer.music.model.Seg> {
        val out = mutableListOf<com.blissplayer.music.model.Seg>()
        readableDatabase.rawQuery(
            "SELECT id, start_ms, end_ms, label FROM segments WHERE media_id=? ORDER BY start_ms",
            arrayOf(mediaId.toString())
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    com.blissplayer.music.model.Seg(
                        c.getLong(0), mediaId, c.getLong(1), c.getLong(2), c.getString(3) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun addSegment(mediaId: Long, startMs: Long, endMs: Long, label: String): Long =
        writableDatabase.insert(
            "segments", null,
            ContentValues().apply {
                put("media_id", mediaId)
                put("start_ms", startMs)
                put("end_ms", endMs)
                put("label", label)
            }
        )

    fun updateSegment(id: Long, startMs: Long, endMs: Long, label: String) {
        writableDatabase.update(
            "segments",
            ContentValues().apply {
                put("start_ms", startMs)
                put("end_ms", endMs)
                put("label", label)
            },
            "id=?", arrayOf(id.toString())
        )
    }

    fun deleteSegment(id: Long) {
        writableDatabase.delete("segments", "id=?", arrayOf(id.toString()))
    }

    // ---- genre per song (Uncategorized when nothing set) ----
    fun getGenre(mediaId: Long): String {
        readableDatabase.rawQuery("SELECT genre FROM song_genre WHERE media_id=?", arrayOf(mediaId.toString()))?.use { c ->
            if (c.moveToFirst()) return c.getString(0)?.takeIf { it.isNotBlank() } ?: "Uncategorized"
        }
        return "Uncategorized"
    }

    fun setGenre(mediaId: Long, genre: String) {
        writableDatabase.insertWithOnConflict(
            "song_genre", null,
            ContentValues().apply {
                put("media_id", mediaId)
                put("genre", genre.ifBlank { "Uncategorized" })
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun allGenres(): Map<Long, String> {
        val m = mutableMapOf<Long, String>()
        readableDatabase.rawQuery("SELECT media_id, genre FROM song_genre", null)?.use { c ->
            while (c.moveToNext()) m[c.getLong(0)] = c.getString(1)
        }
        return m
    }

    // ---- display album per song. Folder names like "Music" are never real
    // albums: an override here beats the file tag for browsing only.
    fun getAlbum(mediaId: Long): String? {
        readableDatabase.rawQuery("SELECT album FROM song_album WHERE media_id=?", arrayOf(mediaId.toString()))?.use { c ->
            if (c.moveToFirst()) return c.getString(0)?.takeIf { it.isNotBlank() }
        }
        return null
    }

    fun setAlbum(mediaId: Long, album: String) {
        if (album.isBlank()) {
            writableDatabase.delete("song_album", "media_id=?", arrayOf(mediaId.toString()))
            return
        }
        writableDatabase.insertWithOnConflict(
            "song_album", null,
            ContentValues().apply {
                put("media_id", mediaId)
                put("album", album)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun allAlbums(): Map<Long, String> {
        val m = mutableMapOf<Long, String>()
        readableDatabase.rawQuery("SELECT media_id, album FROM song_album", null)?.use { c ->
            while (c.moveToNext()) m[c.getLong(0)] = c.getString(1)
        }
        return m
    }

    // ---- Wikipedia lookup cache (analyzer): keyed by normalized
    // title+artist so repeat runs never re-hit the network. Misses are
    // stored too, so obscure songs cost one lookup ever.
    fun getLookup(key: String): SongAnalyzer.CachedLookup? {
        readableDatabase.rawQuery(
            "SELECT title, artist, genre, album, pageid, hittitle, high, confirmed, found FROM lookup_cache WHERE key=?",
            arrayOf(key)
        )?.use { c ->
            if (c.moveToFirst()) {
                return SongAnalyzer.CachedLookup(
                    c.getString(0) ?: "", c.getString(1) ?: "",
                    c.getString(2)?.takeIf { it.isNotEmpty() },
                    c.getString(3)?.takeIf { it.isNotEmpty() },
                    c.getLong(4), c.getString(5) ?: "",
                    c.getInt(6) != 0, c.getInt(7) != 0, c.getInt(8) != 0
                )
            }
        }
        return null
    }

    fun putLookup(key: String, hit: SongAnalyzer.CachedLookup) {
        writableDatabase.insertWithOnConflict(
            "lookup_cache", null,
            ContentValues().apply {
                put("key", key)
                put("title", hit.title)
                put("artist", hit.artist)
                put("genre", hit.genre ?: "")
                put("album", hit.album ?: "")
                put("pageid", hit.pageId)
                put("hittitle", hit.hitTitle)
                put("high", if (hit.high) 1 else 0)
                put("confirmed", if (hit.titleConfirmed) 1 else 0)
                put("found", if (hit.found) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ---- loudness measure cache (Equalize) ----
    fun putMeasure(mediaId: Long, rms: Double, modified: Long) {
        writableDatabase.insertWithOnConflict(
            "measure", null,
            ContentValues().apply {
                put("media_id", mediaId)
                put("rms", rms)
                put("modified", modified)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ---- user boost presets (name + percent, 1 to 500) ----
    fun presets(): List<Pair<String, Double>> {
        val out = mutableListOf<Pair<String, Double>>()
        readableDatabase.rawQuery("SELECT name, gain_mult FROM preset ORDER BY name", null)?.use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getDouble(1))
        }
        return out
    }

    fun putPreset(name: String, mult: Double) {
        writableDatabase.insertWithOnConflict(
            "preset", null,
            ContentValues().apply {
                put("name", name)
                put("gain_mult", mult)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deletePreset(name: String) {
        writableDatabase.delete("preset", "name=?", arrayOf(name))
    }

    // ---- loudness measure cache (Equalize) ----
    fun getMeasures(): Map<Long, Pair<Double, Long>> {
        val m = mutableMapOf<Long, Pair<Double, Long>>()
        readableDatabase.rawQuery("SELECT media_id, rms, modified FROM measure", null)?.use { c ->
            while (c.moveToNext()) m[c.getLong(0)] = c.getDouble(1) to c.getLong(2)
        }
        return m
    }
}
