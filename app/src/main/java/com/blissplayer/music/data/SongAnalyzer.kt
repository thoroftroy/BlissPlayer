package com.blissplayer.music.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.blissplayer.core.AnalyzerRules
import com.blissplayer.core.AnalyzerRules.Cleaned
import com.blissplayer.core.AnalyzerRules.Proposal
import com.blissplayer.music.model.Song
import com.blissplayer.music.model.SongNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Song analyzer + organizer.
 *
 * YouTube downloads arrive with messy names and no tags, so cleanup works
 * in two layers. The local layer strips video noise: (Official Video),
 * (Lyrics), [videoIds], M/V, VEVO, leading NA, and Artist - Title splits.
 * The online layer asks Wikipedia (no key needed) for the canonical title,
 * performer, and genre words. Online hits only apply when the lookup
 * clearly matches; anything unsure lands in a confirm-first list.
 * Without internet the analyzer says so and cleans names offline.
 *
 * Organize moves only enabled-folder songs into Music/BlissPlayer/Artist
 * /Album through MediaStore. Playlists key on mediaId, so they survive.
 */
object SongAnalyzer {

    /**
     * One Wikipedia answer, cached by normalized title+artist so repeat runs
     * never re-hit the network. Misses are cached too: obscure songs cost
     * one lookup ever.
     */
    data class CachedLookup(
        val title: String, val artist: String,
        val genre: String?, val album: String?,
        val pageId: Long, val hitTitle: String,
        val high: Boolean, val titleConfirmed: Boolean,
        val found: Boolean
    )

    /** Persistent lookup answers. BlissDb implements this; tests pass null. */
    interface LookupCache {
        fun get(title: String, artist: String): CachedLookup?
        fun put(title: String, artist: String, hit: CachedLookup)
    }

    fun cacheKey(title: String, artist: String): String =
        com.blissplayer.core.AnalyzerRules.cacheKey(title, artist)



    fun stripLeadingJunk(artist: String): Pair<String, Boolean> =
        com.blissplayer.core.AnalyzerRules.stripLeadingJunk(artist)



    fun stripEmoji(s: String): String = com.blissplayer.core.AnalyzerRules.stripEmoji(s)


    fun stripVideoId(title: String): Pair<String, Boolean> =
        com.blissplayer.core.AnalyzerRules.stripVideoId(title)



    fun splitVariants(base: String): List<Pair<String, String>> =
        com.blissplayer.core.AnalyzerRules.splitVariants(base)

    fun isUnknownArtist(artist: String): Boolean =
        com.blissplayer.core.AnalyzerRules.isUnknownArtist(artist)

    fun isPlaceholderAlbum(album: String, filePath: String?): Boolean =
        com.blissplayer.core.AnalyzerRules.isPlaceholderAlbum(album, filePath)

    /** Folders, blanks, and live/compilation/version packaging: never used or kept as an album. */
    fun isIgnoredAlbum(album: String, filePath: String?): Boolean =
        com.blissplayer.core.AnalyzerRules.isIgnoredAlbumTitle(album, filePath)




    fun normNoSpace(s: String): String = com.blissplayer.core.AnalyzerRules.normNoSpace(s)
    fun canonicalArtist(raw: String): String =
        com.blissplayer.core.AnalyzerRules.canonicalArtist(raw)

    fun artistKey(raw: String): String = com.blissplayer.core.AnalyzerRules.artistKey(raw)

    fun artistMergeKey(raw: String): String =
        com.blissplayer.core.AnalyzerRules.artistMergeKey(raw)

    fun preferArtistDisplay(old: String?, new: String): String =
        com.blissplayer.core.AnalyzerRules.preferArtistDisplay(old, new)

    fun canonicalAlbum(raw: String): String =
        com.blissplayer.core.AnalyzerRules.canonicalAlbum(raw)

    fun albumKey(raw: String): String = com.blissplayer.core.AnalyzerRules.albumKey(raw)

    fun splitArtists(displayArtist: String): List<String> =
        com.blissplayer.core.AnalyzerRules.splitArtists(displayArtist)

    fun artistMatches(local: String, wiki: String): Boolean =
        com.blissplayer.core.AnalyzerRules.artistMatches(local, wiki)

