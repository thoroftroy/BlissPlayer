package com.blissplayer.music.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.blissplayer.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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

    data class Proposal(
        val song: Song,
        val newTitle: String,
        val newArtist: String,
        val newAlbum: String,
        val newGenre: String,
        val confidence: String, // High / Check
        val reason: String
    )

    data class Cleaned(
        val title: String,
        val artist: String,
        val reasons: List<String>,
        val safe: Boolean
    )

    private val noise = listOf(
        Regex("\\[.*?official.*?video.*?\\]", RegexOption.IGNORE_CASE),
        Regex("\\(.*official.*?video.*?\\)", RegexOption.IGNORE_CASE),
        Regex("\\[.*?official.*?audio.*?\\]", RegexOption.IGNORE_CASE),
        Regex("\\(.*official.*?audio.*?\\)", RegexOption.IGNORE_CASE),
        Regex("\\(.*lyric.*?video.*?\\)", RegexOption.IGNORE_CASE),
        Regex("\\[.*?lyric.*?\\]", RegexOption.IGNORE_CASE),
        Regex("\\(.*lyrics?\\)", RegexOption.IGNORE_CASE),
        Regex("\\[.*?lyrics?\\]", RegexOption.IGNORE_CASE),
        Regex("\\(.*visuali[sz]er.*?\\)", RegexOption.IGNORE_CASE),
        Regex("\\[.*?visuali[sz]er.*?\\]", RegexOption.IGNORE_CASE),
        Regex("\\(.*music.*?video.*?\\)", RegexOption.IGNORE_CASE),
        Regex("\\bofficial\\s+(music\\s+)?video\\b", RegexOption.IGNORE_CASE),
        Regex("\\bofficial\\s+audio\\b", RegexOption.IGNORE_CASE),
        Regex("\\(\\s*(4k|8k|hd|hq)\\s*[^()]*\\)", RegexOption.IGNORE_CASE),
        Regex("\\[\\s*(4k|8k|hd|hq)\\s*[^\\[\\]]*\\]", RegexOption.IGNORE_CASE),
        Regex("\\bm/?v\\b", RegexOption.IGNORE_CASE),
        Regex("\\bletras?\\b", RegexOption.IGNORE_CASE),
        Regex("\\(\\s*vevo\\s*\\)", RegexOption.IGNORE_CASE),
        Regex("\\bvevo\\b", RegexOption.IGNORE_CASE)
    )

    /** Bracket contents worth keeping: remix, live, acoustic, version, explicit. */
    private val keepBracket = setOf(
        "remix", "remixes", "live", "acoustic", "unplugged", "version",
        "explicit", "clean", "remaster", "remastered", "cover", "demo",
        "extended", "original", "radio", "edit", "mix", "part", "pt"
    )

    /** A trailing [xxxx] is a video id when it looks like one: no spaces, id-shaped. */
    fun stripVideoId(title: String): Pair<String, Boolean> {
        var t = title
        var stripped = false
        while (true) {
            val m = Regex("\\s*\\[([^\\[\\]]+)\\]\\s*$").find(t) ?: break
            val inner = m.groupValues[1].trim()
            val words = inner.split(Regex("\\s+"))
            val idLike = words.size == 1 && inner.length in 6..20 &&
                inner.all { it.isLetterOrDigit() || it == '_' || it == '-' } &&
                inner.lowercase() !in keepBracket
            if (!idLike) break
            t = t.removeRange(m.range).trim()
            stripped = true
        }
        return t to stripped
    }

    /**
     * Pure local cleanup of a messy YouTube-style name. Returns the cleaned
     * title/artist plus what changed. Never touches the network.
     */
    fun cleanLocal(rawTitle: String, rawArtist: String, fileBase: String): Cleaned {
        var title = rawTitle.trim()
        var artist = rawArtist.trim()
        val reasons = mutableListOf<String>()
        var safe = true

        // Leading junk artist markers from re-uploads: "NA - Real Artist - Title".
        val naPrefix = Regex("^(?:na|n/a|unknown(?:\\s+artist)?)\\s+-\\s+", RegexOption.IGNORE_CASE)
        if (naPrefix.containsMatchIn(title)) {
            title = title.replace(naPrefix, "").trim()
            reasons.add("dropped junk prefix")
        }

        val base = fileBase.trim()
        val artistUnknown = artist.isBlank() || artist.equals("Unknown artist", true) ||
            artist.equals("<unknown>", true)
        if (artistUnknown && " - " in title) {
            val parts = title.split(" - ", limit = 2)
            if (parts[0].isNotBlank() && parts[1].isNotBlank()) {
                artist = parts[0].trim().removeSuffix(" - Topic").trim()
                title = parts[1].trim()
                reasons.add("split Artist - Title")
            }
        } else if ((title.equals("Unknown title", true) || title.isBlank()) && base.isNotBlank()) {
            title = base
            reasons.add("used filename as title")
            safe = false
        }
        if (artistUnknown && artist.isBlank() && " - " in base && " - " !in title) {
            val parts = base.split(" - ", limit = 2)
            if (parts[0].isNotBlank() && parts[1].isNotBlank() &&
                !parts[0].matches(Regex("^(na|n/a|unknown)$", RegexOption.IGNORE_CASE))
            ) {
                artist = parts[0].trim().removeSuffix(" - Topic").trim()
                if (title.isBlank()) title = parts[1].trim()
                reasons.add("split filename Artist - Title")
            }
        }
        artist = artist.removeSuffix(" - Topic").trim()

        var cleaned = title.replace("⧸", "/")
        noise.forEach { r -> cleaned = r.replace(cleaned, " ") }
        val (noId, hadId) = stripVideoId(cleaned)
        cleaned = noId
        if (hadId) reasons.add("dropped video id")
        cleaned = cleaned.replace(Regex("\\s{2,}"), " ")
            .trim { it == ' ' || it == '-' || it == '_' || it == '.' || it == '(' || it == ')' }
            .replace(Regex("\\s+"), " ").trim()
        if (cleaned.isNotBlank() && cleaned != title) {
            // Everything removed came from known noise patterns, so long
            // removals are fine. Only a stub of a title is a guess.
            if (cleaned.length < 4) safe = false
            title = cleaned
            if ("removed video tags" !in reasons) reasons.add("removed video tags")
        }
        return Cleaned(title, artist, reasons, safe)
    }

    private val genreWords = listOf(
        "k-pop" to "K-Pop", "j-pop" to "J-Pop",
        "dance-pop" to "Dance-Pop", "hip-hop" to "Hip-Hop", "hip hop" to "Hip-Hop",
        "r&b" to "R&B", "lo-fi" to "Lo-Fi", "lofi" to "Lo-Fi",
        "rock" to "Rock", "metal" to "Metal", "punk" to "Punk",
        "pop" to "Pop", "rap" to "Hip-Hop", "trap" to "Hip-Hop",
        "country" to "Country", "jazz" to "Jazz", "blues" to "Blues",
        "soul" to "Soul", "gospel" to "Gospel", "folk" to "Folk",
        "classical" to "Classical", "electronic" to "Electronic",
        "house" to "Electronic", "techno" to "Electronic", "edm" to "Electronic",
        "reggaeton" to "Latin", "latin" to "Latin", "salsa" to "Latin",
        "bossa" to "Jazz", "swing" to "Jazz", "bluegrass" to "Country",
        "symphony" to "Classical", "worship" to "Gospel", "hymn" to "Gospel"
    )

    /** First known genre word found in Wikipedia categories, else null. */
    fun genreFromCategories(categories: List<String>): String? {
        for (c in categories) {
            val low = c.lowercase()
            for ((word, name) in genreWords) {
                if (word in low) return name
            }
        }
        return null
    }

    fun keywordGenre(title: String, artist: String, current: String): String {
        if (current.isNotBlank() && current != "Uncategorized") return current
        val hay = "$title $artist".lowercase()
        for ((word, name) in genreWords) {
            if (word in hay) return name
        }
        return current.ifBlank { "Uncategorized" }
    }

    fun propose(song: Song, fileName: String, currentGenre: String): Proposal? {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.').trim()
        val cleaned = cleanLocal(song.title, song.artist, base)
        if (cleaned.title.isBlank()) return null
        var genre = keywordGenre(cleaned.title, cleaned.artist, currentGenre)
        val reasons = cleaned.reasons.toMutableList()
        var high = cleaned.safe
        if (genre != currentGenre && genre != "Uncategorized") {
            reasons.add("keyword genre")
            high = false
        }
        if (reasons.isEmpty()) return null
        return Proposal(
            song, cleaned.title.ifBlank { song.title },
            cleaned.artist.ifBlank { song.artist },
            song.album, genre.ifBlank { "Uncategorized" },
            if (high) "High" else "Check", reasons.joinToString(", ")
        )
    }

    /**
     * Full pass: local cleanup for every song, then Wikipedia for the
     * uncertain ones. Phases reported as "names" then "lookup" so the
     * progress bar reads clearly.
     */
    suspend fun analyze(
        songs: List<Song>,
        genreOf: (Long) -> String,
        online: Boolean,
        onProgress: (done: Int, total: Int, phase: String) -> Unit
    ): List<Proposal> = withContext(Dispatchers.Default) {
        // Phase 1: local rules, fast.
        data class Mid(
            val song: Song, var title: String, var artist: String,
            val reasons: MutableList<String>, var high: Boolean,
            var wikiGenre: String?
        )
        val mids = mutableListOf<Mid>()
        songs.forEachIndexed { i, s ->
            val base = (s.filePath ?: "").substringAfterLast('/').substringBeforeLast('.')
                .ifBlank { s.title }
            val c = cleanLocal(s.title, s.artist, base)
            if (c.title.isNotBlank() && c.reasons.isNotEmpty()) {
                mids.add(Mid(s, c.title, c.artist, c.reasons.toMutableList(), c.safe, null))
            }
            if (i % 25 == 0) onProgress(i + 1, songs.size, "names")
            if (!coroutineContext.isActive) return@withContext emptyList<Proposal>()
        }
        onProgress(songs.size, songs.size, "names")

        // Phase 2: Wikipedia confirmation + enrichment.
        // One cheap probe first: without network there is no point burning
        // minutes on per-song timeouts.
        var offline = false
        if (online && mids.isNotEmpty()) {
            offline = try {
                !WikiLookup.reachable()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                true
            }
            mids.forEachIndexed { i, m ->
                if (!coroutineContext.isActive) return@withContext emptyList<Proposal>()
                if (!offline) {
                    val hit = try {
                        WikiLookup.lookup(m.title, m.artist)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        null
                    }
                    if (hit != null) {
                        val g0 = genreOf(m.song.mediaId)
                        val applied = applyWikiResult(m.title, m.artist, g0, hit)
                        m.title = applied.title
                        m.artist = applied.artist
                        m.reasons.addAll(applied.reasons)
                        m.high = m.high && applied.high
                        m.wikiGenre = applied.genre
                    }
                }
                onProgress(i + 1, mids.size, "lookup")
            }
        }
        onProgress(songs.size, songs.size, "done")

        val out = mids.mapNotNull { m ->
            val g0 = genreOf(m.song.mediaId)
            var genre = m.wikiGenre ?: keywordGenre(m.title, m.artist, g0)
            if (genre != g0 && genre != "Uncategorized" &&
                m.reasons.none { it.startsWith("genre") || it.startsWith("Wikipedia genre") }
            ) {
                m.reasons.add("keyword genre")
                m.high = false
            }
            if (m.title.isBlank()) return@mapNotNull null
            Proposal(
                m.song, m.title, m.artist.ifBlank { m.song.artist },
                m.song.album, genre.ifBlank { "Uncategorized" },
                if (m.high) "High" else "Check", m.reasons.joinToString(", ")
            )
        }.sortedWith(compareBy({ it.confidence != "High" }, { it.song.title.lowercase() }))
        if (offline) throw OfflineException(out)
        out
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
        val localUnknown = localArtist.isBlank() ||
            localArtist.equals("Unknown artist", true) ||
            localArtist.equals("<unknown>", true)
        val snippetArtist = WikiLookup.snippetArtist(hit.snippet)
        val artistMatch = snippetArtist != null && !localUnknown &&
            WikiLookup.norm(snippetArtist) == WikiLookup.norm(localArtist)

        if (titleMatch && (localUnknown || artistMatch)) {
            // Clear match: take the canonical title and the performer.
            title = canon
            if (snippetArtist != null) artist = snippetArtist
            reasons.add("Wikipedia: ${hit.title}")
            high = true
        } else if (titleMatch && snippetArtist != null && localUnknown) {
            title = canon
            artist = snippetArtist
            reasons.add("Wikipedia: ${hit.title}")
            high = true
        } else if (titleMatch) {
            // Title confirmed, performer differs: keep ours, flag review.
            title = canon
            reasons.add("Wikipedia title match (artist differs: $snippetArtist)")
            high = false
        } else {
            // Best guess only: suggest, do not trust.
            reasons.add("Wikipedia guess: ${hit.title}")
            high = false
            return WikiApplied(title, artist, null, reasons, high)
        }
        val g = genreFromCategories(hit.categories)
        if (g != null && (currentGenre.isBlank() || currentGenre == "Uncategorized")) {
            genre = g
            reasons.add("Wikipedia genre")
        }
        return WikiApplied(title, artist, genre, reasons, high)
    }

    data class WikiApplied(
        val title: String, val artist: String, val genre: String?,
        val reasons: List<String>, val high: Boolean
    )

    private fun sanitize(name: String): String {
        val s = name.trim().ifBlank { "Unknown" }
        val cleaned = s.map { c ->
            if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') '_' else c
        }.joinToString("").take(60).trim()
        return cleaned.ifBlank { "Unknown" }
    }

    data class OrganizeResult(
        val moved: Int,
        val skipped: Int,
        val notes: List<String>,
        /** Content URIs the system refused without an explicit write grant. */
        val needsPermission: List<String>
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
    ): OrganizeResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext OrganizeResult(
            0, songs.size, listOf("Organizing needs Android 10 or newer."), emptyList()
        )
        var moved = 0
        var skipped = 0
        val notes = mutableListOf<String>()
        val needsPermission = mutableListOf<String>()
        songs.forEachIndexed { i, s ->
            try {
                val path = s.filePath
                if (path.isNullOrBlank()) {
                    skipped++
                    if (notes.size < 5) notes.add("${s.title}: no readable file path, left alone.")
                } else if (path.contains("/BlissPlayer/")) {
                    skipped++
                } else {
                    val uri = Uri.parse(s.contentUri)
                    val artist = sanitize(s.artist)
                    val album = sanitize(s.album)
                    val rel = "Music/BlissPlayer/$artist/$album"
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, rel)
                    }
                    val rows = context.contentResolver.update(uri, values, null, null)
                    if (rows > 0) moved++ else {
                        skipped++
                        if (notes.size < 5) notes.add("${s.title}: the system refused the move.")
                    }
                }
            } catch (e: SecurityException) {
                // System wants one tap per batch: collect and ask once at the end.
                skipped++
                needsPermission.add(s.contentUri)
            } catch (e: Exception) {
                skipped++
                if (notes.size < 5) notes.add("${s.title}: ${e.message ?: "move failed"}.")
            }
            if (i % 10 == 0) onProgress(i + 1, songs.size, s.title)
            if (!coroutineContext.isActive) {
                return@withContext OrganizeResult(moved, skipped, notes, needsPermission)
            }
        }
        onProgress(songs.size, songs.size, "")
        OrganizeResult(moved, skipped, notes, needsPermission)
    }
}
