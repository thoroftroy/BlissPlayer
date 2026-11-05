package com.blissplayer.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable playlist format (v1), shared by Android and Linux.
 *
 * Identity is platform-neutral on purpose: relative path + filename +
 * metadata snapshot (title/artist/album/duration). Android numeric mediaIds
 * and content URIs, or Linux absolute paths, travel only as `sourceHint`
 * and are never trusted for re-linking.
 *
 * Missing policy is KEEP_MISSING everywhere: unresolved entries survive as
 * placeholders with their stored metadata. They never break queue or
 * playback; the UI shows them as unavailable until relinked.
 */
object PlaylistPortable {

    const val FORMAT_VERSION = 1

    data class Entry(
        val relativePath: String,
        val fileName: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val sourceHint: String = ""
    )

    data class Playlist(
        val name: String,
        val exported: Long,
        val entries: List<Entry>,
        /** Favorite snapshots included iff shareFavorites was on at export. */
        val favorites: List<Entry> = emptyList()
    )

    /** Minimal library view needed to re-link. Both platforms adapt to this. */
    data class LibTrack(
        val relativePath: String,
        val fileName: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    )

    data class Relinked<T>(
        val found: List<Pair<Entry, T>>,
        val missing: List<Entry>
    )

    fun export(name: String, entries: List<Entry>, favorites: List<Entry>): String {
        val obj = JSONObject()
        obj.put("formatVersion", FORMAT_VERSION)
        obj.put("name", name)
        obj.put("exported", System.currentTimeMillis())
        obj.put("entries", JSONArray(entries.map(::entryToJson)))
        obj.put("favorites", JSONArray(favorites.map(::entryToJson)))
        return obj.toString(1)
    }

    fun parse(text: String): Playlist? = runCatching {
        val obj = JSONObject(text)
        if (obj.optInt("formatVersion", -1) != FORMAT_VERSION) return null
        val name = obj.optString("name", "").ifBlank { return null }
        fun read(key: String): List<Entry> {
            val arr = obj.optJSONArray(key) ?: JSONArray()
            return (0 until arr.length()).mapNotNull {
                runCatching { jsonToEntry(arr.getJSONObject(it)) }.getOrNull()
            }
        }
        Playlist(name, obj.optLong("exported", 0), read("entries"), read("favorites"))
    }.getOrNull()

    /**
     * Deterministic re-link: exact relative-path match first, then a
     * filename + normalized title/artist match with duration within 5s.
     * Everything else is missing (kept, never dropped).
     */
    fun <T> relink(
        entries: List<Entry>,
        library: List<T>,
        trackOf: (T) -> LibTrack
    ): Relinked<T> {
        val found = mutableListOf<Pair<Entry, T>>()
        val missing = mutableListOf<Entry>()
        val byPath = library.associateBy { normPath(trackOf(it).relativePath) }
        val byFile = library.groupBy { trackOf(it).fileName.lowercase() }
        for (e in entries) {
            val exact = byPath[normPath(e.relativePath)]
            if (exact != null) {
                found.add(e to exact)
                continue
            }
            val cands = byFile[e.fileName.lowercase()].orEmpty()
            val fuzzy = cands.firstOrNull { t ->
                val lt = trackOf(t)
                normMeta(lt.title) == normMeta(e.title) &&
                    normMeta(lt.artist) == normMeta(e.artist) &&
                    kotlin.math.abs(lt.durationMs - e.durationMs) <= 5000
            } ?: cands.firstOrNull { t ->
                val lt = trackOf(t)
                normMeta(lt.title) == normMeta(e.title) &&
                    kotlin.math.abs(lt.durationMs - e.durationMs) <= 5000
            }
            if (fuzzy != null) found.add(e to fuzzy) else missing.add(e)
        }
        return Relinked(found, missing)
    }

    private fun entryToJson(e: Entry): JSONObject = JSONObject()
        .put("relativePath", e.relativePath)
        .put("fileName", e.fileName)
        .put("title", e.title)
        .put("artist", e.artist)
        .put("album", e.album)
        .put("durationMs", e.durationMs)
        .put("sourceHint", e.sourceHint)

    private fun jsonToEntry(o: JSONObject): Entry? {
        val fileName = o.optString("fileName", "").ifBlank { return null }
        return Entry(
            o.optString("relativePath", ""),
            fileName,
            o.optString("title", fileName),
            o.optString("artist", ""),
            o.optString("album", ""),
            o.optLong("durationMs", 0),
            o.optString("sourceHint", "")
        )
    }

    private fun normPath(p: String): String =
        p.trim().replace('\\', '/').lowercase()

    private fun normMeta(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.split(' ')
            .filter { it.isNotBlank() }.joinToString(" ")
}