    fun cleanLocal(rawTitle: String, rawArtist: String, fileBase: String): Cleaned =
        com.blissplayer.core.AnalyzerRules.cleanLocal(rawTitle, rawArtist, fileBase)


    fun genreFromCategories(categories: List<String>): String? =
        com.blissplayer.core.AnalyzerRules.genreFromCategories(categories)

    fun propose(song: Song, fileName: String, currentGenre: String): Proposal? =
        com.blissplayer.core.AnalyzerRules.propose(song, fileName, currentGenre)

    /**
     * Full pass: local cleanup for every song, then Wikipedia for the
     * uncertain ones. Phases reported as "names" then "lookup" so the
     * progress bar reads clearly.
     */
    suspend fun analyze(
        songs: List<Song>,
        genreOf: (Long) -> String,
        albumOf: (Long) -> String,
        online: Boolean,
        lookupCache: LookupCache? = null,
        onProgress: (done: Int, total: Int, phase: String) -> Unit
    ): List<Proposal> {
        // Report on the caller's dispatcher (Main in the UI), never on the worker.
        val progressContext = currentCoroutineContext().minusKey(Job)
        return analyzeOnWorker(songs, genreOf, albumOf, online, lookupCache) { done, total, phase ->
            withContext(progressContext) { onProgress(done, total, phase) }
        }
    }

