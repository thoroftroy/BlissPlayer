package com.blissplayer.desktop

import com.blissplayer.core.AnalyzerRules
import com.blissplayer.core.PlaybackRules
import com.blissplayer.music.data.MusicBrainzLookup
import com.blissplayer.music.data.WikiLookup
import com.blissplayer.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Desktop analyzer: same two layers as Android (core local cleanup, then
 * MusicBrainz + Wikipedia via the shared JVM lookups). Tags persist through
 * DesktopTags, answers through DesktopDb's lookup cache. Runs off the UI
 * thread with phase progress ("names" then "lookup").
 */
class DesktopAnalyzer(
    private val db: DesktopDb,
    private val settings: DesktopSettings
) {
    data class Outcome(
        val proposals: List<AnalyzerRules.Proposal>,
        /** mediaId -> tags actually written. */
        val applied: Map<Long, Triple<String, String, String>>
    )

    private fun SongRow.toSong(): Song = Song(
        mediaId, path, path, title, artist, album, durationMs, sizeBytes, null, modified
    )

    suspend fun analyze(
        rows: List<SongRow>,
        online: Boolean,
        onProgress: (done: Int, total: Int, phase: String) -> Unit
    ): List<AnalyzerRules.Proposal> = coroutineScope {
        val out = mutableListOf<AnalyzerRules.Proposal>()
        val mids = mutableListOf<Mid>()
        rows.forEachIndexed { i, r ->
            val base = r.path.substringAfterLast('/').substringBeforeLast('.').ifBlank { r.title }
            val c = AnalyzerRules.cleanLocal(r.title, r.artist, base)
            val missing = AnalyzerRules.isUnknownArtist(c.artist) || db.genreOf(r.mediaId) == "Uncategorized"
            if (c.title.isNotBlank() && (c.reasons.isNotEmpty() || online && missing)) {
                var altT = ""; var altA = ""
                if (c.artist.isBlank() || AnalyzerRules.isUnknownArtist(c.artist)) {
                    val variants = AnalyzerRules.splitVariants(base.ifBlank { r.title })
                    val primary = c.artist to c.title
                    variants.firstOrNull { it != primary }?.let { altT = it.second; altA = it.first }
                }
                mids.add(Mid(r, c.title, c.artist, altT, altA, c.reasons.toMutableList(), c.safe, null, null, c.versioned))
            }
            if (i % 25 == 0) onProgress(i + 1, rows.size, "names")
        }
        onProgress(rows.size, rows.size, "names")
        if (online && mids.isNotEmpty()) {
            val offline = try { !WikiLookup.reachable() } catch (e: Exception) { true }
            if (!offline) {
                val sem = Semaphore(4)
                mids.map { m ->
                    async(Dispatchers.IO) {
                        sem.withPermit { lookupOne(m) }
                        synchronized(out) { onProgress(out.size + 1, mids.size, "lookup") }
                    }
                }.awaitAll()
            }
        }
        mids.mapNotNull { m ->
            if (m.title.isBlank()) return@mapNotNull null
            val g0 = db.genreOf(m.row.mediaId)
            val album = m.wikiAlbum ?: db.albumOf(m.row.mediaId)
            val effAlbum = if (album == null && AnalyzerRules.isPlaceholderAlbum(db.albumOf(m.row.mediaId) ?: "", m.row.path)) "Unknown album" else album
            if (m.title == m.row.title && m.artist.ifBlank { m.row.artist } == m.row.artist &&
                (effAlbum ?: db.albumOf(m.row.mediaId) ?: "") == (db.albumOf(m.row.mediaId) ?: "") &&
                g0 != "Uncategorized" && m.wikiGenre == null
            ) return@mapNotNull null
            AnalyzerRules.Proposal(m.row.toSong(), m.title, m.artist.ifBlank { m.row.artist },
                effAlbum ?: "Unknown album", m.wikiGenre ?: g0,
                if (m.high) "High" else "Check", m.reasons.joinToString(", "))
        }.also { out.addAll(it) }
    }

    private data class Mid(
        val row: SongRow, var title: String, var artist: String,
        val altTitle: String, val altArtist: String,
        val reasons: MutableList<String>, var high: Boolean,
        var wikiGenre: String?, var wikiAlbum: String?, val versioned: Boolean
    )

    private suspend fun lookupOne(m: Mid) {
        if (m.versioned) return
        val tried = mutableListOf(m.title to m.artist)
        if (m.altArtist.isNotBlank()) tried.add(m.altTitle to m.altArtist)
        if (!AnalyzerRules.isUnknownArtist(m.artist)) tried.add(m.title to "")
        for ((tt, aa) in tried) {
            val key = AnalyzerRules.cacheKey(tt, aa)
            val memo = db.getLookup(key)
            if (memo != null && !memo.found) continue
            if (memo != null && memo.found) {
                applyMemo(m, memo, tt, aa); return
            }
            val mb = runCatching {
                withContext(Dispatchers.IO) { MusicBrainzLookup.search(tt, aa) }
            }.getOrNull().orEmpty()
            val hit = mb.firstOrNull { com.blissplayer.music.data.MusicBrainzLookup.baseTitle(it.track).equals(
                com.blissplayer.music.data.MusicBrainzLookup.baseTitle(m.title), ignoreCase = true) }
            if (hit != null) {
                val genre = hit.genre
                val album = hit.album?.takeIf { !AnalyzerRules.isBadAlbumTitle(it) }
                if (m.artist.isBlank() || AnalyzerRules.isUnknownArtist(m.artist)) {
                    m.artist = hit.artist; m.reasons.add("Online artist")
                }
                if (genre != null && db.genreOf(m.row.mediaId) == "Uncategorized") {
                    m.wikiGenre = genre; m.reasons.add("Online genre")
                }
                if (album != null) { m.wikiAlbum = album; m.reasons.add("Online album") }
                m.high = true
                db.putLookup(key, DesktopDb.LookupHit(tt, aa, genre ?: "", album ?: "", -1, hit.track, true, true, true))
                db.learnArtist(AnalyzerRules.artistKey(aa.ifBlank { m.artist }), hit.artist)
                return
            }
            val page = runCatching {
                withContext(Dispatchers.IO) { WikiLookup.searchPageId(tt, aa) }
            }.getOrNull() ?: continue
            val details = runCatching {
                withContext(Dispatchers.IO) { WikiLookup.pageDetails(page) }
            }.getOrNull()
            val genre = details?.let { AnalyzerRules.genreFromCategories(it.first) }
            val album = details?.second?.album?.takeIf { !AnalyzerRules.isBadAlbumTitle(it) }
            m.high = true
            if (genre != null && db.genreOf(m.row.mediaId) == "Uncategorized") {
                m.wikiGenre = genre; m.reasons.add("Online genre")
            }
            if (album != null) { m.wikiAlbum = album; m.reasons.add("Online album") }
            db.putLookup(key, DesktopDb.LookupHit(tt, aa, genre ?: "", album ?: "", page, tt, true, true, true))
            return
        }
        db.putLookup(AnalyzerRules.cacheKey(m.title, m.artist),
            DesktopDb.LookupHit(m.title, m.artist, "", "", -1, "", false, false, false))
    }

    private fun applyMemo(m: Mid, memo: DesktopDb.LookupHit, tt: String, aa: String) {
        if (memo.artist.isNotBlank() && memo.artist != aa && (AnalyzerRules.isUnknownArtist(m.artist))) {
            m.artist = memo.artist; m.reasons.add("Online artist")
        }
        if (memo.genre.isNotBlank() && db.genreOf(m.row.mediaId) == "Uncategorized") {
            m.wikiGenre = memo.genre; m.reasons.add("Online genre")
        }
        if (memo.album.isNotBlank() && !AnalyzerRules.isBadAlbumTitle(memo.album)) {
            m.wikiAlbum = memo.album; m.reasons.add("Online album")
        }
        m.high = memo.high
    }

    /** Write proposal tags via DesktopTags + DesktopDb. Returns error or null. */
    fun apply(p: AnalyzerRules.Proposal): String? {
        val err = DesktopTags.write(p.song.contentUri, com.blissplayer.core.TagData(
            p.newTitle, p.newArtist, p.newAlbum, p.newGenre.takeIf { it != "Uncategorized" } ?: ""))
        if (err != null) return err
        db.setGenre(p.song.mediaId, p.newGenre)
        db.setAlbum(p.song.mediaId, p.newAlbum)
        db.markAnalyzed(p.song.mediaId, "${p.song.sizeBytes}:${p.song.modified}")
        return null
    }

    fun startPosition(rowId: Long, resumeMs: Long, segments: List<Pair<Long, Long>>): Long =
        PlaybackRules.startPosition(segments, resumeMs)
}

/** Portable desktop library row (mediaId = stable path hash). */
data class SongRow(
    val mediaId: Long,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val modified: Long
)
