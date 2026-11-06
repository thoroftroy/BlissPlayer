package com.blissplayer.core

import com.blissplayer.music.model.Song
import com.blissplayer.music.model.SongNames

/**
 * Library query logic shared by Android and Linux: sort orders, narrowing
 * (search + filter chips + playlist scope) and canonical grouping. Android's
 * fuzzy typo-tolerant ranking stays platform-side; everything here is
 * deterministic and unit-tested, so both libraries order identically.
 */
object LibraryQueries {

    /** Verbatim the sort orders both platforms offer (AZ default). */
    fun sortSongs(
        songs: List<Song>,
        key: String,
        plays: Map<Long, Int> = emptyMap(),
        albumOf: (Song) -> String = { it.album }
    ): List<Song> {
        return when (key) {
            "ZA" -> songs.sortedByDescending { it.sortTitle }
            "Artist" -> songs.sortedWith(compareBy({ SongNames.sortKey(it.artist) }, { SongNames.sortKey(albumOf(it)) }, { it.sortTitle }))
            "Album" -> songs.sortedWith(compareBy({ SongNames.sortKey(albumOf(it)) }, { it.sortTitle }))
            "Longest" -> songs.sortedByDescending { it.durationMs }
            "Shortest" -> songs.sortedBy { it.durationMs }
            "MostPlayed" -> songs.sortedByDescending { plays[it.mediaId] ?: 0 }
            "LeastPlayed" -> songs.sortedBy { plays[it.mediaId] ?: 0 }
            else -> songs.sortedBy { it.sortTitle }
        }
    }

    /**
     * Narrow by exact-substring chips (artist/album/genre/playlist scope) plus
     * a plain contains query over title/artist/album/path. Case- and
     * accent-blind via normalized comparison.
     */
    fun narrow(
        songs: List<Song>,
        query: String,
        artistSub: String = "",
        albumSub: String = "",
        genre: String = "",
        playlistIds: Set<Long>? = null,
        genreOf: (Long) -> String = { "" },
        albumOf: (Long) -> String? = { null }
    ): List<Song> {
        val q = norm(query)
        val fA = norm(artistSub)
        val fB = norm(albumSub)
        val fG = norm(genre)
        return songs.filter { s ->
            if (playlistIds != null && s.mediaId !in playlistIds) return@filter false
            if (fA.isNotEmpty() && !norm(s.artist).contains(fA)) return@filter false
            if (fB.isNotEmpty() && !norm(albumOf(s.mediaId) ?: s.album).contains(fB)) return@filter false
            if (fG.isNotEmpty() && norm(genreOf(s.mediaId)) != fG) return@filter false
            if (q.isEmpty()) return@filter true
            norm(s.title).contains(q) || norm(s.artist).contains(q) ||
                norm(albumOf(s.mediaId) ?: s.album).contains(q) ||
                norm(s.filePath ?: "").contains(q)
        }
    }

    /** Canonical artist groups (years/casing/version words merged), display names kept. */
    fun groupArtists(songs: List<Song>, artistOf: (Song) -> List<String>): Map<String, List<Song>> {
        val out = mutableMapOf<String, MutableList<Song>>()
        val display = mutableMapOf<String, String>()
        for (s in songs) {
            for (a in artistOf(s)) {
                val key = AnalyzerRules.artistMergeKey(a)
                out.getOrPut(key) { mutableListOf() }.add(s)
                val cur = display[key]
                display[key] = AnalyzerRules.preferArtistDisplay(cur, a)
            }
        }
        return out.entries.sortedBy { it.key }.associate { display[it.key]!! to it.value }
    }

    /** Canonical album groups (original releases, never live/compilations). */
    fun groupAlbums(songs: List<Song>, albumOf: (Song) -> String): Map<String, List<Song>> {
        val out = mutableMapOf<String, MutableList<Song>>()
        val display = mutableMapOf<String, String>()
        for (s in songs) {
            val raw = albumOf(s)
            val key = AnalyzerRules.albumKey(raw)
            out.getOrPut(key) { mutableListOf() }.add(s)
            if (display[key] == null) display[key] = AnalyzerRules.canonicalAlbum(raw)
        }
        return out.entries.sortedBy { it.key }.associate { display[it.key]!! to it.value }
    }

    private fun norm(s: String): String = s.lowercase().trim()
}