    private suspend fun analyzeOnWorker(
        songs: List<Song>, genreOf: (Long) -> String, albumOf: (Long) -> String, online: Boolean,
        lookupCache: LookupCache?,
        report: suspend (Int, Int, String) -> Unit
    ): List<Proposal> = withContext(Dispatchers.Default) {
        // Phase 1: local rules, fast. Mid lives at object level so the
        // parallel lookup step can take it.
        val mids = mutableListOf<Mid>()
        songs.forEachIndexed { i, s ->
            coroutineContext.ensureActive()
            val base = (s.filePath ?: "").substringAfterLast('/').substringBeforeLast('.')
                .ifBlank { s.title }
            val c = cleanLocal(s.title, s.artist, base)
            val missingMetadata = isUnknownArtist(c.artist) || genreOf(s.mediaId) == "Uncategorized" ||
                isIgnoredAlbum(albumOf(s.mediaId), s.filePath)
            if (c.title.isNotBlank() && (c.reasons.isNotEmpty() || online && missingMetadata)) {
                // Swapped reading for "Title - Artist" uploaders: tried online
                // when the primary reading is not confident.
                var altT = ""
                var altA = ""
                if (c.artist.isBlank() || isUnknownArtist(c.artist) || "split Artist - Title" in c.reasons ||
                    "moved artist from title" in c.reasons
                ) {
                    val variants = splitVariants(base.ifBlank { s.title })
                    val primary = c.artist to c.title
                    val swapped = variants.firstOrNull { it != primary }
                    if (swapped != null) {
                        altT = swapped.second
                        altA = swapped.first
                    } else if ("moved artist from title" in c.reasons && c.artist.isNotBlank()) {
                        // The offline guess may have the order backwards
                        // ("Stayin' Alive - Bee Gees" vs "Bill Withers - Ain't No
                        // Sunshine"): offer the flipped reading so the online
                        // index picks the winner instead of trusting the guess.
                        altT = c.artist
                        altA = c.title
                    }
                }
                mids.add(Mid(s, c.title, c.artist, altT, altA, c.reasons.toMutableList(), c.safe, null, null, c.versioned))
            }
            if (i % 25 == 0) report(i + 1, songs.size, "names")
        }
        report(songs.size, songs.size, "names")

        // Phase 2: Wikipedia confirmation + enrichment.
        // One cheap probe first: without network there is no point burning
        // minutes on per-song timeouts. MusicBrainz goes out batched (one
        // request covers 8 titles against its 1-request-per-second limit),
        // then songs finish in parallel with Wikipedia fallbacks. Answers
        // persist in the cache, so a thousand-song library costs one lookup
        // per unique song ever.
        var offline = false
        if (online && mids.isNotEmpty()) {
            offline = try {
                !WikiLookup.reachable()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                true
            }
            if (!offline) {
                // Memo pass (free): absorb cached answers, learn each song's
                // single network reading.
                val pending = mutableListOf<Triple<Mid, String, String>>()
                for (m in mids) {
                    coroutineContext.ensureActive()
                    if (m.versioned) continue
                    selectReading(m, genreOf, lookupCache)?.let { (tt, aa) ->
                        pending.add(Triple(m, tt, aa))
                    }
                }
                // Batched MusicBrainz: one request per 8 distinct titles.
                val mbByTitle = mutableMapOf<String, List<MusicBrainzLookup.Hit>>()
                var batched = 0
                pending.map { it.second }.distinct().chunked(8).forEach { titles ->
                    coroutineContext.ensureActive()
                    val hits = try {
                        MusicBrainzLookup.searchBatch(titles)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        emptyList()
                    }
                    // Distribute by exact title match, the same rule the
                    // per-song path enforces: each title's recordings land
                    // with the songs that asked for that title.
                    for (t in titles) {
                        val want = WikiLookup.norm(MusicBrainzLookup.baseTitle(t))
                        mbByTitle[t] = hits.filter {
                            WikiLookup.norm(MusicBrainzLookup.baseTitle(it.track)) == want
                        }
                    }
                    batched += titles.size
                    report(batched.coerceAtMost(mids.size), mids.size, "lookup")
                }
                // Per-song finish (12 workers overlap Wikipedia fetches).
                val sem = kotlinx.coroutines.sync.Semaphore(12)
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                pending.map { (m, tt, aa) ->
                    async {
                        sem.withPermit { finishWithMb(m, tt, aa, mbByTitle[tt] ?: emptyList(), genreOf, lookupCache) }
                        report(done.incrementAndGet(), mids.size, "lookup")
                    }
                }.awaitAll()
            }
        }
        report(songs.size, songs.size, "done")

        // Genre backfill: a song the index left blank takes its artist's
        // (then its album's) majority genre from categorized songs. Same
        // discography, same shelf: data-driven, never guessed.
        val genreByArtist = mutableMapOf<String, MutableMap<String, Int>>()
        val genreByAlbum = mutableMapOf<String, MutableMap<String, Int>>()
        for (m in mids) {
            val g = m.wikiGenre ?: genreOf(m.song.mediaId)
            if (g.isBlank() || g == "Uncategorized") continue
            genreByArtist.getOrPut(artistMergeKey(m.artist.ifBlank { m.song.artist })) { mutableMapOf() }
                .merge(g, 1, Int::plus)
            val alb = m.wikiAlbum ?: albumOf(m.song.mediaId)
            if (!isIgnoredAlbum(alb, m.song.filePath)) {
                genreByAlbum.getOrPut(albumKey(canonicalAlbum(alb))) { mutableMapOf() }
                    .merge(g, 1, Int::plus)
            }
        }
        for (m in mids) {
            if (m.wikiGenre != null) continue
            if (genreOf(m.song.mediaId).isNotBlank() && genreOf(m.song.mediaId) != "Uncategorized") continue
            val byArtist = genreByArtist[artistMergeKey(m.artist.ifBlank { m.song.artist })]
            val best = byArtist?.maxByOrNull { it.value }
            if (best != null) {
                m.wikiGenre = best.key
                m.reasons.add("Same artist")
                continue
            }
            val alb = m.wikiAlbum ?: albumOf(m.song.mediaId)
            if (!isIgnoredAlbum(alb, m.song.filePath)) {
                genreByAlbum[albumKey(canonicalAlbum(alb))]?.maxByOrNull { it.value }?.let {
                    m.wikiGenre = it.key
                    m.reasons.add("Same album")
                }
            }
        }

        val out = mids.mapNotNull { m ->
            val g0 = genreOf(m.song.mediaId)
            // Genre and album come only from the online index (or existing
            // tags): never guessed offline.
            val genre = m.wikiGenre ?: g0
            // A refused album tag (folder, live, best-of) is repaired to the
            // found original, or to Unknown when the index has no evidence.
            // A refused tag always differs, so it always proposes.
            val effAlbum0 = albumOf(m.song.mediaId)
            var album = m.wikiAlbum
            if (album == null && isIgnoredAlbum(effAlbum0, m.song.filePath)) {
                val fixed = com.blissplayer.core.AnalyzerRules.resolveAlbumTitle(effAlbum0)
                if (fixed != null && !fixed.equals(effAlbum0, ignoreCase = true)) {
                    album = fixed
                    if (m.reasons.none { it.startsWith("version packaging") }) {
                        m.reasons.add("version packaging grouped to the original release")
                    }
                } else {
                    album = "Unknown album"
                    if (isBadAlbumTitle(effAlbum0) && m.reasons.none { it.startsWith("live or compilation") }) {
                        m.reasons.add("live or compilation packaging ignored, original unknown")
                    }
                }
            }
            if (m.title.isBlank()) return@mapNotNull null
            if (m.title == m.song.title && m.artist.ifBlank { m.song.artist } == m.song.artist &&
                (album ?: effAlbum0) == effAlbum0 && genre.ifBlank { "Uncategorized" } == g0) return@mapNotNull null
            if (m.wikiAlbum != null && "Wikipedia album" !in m.reasons) {
                m.reasons.add("Wikipedia album")
            }
            Proposal(
                m.song, m.title, m.artist.ifBlank { m.song.artist },
                album ?: effAlbum0, genre.ifBlank { "Uncategorized" },
                if (m.high) "High" else "Check", m.reasons.joinToString(", ")
            )
        }.sortedWith(compareBy({ it.confidence != "High" }, { it.song.title.lowercase() }))
        if (offline) throw OfflineException(out)
        out
    }

