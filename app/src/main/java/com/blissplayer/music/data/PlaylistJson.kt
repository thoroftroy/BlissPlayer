package com.blissplayer.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Playlists live in SQLite for speed, but every change is also mirrored to
 * JSON files in the app working directory under files-playlists, so they
 * survive reinstalls, can be copied, and satisfy the JSON requirement.
 * On launch, any JSON file missing from the DB is reimported.
 */
object PlaylistJson {

    private fun dir(context: Context): File =
        File(context.filesDir, "playlists").apply { mkdirs() }

    private fun safeName(name: String): String {
        val s = name.trim().ifBlank { "Playlist" }
        return s.map { c -> if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') '_' else c }
            .joinToString("").take(80)
    }

    fun exportAll(context: Context, db: BlissDb) {
        try {
            val d = dir(context)
            // Remove stale files for deleted playlists.
            val live = db.playlists().associate { it.first to it.second }
            d.listFiles()?.forEach { f ->
                if (f.extension == "json") {
                    val id = f.nameWithoutExtension.substringBefore('_').toLongOrNull()
                    if (id == null || id !in live) runCatching { f.delete() }
                }
            }
            live.forEach { (id, name) ->
                val ids = db.songsIn(id)
                val obj = JSONObject()
                obj.put("id", id)
                obj.put("name", name)
                val arr = JSONArray()
                ids.forEach { arr.put(it) }
                obj.put("songs", arr)
                obj.put("exported", System.currentTimeMillis())
                val file = File(d, "${id}_${safeName(name)}.json")
                // Avoid rewriting identical files (saves battery on big libs).
                val text = obj.toString(1)
                val existing = runCatching { file.readText() }.getOrNull()
                if (existing != text) file.writeText(text)
            }
        } catch (e: Exception) {
            // Export is best effort; DB remains the source of truth.
        }
    }

    fun importMissing(context: Context, db: BlissDb) {
        try {
            val d = dir(context)
            if (!d.exists()) return
            val existingIds = db.playlists().map { it.first }.toSet()
            d.listFiles()?.filter { it.extension == "json" }?.forEach { f ->
                try {
                    val obj = JSONObject(f.readText())
                    val id = obj.optLong("id", -1)
                    val name = obj.optString("name", "").ifBlank { f.nameWithoutExtension }
                    if (id in existingIds) return@forEach
                    val arr = obj.optJSONArray("songs") ?: JSONArray()
                    val ids = (0 until arr.length()).mapNotNull { runCatching { arr.getLong(it) }.getOrNull() }
                    // Recreate with the same songs; the numeric id may differ
                    // after reinstall, but order and contents are preserved.
                    val newId = db.createPlaylist(name)
                    if (ids.isNotEmpty()) db.addToPlaylist(newId, ids)
                } catch (e: Exception) {
                    // Skip corrupt files.
                }
            }
        } catch (e: Exception) {
        }
    }

    fun exportPath(context: Context): String =
        File(context.filesDir, "playlists").absolutePath
}
