package com.blissplayer.music.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Tiny hand-rolled SQLite store (no Room — keeps APK small and battery use flat).
 * Tables:
 *  gains(media_id PRIMARY KEY, gain_mult)      — baked loudness-normalize factors
 *  playlists(id, name) + playlist_songs(playlist_id, media_id, position)
 *  boost(device_name PRIMARY KEY, gain_mult)   — per-device amp memory (car vs headphones)
 */
class BlissDb(context: Context) : SQLiteOpenHelper(context, "bliss.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE gains(media_id INTEGER PRIMARY KEY, gain_mult REAL NOT NULL)")
        db.execSQL("CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE playlist_songs(playlist_id INTEGER NOT NULL, media_id INTEGER NOT NULL, position INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_ps ON playlist_songs(playlist_id, position)")
        db.execSQL("CREATE TABLE boost(device_name TEXT PRIMARY KEY, gain_mult REAL NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) db.execSQL("CREATE TABLE IF NOT EXISTS boost(device_name TEXT PRIMARY KEY, gain_mult REAL NOT NULL)")
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
    fun createPlaylist(name: String): Long =
        writableDatabase.insert("playlists", null, ContentValues().apply { put("name", name) })

    fun renamePlaylist(id: Long, name: String) {
        writableDatabase.update("playlists", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        writableDatabase.delete("playlist_songs", "playlist_id=?", arrayOf(id.toString()))
        writableDatabase.delete("playlists", "id=?", arrayOf(id.toString()))
    }

    fun playlists(): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT id, name FROM playlists ORDER BY name", null)?.use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1))
        }
        return out
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
}