    /** One song against the online index: cache first, then readings in order. */
    private data class Mid(
        val song: Song, var title: String, var artist: String,
        var altTitle: String, var altArtist: String,
        val reasons: MutableList<String>, var high: Boolean,
        var wikiGenre: String?, var wikiAlbum: String?, val versioned: Boolean
    )

    /** A folded answer from cache or network, ready to apply or skip. */
    private data class Folded(
        val title: String,
        /** Suggested performer, null when the tag's own artist stands. */
        val artist: String?,
        val genre: String?,
        val album: String?,
        val hitTitle: String,
        val pageId: Long,
        val high: Boolean,
        val titleConfirmed: Boolean,
        val reasons: List<String>
    )

    /** Tags that carry no performer information: anything may be adopted. */
    private fun artistSuspicious(artist: String): Boolean =
        com.blissplayer.core.AnalyzerRules.artistSuspicious(artist)

    /**
     * Genre/album adoption needs a corroborated title: a clear match, a
     * performer we already trust, a tag with no performer info, or a title
     * too distinctive to be another song ("Halo" stays cautious).
     */
    private fun corroborated(
        localArtist: String, snippetArtist: String?, title: String, high: Boolean
    ): Boolean {
        if (high) return true
        if (snippetArtist != null && artistMatches(localArtist, snippetArtist)) return true
        if (artistSuspicious(localArtist)) return true
        return title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size >= 2
    }

    /**
     * Fold MusicBrainz recording results the same shape as a Wikipedia hit:
     * exact title match required, performer adopted only from unknown or
     * matching tags, everything review-flagged otherwise. Local titles
     * stand (they are already cleaned); MusicBrainz supplies artist, genre
     * and album.
     */
    fun mbFold(
        localTitle: String, localArtist: String, currentGenre: String,
        hits: List<MusicBrainzLookup.Hit>,
        localDurationMs: Long = 0L
    ): WikiApplied? {
        if (hits.isEmpty() || localTitle.isBlank()) return null
        val want = WikiLookup.norm(MusicBrainzLookup.baseTitle(localTitle))
        if (want.isBlank()) return null
        val localUnknown = isUnknownArtist(localArtist)
        // Duration guard: same title but wildly different length is a different
        // recording. Downgrade to Check instead of claiming High.
        fun durationOk(h: MusicBrainzLookup.Hit): Boolean {
            val ref = h.lengthMs ?: return true
            if (localDurationMs <= 0) return true
            val diff = kotlin.math.abs(ref - localDurationMs)
            if (diff <= 15_000L) return true
            val ratio = minOf(ref, localDurationMs).toDouble() / maxOf(ref, localDurationMs).toDouble()
            return ratio >= 0.75
        }
        // Exact title first (unknown or matching performer), then title-only.
        var settled: WikiApplied? = null
        for (h in hits) {
            if (WikiLookup.norm(MusicBrainzLookup.baseTitle(h.track)) != want) continue
            val match = h.artist.isNotBlank() && (localUnknown || artistMatches(localArtist, h.artist))
            if (localUnknown || match) {
                val reasons = mutableListOf("MusicBrainz: ${h.track}")
                val genre: String? =
                    if (h.genre != null && (currentGenre.isBlank() || currentGenre == "Uncategorized")) h.genre
                    else null
                val artist = if (h.artist.isNotBlank()) h.artist else localArtist
                val high = durationOk(h)
                if (!high) reasons.add("duration differs, needs review")
                return WikiApplied(localTitle, artist, genre, reasons, high, true, h.artist)
            }
            if (settled == null && h.artist.isNotBlank()) {
                val reasons = mutableListOf("MusicBrainz title match (artist differs: ${h.artist})")
                val genre: String? =
                    if (h.genre != null && (currentGenre.isBlank() || currentGenre == "Uncategorized")) h.genre
                    else null
                settled = WikiApplied(localTitle, localArtist, genre, reasons, false, true, h.artist)
            }
        }
        return settled
    }

    /** Album from the title-matching MusicBrainz hits: studio releases
     * first, so live/concert and best-of titles never win when the
     * original album is right there. Null when everything is live. */
    private fun mbAlbum(localTitle: String, hits: List<MusicBrainzLookup.Hit>): String? {
        if (hits.isEmpty()) return null
        val want = WikiLookup.norm(MusicBrainzLookup.baseTitle(localTitle))
        if (want.isBlank()) return null
        val matching = hits.filter {
            WikiLookup.norm(MusicBrainzLookup.baseTitle(it.track)) == want
        }.mapNotNull { it.album?.let { a -> com.blissplayer.core.AnalyzerRules.resolveAlbumTitle(a) } }
        return matching.firstOrNull()
    }

    fun isBadAlbumTitle(title: String): Boolean =
        com.blissplayer.core.AnalyzerRules.isBadAlbumTitle(title)

    /**
     * Memo pass over one song's candidate readings (free): absorb cached
     * answers, and pick the single reading that goes to the network.
     * Returns null when a High memo settles the song or no reading is left.
     * A redo pass naturally tries the next untried reading because tried
     * ones leave memos behind.
     */
    private suspend fun selectReading(
        m: Mid, genreOf: (Long) -> String, cache: LookupCache?
    ): Pair<String, String>? {
        coroutineContext.ensureActive()
        val g0 = genreOf(m.song.mediaId)
        val primary = m.title to m.artist
        var fileReading: Pair<String, String>? = null
        runCatching {
            val base = (m.song.filePath ?: "").substringAfterLast('/').substringBeforeLast('.').trim()
            if (base.isNotBlank()) {
                val c = cleanLocal(base, "", base)
                if (c.title.isNotBlank() && (c.title to c.artist) != primary) {
                    fileReading = c.title to c.artist
                }
            }
        }
        val tagSuspicious = isUnknownArtist(m.artist) || artistSuspicious(m.artist)
        val ordered = mutableListOf<Pair<String, String>>()
        if (tagSuspicious) {
            fileReading?.let { ordered.add(it) }
            ordered.add(primary)
            m.altArtist.takeIf { it.isNotBlank() }?.let { aa ->
                m.altTitle.takeIf { it.isNotBlank() }?.let { at ->
                    if ((at to aa) !in ordered) ordered.add(at to aa)
                }
            }
        } else {
            ordered.add(primary)
            fileReading?.let { ordered.add(it) }
        }
        var netReading: Pair<String, String>? = null
        for ((tt, aa) in ordered) {
            coroutineContext.ensureActive()
            val memo = runCatching { cache?.get(tt, aa) }.getOrNull()
            if (memo == null) {
                if (netReading == null) netReading = tt to aa
                continue
            }
            if (!memo.found) continue
            val solo = aa.isBlank()
            val suggested = memo.artist.takeIf { it.isNotBlank() && it != aa }
            val f = Folded(
                memo.title, suggested, memo.genre, memo.album,
                memo.hitTitle, memo.pageId, memo.high, memo.titleConfirmed,
                if (memo.high) listOf("Online: ${memo.hitTitle}")
                else listOf("Online title match")
            )
            if (absorb(m, f, g0, solo)) return null
        }
        return netReading
    }

    /**
     * Finish one song with pre-batched MusicBrainz hits plus the Wikipedia
     * fallback. The same exact-title plus artist rules as the old per-song
     * path apply; only the transport is batched.
     */
    private suspend fun finishWithMb(
        m: Mid, tt: String, aa: String, mb: List<MusicBrainzLookup.Hit>,
        genreOf: (Long) -> String, cache: LookupCache?
    ) {
        coroutineContext.ensureActive()
        val g0 = genreOf(m.song.mediaId)
        val solo = aa.isBlank()
        val folded = mbFold(tt, aa, g0, mb, m.song.durationMs)
        if (folded != null) {
            val ok = corroborated(aa, folded.snippetArtist, tt, folded.high)
            if (ok) {
                val album = mbAlbum(tt, mb)
                // Genre backfill: MusicBrainz tags are sparse, so a
                // confirmed title without genre gets one Wikipedia
                // details call (cached with the answer).
                var mbGenre = folded.genre
                if (mbGenre == null && folded.titleConfirmed &&
                    (g0.isBlank() || g0 == "Uncategorized")
                ) {
                    mbGenre = wikiGenreFor(tt, aa)
                }
                runCatching {
                    cache?.put(
                        tt, aa, CachedLookup(
                            folded.title,
                            if (!solo && folded.titleConfirmed) folded.snippetArtist ?: folded.artist else folded.artist,
                            mbGenre, album, -1, folded.title,
                            folded.high && !solo, folded.titleConfirmed, true
                        )
                    )
                }
                val f = Folded(
                    folded.title,
                    if (!solo && folded.titleConfirmed) folded.snippetArtist else null,
                    mbGenre, album, folded.title, -1,
                    folded.high && !solo, folded.titleConfirmed, folded.reasons.toList()
                )
                absorb(m, f, g0, solo)
                return
            }
        }
        val throttlesBefore = WikiLookup.throttleCount()
        val hit = try {
            WikiLookup.lookup(tt, aa)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        if (hit == null) {
            // A throttle is transient: never cache it as a miss, or a redo
            // would skip a song that was never actually looked up.
            if (WikiLookup.throttleCount() == throttlesBefore) {
                runCatching { cache?.put(tt, aa, miss()) }
            }
            return
        }
        val applied = applyWikiResult(tt, aa, g0, hit)
        if (!corroborated(aa, applied.snippetArtist, tt, applied.high)) {
            // Title matched nothing trustworthy: pure guess, not kept.
            // Cache the miss so obscure songs cost one lookup ever.
            if (!applied.titleConfirmed) {
                runCatching {
                    cache?.put(
                        tt, aa, CachedLookup(
                            applied.title, aa, null, null,
                            hit.pageId, hit.title, false, false, false
                        )
                    )
                }
            }
            return
        }
        var genre = applied.genre
        var album: String? = WikiLookup.snippetAlbum(hit.snippet)
        if (applied.titleConfirmed && (genre == null || album == null)) {
            // One combined call serves categories-genre and infobox
            // genre/album together. Skipped when the snippet already
            // carried both, saving a fetch per song.
            val details = pageDetails(hit.pageId)
            if (genre == null) {
                genre = details?.first?.let { genreFromCategories(it) }
                    ?: details?.second?.genre?.let { genreFromCategories(listOf(it)) }
            }
            if (album == null) album = details?.second?.album
        }
        runCatching {
            cache?.put(
                tt, aa, CachedLookup(
                    applied.title,
                    if (!solo && applied.titleConfirmed) {
                        applied.snippetArtist ?: applied.artist
                    } else applied.artist,
                    genre, album, hit.pageId, hit.title,
                    applied.high && !solo, applied.titleConfirmed, true
                )
            )
        }
        val f = Folded(
            applied.title,
            if (!solo && applied.titleConfirmed) applied.snippetArtist else null,
            genre, album, hit.title, hit.pageId, applied.high && !solo, applied.titleConfirmed,
            applied.reasons.toList()
        )
        absorb(m, f, g0, solo)
    }

    /**
     * Fold one answer into the candidate. High confidence settles the song;
     * a confirmed title settles it once as a review candidate. Returns true
     * when no further reading can improve it.
     */
    private fun absorb(m: Mid, f: Folded, g0: String, solo: Boolean): Boolean {
        if (f.high && !solo) {
            m.title = f.title
            if (f.artist != null) m.artist = f.artist
            m.high = true
            m.reasons.addAll(f.reasons.filter { it !in m.reasons })
            adoptGenreAlbum(m, f, g0)
            return true
        }
        if (f.titleConfirmed && m.reasons.none { it.startsWith("Wikipedia") || it.startsWith("MusicBrainz") }) {
            m.title = f.title
            if (!solo && f.artist != null) m.artist = f.artist
            m.reasons.addAll(f.reasons.filter { it !in m.reasons })
            m.high = false
            adoptGenreAlbum(m, f, g0)
        }
        return false
    }

    private fun adoptGenreAlbum(m: Mid, f: Folded, g0: String) {
        if (f.genre != null && (g0.isBlank() || g0 == "Uncategorized")) {
            m.wikiGenre = f.genre
            if ("Online genre" !in m.reasons) m.reasons.add("Online genre")
        }
        // Live and best-of packaging is rejected everywhere: the resolved
        // original wins ("Trouble (Deluxe Edition)" becomes "Trouble").
        val fixed = f.album?.let { com.blissplayer.core.AnalyzerRules.resolveAlbumTitle(it) }
        if (fixed != null) {
            m.wikiAlbum = fixed
            if ("Online album" !in m.reasons) m.reasons.add("Online album")
        }
    }

    private fun miss(): CachedLookup =
        CachedLookup("", "", null, null, -1, "", false, false, false)

    /** Genre for an already-confirmed title via one Wikipedia search + details. */
    private suspend fun wikiGenreFor(title: String, artist: String): String? {
        return try {
            coroutineContext.ensureActive()
            val pageId = WikiLookup.searchPageId(title, artist) ?: return null
            val details = pageDetails(pageId) ?: return null
            genreFromCategories(details.first)
                ?: details.second?.genre?.let { genreFromCategories(listOf(it)) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /** Categories + infobox for one song page, else null. One fetch per song page. */
    private suspend fun pageDetails(pageId: Long): Pair<List<String>, WikiLookup.Infobox?>? = try {
        WikiLookup.pageDetails(pageId)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /** Thrown when Wikipedia is unreachable: carries the offline-only results. */
    class OfflineException(val partial: List<Proposal>) : Exception("offline")

    /**
     * Pure fold of a Wikipedia hit into a candidate. Returns the updated
     * title, artist, genre (null = keep current), reasons delta, and whether
     * the result is confident. Unit tested on the JVM with real responses.
     */
    fun applyWikiResult(
        localTitle: String, localArtist: String, currentGenre: String,
        hit: WikiLookup.Hit
    ): WikiApplied {
        val reasons = mutableListOf<String>()
        var title = localTitle
        var artist = localArtist
        var genre: String? = null
        var high: Boolean

        val canon = WikiLookup.canonicalTitle(hit.title)
        val titleMatch = canon.isNotBlank() &&
            WikiLookup.norm(canon) == WikiLookup.norm(localTitle)
        val localUnknown = isUnknownArtist(localArtist)
        val snippetPerformer = WikiLookup.snippetArtist(hit.snippet)
        val artistMatch = snippetPerformer != null &&
            (localUnknown || artistMatches(localArtist, snippetPerformer))

        if (titleMatch && (localUnknown || artistMatch)) {
            // Clear match: take the canonical title and the performer.
            title = canon
            if (snippetPerformer != null) artist = snippetPerformer
            reasons.add("Wikipedia: ${hit.title}")
            high = true
        } else if (titleMatch && snippetPerformer != null) {
            // Title confirmed, performer differs: keep ours, flag review.
            // The caller may still adopt the performer as a suggestion.
            title = canon
            reasons.add("Wikipedia title match (artist differs: $snippetPerformer)")
            high = false
        } else if (titleMatch) {
            // Title confirmed but no performer found: keep everything, review.
            title = canon
            reasons.add("Wikipedia title match")
            high = false
        } else {
            // Best guess only: suggest, do not trust.
            reasons.add("Wikipedia guess: ${hit.title}")
            high = false
            return WikiApplied(title, artist, null, reasons, high, titleMatch, snippetPerformer)
        }
        // Genre travels with the hit; the caller adopts it only when the
        // title is corroborated, and says so itself.
        val g = genreFromCategories(hit.categories)
        if (g != null && (currentGenre.isBlank() || currentGenre == "Uncategorized")) {
            genre = g
        }
        return WikiApplied(title, artist, genre, reasons, high, titleMatch, snippetPerformer)
    }

    data class WikiApplied(
        val title: String, val artist: String, val genre: String?,
        val reasons: MutableList<String>, val high: Boolean,
        val titleConfirmed: Boolean, val snippetArtist: String?
    )

    private fun sanitize(name: String): String {
        val s = name.trim().ifBlank { "Unknown" }
        val cleaned = s.map { c ->
            if (c.isISOControl() || c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') '_' else c
        }.joinToString("").take(60).trim(' ', '.')
        return cleaned.ifBlank { "Unknown" }
    }

    data class OrganizeResult(
        val moved: Int,
        val skipped: Int,
        val notes: List<String>,
        /** Content URIs the system refused without an explicit write grant. */
        val needsPermission: List<String>,
        val alreadyOrganized: Int = 0
    )

    /**
     * Move files into Music/BlissPlayer/Artist/Album via MediaStore.
     * Songs with no readable path are counted as skipped with a reason
     * instead of vanishing silently.
     */
    suspend fun organize(
        context: Context,
        songs: List<Song>,
        onProgress: (done: Int, total: Int, name: String) -> Unit
    ): OrganizeResult {
        val progressContext = currentCoroutineContext().minusKey(Job)
        return organizeOnWorker(context, songs) { done, total, name ->
            withContext(progressContext) { onProgress(done, total, name) }
        }
    }

    private suspend fun organizeOnWorker(
        context: Context, songs: List<Song>, report: suspend (Int, Int, String) -> Unit
    ): OrganizeResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext OrganizeResult(
            0, songs.size, listOf("Organizing needs Android 10 or newer."), emptyList()
        )
        var moved = 0
        var skipped = 0
        var alreadyOrganized = 0
        val notes = mutableListOf<String>()
        val needsPermission = mutableListOf<String>()
        songs.forEachIndexed { i, s ->
            coroutineContext.ensureActive()
            try {
                val uri = Uri.parse(s.contentUri)
                val artist = sanitize(s.artist)
                val album = sanitize(s.album)
                val rel = "Music/BlissPlayer/$artist/$album/"
                val currentPath = context.contentResolver.query(uri,
                    arrayOf(MediaStore.Audio.Media.RELATIVE_PATH), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                if (currentPath == null) {
                    skipped++
                    if (notes.size < 20) notes.add("${s.title}: Android returned no readable media record. The file may have been removed or access revoked.")
                    return@forEachIndexed
                }
                if (currentPath.trimEnd('/') == rel.trimEnd('/')) {
                    skipped++
                    alreadyOrganized++
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, rel)
                    }
                    val rows = context.contentResolver.update(uri, values, null, null)
                    if (rows > 0) moved++ else {
                        skipped++
                        if (notes.size < 20) notes.add("${s.title}: MediaStore updated no rows; the file may have been removed.")
                    }
                }
            } catch (e: SecurityException) {
                // System wants one tap per batch: collect and ask once at the end.
                skipped++
                needsPermission.add(s.contentUri)
                if (notes.size < 20) notes.add("${s.title}: Android requires write access before moving this file. ${e.message.orEmpty()}")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                skipped++
                if (notes.size < 20) notes.add("${s.title}: ${e.javaClass.simpleName}: ${e.message ?: "move failed"}.")
            }
            if (i % 10 == 0) report(i + 1, songs.size, s.title)
        }
        report(songs.size, songs.size, "")
        OrganizeResult(moved, skipped, notes, needsPermission, alreadyOrganized)
    }
}
