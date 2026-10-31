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
        val safe: Boolean,
        /** Modified re-upload (slowed, nightcore, 8D...): never Wikipedia-adopted. */
        val versioned: Boolean = false
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
        Regex("\\bmusic\\s+video\\b", RegexOption.IGNORE_CASE),
        Regex("\\bofficial\\s+(music\\s+)?video\\b", RegexOption.IGNORE_CASE),
        Regex("\\bofficial\\s+audio\\b", RegexOption.IGNORE_CASE),
        Regex("\\(\\s*(4k|8k|hd|hq)\\s*[^()]*\\)", RegexOption.IGNORE_CASE),
        Regex("\\[\\s*(4k|8k|hd|hq)\\s*[^\\[\\]]*\\]", RegexOption.IGNORE_CASE),
        Regex("\\bm/?v\\b", RegexOption.IGNORE_CASE),
        Regex("\\bletras?\\b", RegexOption.IGNORE_CASE),
        Regex("\\(\\s*vevo\\s*\\)", RegexOption.IGNORE_CASE),
        Regex("\\(\\s*vevo\\b[^()]*\\)", RegexOption.IGNORE_CASE),
        Regex("\\bvevo\\b", RegexOption.IGNORE_CASE),
        // Bare years and label junk in brackets: (1970), (USSR, 1989).
        Regex("\\(\\s*(19|20)\\d{2}\\s*\\)"),
        Regex("\\(\\s*(?:ussr|usa|u\\.s\\.|uk|england)\\b[^()]*\\)", RegexOption.IGNORE_CASE),
        // Descriptive parentheticals, never the song itself.
        Regex("\\(\\s*[^()]*\\b(chiptune|8-?bit|lo-?fi|demo|instrumental|karaoke)\\b[^()]*\\)", RegexOption.IGNORE_CASE)
    )

    /** Trailing ", COUNTRY, YEAR" / ", YEAR" tails from compilation titles. */
    private val tailJunk = listOf(
        Regex(",\\s*(19|20)\\d{2}\\s*$"),
        Regex("\\s+\\b(ussr|usa|u\\.s\\.|uk|england|france|germany|russia|ukraine|japan|spain|italy|brazil)\\s*$", RegexOption.IGNORE_CASE)
    )

    /** Leading compilation/decade junk before the real "Artist - Title". */
    private val compWords = Regex("^(playlist|mix|collection|jukebox|top\\s+\\d+|best\\s+of|hits)\\b[\\s\\-.]+", RegexOption.IGNORE_CASE)
    private val genrePrefix = Regex("^(soviet\\s+synthpop|city\\s+pop|sea\\s+shant(?:y|ies)|qawwali|chiptune|synthpop)\\b[\\s.]+", RegexOption.IGNORE_CASE)
    private val decadeLead = Regex("^\\d{2,4}s?[\\s.]+", RegexOption.IGNORE_CASE)

    /** Strip leading junk from an artist candidate. Returns cleaned + whether it changed. */
    fun stripLeadingJunk(artist: String): Pair<String, Boolean> {
        var a = artist.trim()
        var hit = false
        // "80s Soviet Synthpop.  Real Artist": decade lead means the tail is the name.
        if (decadeLead.containsMatchIn(a)) {
            val afterDot = a.substringAfterLast(".  ", "")
            a = if (afterDot.isNotBlank() && afterDot != a) afterDot.trim()
            else a.replace(decadeLead, "").trim()
            hit = true
        }
        var guard = 0
        while (guard++ < 3) {
            val c = compWords.find(a)
            val g = genrePrefix.find(a)
            val m = c ?: g ?: break
            a = a.removeRange(m.range).trim()
            hit = true
        }
        return a to hit
    }

    /** Modified re-upload markers: slowed, nightcore, 8D, bass boosted... */
    private val versionWords = Regex(
        "\\b(nightcore|daycore|slowed(\\s*\\+\\s*reverb|\\s+reverb)?|reverbed|sped\\s*up|8d(\\s+audio)?|bass\\s*boost(ed)?|tiktok(\\s+(version|remix|song))?|antichipmunk)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Channel/compilation giveaways, matched as whole words only. */
    private val compFlags = Regex(
        "\\b(mix|mixes|collection|radio|playlist|playlists|hits|nightcore|daycore|slowed|reverb|reverbed|sped|boosted|8d|karaoke|tiktok|jukebox|medley|compilation|live|concert|interview|interviews|beats|study|hour|top)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Strip emoji/pictographs but keep every real script (CJK, Cyrillic, accents). */
    fun stripEmoji(s: String): String =
        s.filter { c ->
            !(c in '\u2600'..'\u27BF' || c in '\u2B00'..'\u2BFF' ||
                c in '\u2190'..'\u21FF' || c in '\uFE00'..'\uFE0F' || c == '\u200D' ||
                c.isHighSurrogate() || c.isLowSurrogate())
        }.replace(Regex("\\s{2,}"), " ").trim()

    /** Bracket contents worth keeping: remix, live, acoustic, version, explicit. */
    private val keepBracket = setOf(
        "remix", "remixes", "live", "acoustic", "unplugged", "version",
        "explicit", "clean", "remaster", "remastered", "cover", "demo",
        "extended", "original", "radio", "edit", "mix", "part", "pt"
    )

    /** A trailing [xxxx] is a video id when it looks like one: no spaces, id-shaped. */
    fun stripVideoId(title: String): Pair<String, Boolean> {        var t = title
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

    /** Trailing junk segments like label names after the real title. */
    private val junkSeg = Regex(
        "(official|live|music\\s+video|video|audio|hd|hq|4k|lyrics?|visualizer|vevo|full\\s+album|playlist|dj\\s+mix|jukebox|topic|mv|interviews?|slowed|reverb|reverbed|sped|boosted|nightcore|daycore|8d|tiktok|bass)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Split readings of a dashed name. YouTube uploaders write both
     * "Artist - Title" and "Title - Artist", so with 2+ parts both orders
     * are offered and Wikipedia picks the winner.
     */
    fun splitVariants(base: String): List<Pair<String, String>> {
        val parts = base.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return emptyList()
        val core = parts.toMutableList()
        while (core.size > 2 && junkSeg.containsMatchIn(core.last())) core.removeLast()
        if (core.size == 2) {
            val fwd = core[0] to core[1]
            // Same two parts, swapped: the uploader may lead with the title.
            val rev = core[1] to core[0]
            return if (fwd == rev) listOf(fwd) else listOf(fwd, rev)
        }
        return listOf(
            core[0] to core.drop(1).joinToString(" - "),
            core.drop(1).dropLast(1).joinToString(" - ").ifBlank { core[1] } to core[0]
        )
    }

    /** True for placeholder artists that carry no information. */
    fun isUnknownArtist(artist: String): Boolean =
        artist.isBlank() || artist.equals("Unknown artist", true) ||
            artist.equals("<unknown>", true)

    /**
     * True when the album is not a real album: blank, an unknown marker, or
     * just the folder the file sits in ("Music" is a folder, not an album).
     */
    fun isPlaceholderAlbum(album: String, filePath: String?): Boolean {
        val a = album.trim()
        if (a.isBlank() || a.equals("Unknown album", true) || a.equals("<unknown>", true)) return true
        val parent = filePath?.substringBeforeLast('/', "")?.substringAfterLast('/') ?: ""
        return parent.isNotBlank() && a.equals(parent, ignoreCase = true)
    }

    /** Label words: a "performer" that is really a record company. */
    private val labelWords = Regex(
        "\\b(records?|recordings?|music|entertainment|worldwide|official|vevo|tv|media|network|studios?|films?|presents?|publishing)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Compare names ignoring case, spaces, dots and underscores. */
    fun normNoSpace(s: String): String =
        WikiLookup.norm(s).replace(" ", "").replace(".", "").replace("_", "")

    /**
     * One song, several artists: "A & B" browses under both A and B.
     * Commas never split ("Earth, Wind & Fire" stays whole).
     */    fun splitArtists(displayArtist: String): List<String> {
        val s = displayArtist.trim()
        if (s.isBlank()) return emptyList()
        val parts = s.split(
            Regex("\\s+(?:&|and|feat\\.?|ft\\.?|featuring|with|x|vs\\.?|versus)\\s+|\\s*;\\s*", RegexOption.IGNORE_CASE)
        ).map { it.trim() }.filter { it.isNotEmpty() }
        return (if (parts.isEmpty()) listOf(s) else parts).distinct()
    }

    /** Performer names match when equal or when one side adds words like "Ustad". */
    fun artistMatches(local: String, wiki: String): Boolean {
        val l = WikiLookup.norm(local)
        val w = WikiLookup.norm(wiki)
        if (l.isBlank() || w.isBlank()) return false
        if (l == w) return true
        val lw = l.split(' ')
        val ww = w.split(' ')
        if (lw.size < ww.size) {
            return ww.takeLast(lw.size) == lw || ww.take(lw.size) == lw
        }
        return lw.takeLast(ww.size) == ww || lw.take(ww.size) == ww
    }

    /**
     * Pure local cleanup of a messy YouTube-style name. Returns the cleaned
     * title/artist plus what changed. Never touches the network.
     */
    fun cleanLocal(rawTitle: String, rawArtist: String, fileBase: String): Cleaned {
        // Fullwidth separators act like dashes everywhere below.
        var title = rawTitle.trim()
            .replace("＂", "\"").replace("＂", "\"")
            .replace("｜", " - ").replace("│", " - ")
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
        val topicCut = Regex("\\s+-\\s+topic\\s*$", RegexOption.IGNORE_CASE)
        val noTopic = topicCut.replace(artist, "").trim()
        if (noTopic != artist.trim() && noTopic.isNotBlank()) {
            artist = noTopic
            if ("dropped Topic suffix" !in reasons) reasons.add("dropped Topic suffix")
        }

        // Track numbers and quote dots uploaders prepend: "01 - X", "1. X", "...X".
        val trackNo = Regex("^\\d{1,3}\\s*[.\\-]\\s+").find(title)
        if (trackNo != null && title.removeRange(trackNo.range).isNotBlank()) {
            title = title.removeRange(trackNo.range).trim()
            if ("dropped track number" !in reasons) reasons.add("dropped track number")
        }
        title = Regex("^(?:…+|(?:\\.\\s*){2,})").replace(title, "").trim()
        // Underscores standing in for spaces and separators.
        if ("_" in title) {
            val us = title.replace(Regex("\\s_+\\s+"), " - ").trim('_', ' ', '-')
                .let { if (" " !in it) it.replace('_', ' ') else it }
            if (us != title && us.isNotBlank()) {
                title = us
                if ("underscores to separators" !in reasons) reasons.add("underscores to separators")
            }
        }

        // Japanese corner brackets: artist「title」rest.
        if (artistUnknown || artist.isBlank()) {
            val m = Regex("^(.*?)「([^」]{1,60})」").find(title)
            if (m != null) {
                val pre = m.groupValues[1].trim { it == ' ' || it == '-' || it == '_' || it == '.' }
                val inner = m.groupValues[2].trim()
                val rest = title.removeRange(m.range).trim()
                if (pre.isNotBlank() && inner.isNotBlank()) {
                    artist = pre
                    title = if (rest.isBlank()) inner else "$inner $rest"
                    reasons.add("split artist-title brackets")
                }
            }
        }

        // Channel-name artists: "AkonVEVO" -> "Akon", "tenaciousDVEVO" -> "Tenacious D".
        val vevoCut = Regex("(\\s*-\\s*)?\\bvevo\\b\\s*$", RegexOption.IGNORE_CASE)
        val deVevo = vevoCut.replace(artist, "").trim()
        if (deVevo != artist && deVevo.isNotBlank()) {
            var fixed = deVevo.replace(Regex("([a-z])([A-Z])"), "$1 $2").trim()
            if (fixed.isNotEmpty() && fixed[0].isLowerCase()) {
                fixed = fixed.replaceFirstChar { it.uppercase() }
            }
            if (fixed.isNotBlank()) {
                if (fixed != deVevo) {
                    // Case was reshaped: plausible, but let review confirm.
                    artist = fixed
                    safe = false
                    if ("cleaned channel name" !in reasons) reasons.add("cleaned channel name")
                } else {
                    artist = fixed
                    if ("dropped VEVO suffix" !in reasons) reasons.add("dropped VEVO suffix")
                }
            }
        }

        // Label-as-artist: the real performer hides in a dashed title.
        // Single-token uploaders get the same treatment as a review reading.
        if (" - " in title) {
            val labelHit = labelWords.containsMatchIn(artist)
            val singleToken = !isUnknownArtist(artist) && " " !in artist.trim() && "-" !in artist
            if (labelHit) {
                val parts = title.split(" - ", limit = 2)
                if (parts[0].isNotBlank() && parts[1].isNotBlank() &&
                    WikiLookup.norm(parts[0]) != WikiLookup.norm(artist)
                ) {
                    artist = parts[0].trim()
                    title = parts[1].trim()
                    if ("moved artist from title" !in reasons) reasons.add("moved artist from title")
                }
            }
        }

        // "Akon - Lonely" with artist Akon: the prefix is duplication, drop it.
        if (" - " in title && !isUnknownArtist(artist)) {
            val head = title.substringBefore(" - ").trim()
            if (head.isNotBlank() && normNoSpace(head) == normNoSpace(artist)) {
                val rest = title.substringAfter(" - ").trim()
                if (rest.isNotBlank()) {
                    title = rest
                    if ("removed artist prefix" !in reasons) reasons.add("removed artist prefix")
                }
            }
        }

        // Compilation/decade junk leading the artist name.
        val (strippedArtist, hadJunk) = stripLeadingJunk(artist)
        if (hadJunk && strippedArtist.isNotBlank()) {
            artist = strippedArtist
            reasons.add("dropped compilation prefix")
        }

        var cleaned = title.replace("⧸", "/")
        noise.forEach { r -> cleaned = r.replace(cleaned, " ") }
        val (noId, hadId) = stripVideoId(cleaned)
        cleaned = noId
        if (hadId) reasons.add("dropped video id")
        for (t in tailJunk) {
            val cut = t.replace(cleaned, "").trim()
            if (cut != cleaned && cut.isNotBlank()) {
                cleaned = cut
                if ("trimmed tail" !in reasons) reasons.add("trimmed tail")
            }
        }
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
        // A wall of text with no known artist is a compilation or a video,
        // never a confident fix.
        if (title.length > 80 && (artist.isBlank() || artist.equals("Unknown artist", true) ||
                artist.equals("<unknown>", true))
        ) {
            safe = false
        }
        // Emoji never belong in tags.
        val deEmojid = stripEmoji(title)
        val deArtist = stripEmoji(artist)
        if (deEmojid != title || deArtist != artist) {
            title = deEmojid.ifBlank { title }
            artist = deArtist
            if ("dropped emoji" !in reasons) reasons.add("dropped emoji")
        }
        // Modified re-uploads keep their version words and never get a
        // Wikipedia identity: the original song is not this file.
        val versioned = versionWords.containsMatchIn(title) || versionWords.containsMatchIn(artist)
        if (versioned) {
            safe = false
            if ("modified version" !in reasons) reasons.add("modified version")
        }
        // Channel and compilation names are not artists.
        if (!isUnknownArtist(artist) && compFlags.containsMatchIn(artist)) {
            safe = false
            if ("channel or compilation name" !in reasons) reasons.add("channel or compilation name")
        }
        if (isUnknownArtist(artist) && compFlags.containsMatchIn(title)) {
            safe = false
            if ("compilation or stream" !in reasons) reasons.add("compilation or stream")
        }
        return Cleaned(title, artist, reasons, safe, versioned)
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
        albumOf: (Long) -> String,
        online: Boolean,
        onProgress: (done: Int, total: Int, phase: String) -> Unit
    ): List<Proposal> = withContext(Dispatchers.Default) {
        // Phase 1: local rules, fast.
        data class Mid(
            val song: Song, var title: String, var artist: String,
            var altTitle: String, var altArtist: String,
            val reasons: MutableList<String>, var high: Boolean,
            var wikiGenre: String?, var wikiAlbum: String?, val versioned: Boolean
        )
        val mids = mutableListOf<Mid>()
        songs.forEachIndexed { i, s ->
            val base = (s.filePath ?: "").substringAfterLast('/').substringBeforeLast('.')
                .ifBlank { s.title }
            val c = cleanLocal(s.title, s.artist, base)
            if (c.title.isNotBlank() && c.reasons.isNotEmpty()) {
                // Swapped reading for "Title - Artist" uploaders: tried online
                // when the primary reading is not confident.
                var altT = ""
                var altA = ""
                if (c.artist.isBlank() || isUnknownArtist(c.artist) || "split Artist - Title" in c.reasons) {
                    val variants = splitVariants(base.ifBlank { s.title })
                    val primary = c.artist to c.title
                    val swapped = variants.firstOrNull { it != primary }
                    if (swapped != null) {
                        altT = swapped.second
                        altA = swapped.first
                    }
                }
                mids.add(Mid(s, c.title, c.artist, altT, altA, c.reasons.toMutableList(), c.safe, null, null, c.versioned))
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
                // Modified re-uploads keep their version words: Wikipedia
                // would only find the unmodified original, so skip it.
                if (!offline && !m.versioned) {
                    val g0 = genreOf(m.song.mediaId)
                    // Primary reading first; when it is not confident, try the
                    // swapped order ("Title - Artist" uploaders) before giving up.
                    val tried = mutableListOf<Pair<String, String>>(m.title to m.artist)
                    m.altArtist.takeIf { it.isNotBlank() }?.let { aa ->
                        m.altTitle.takeIf { it.isNotBlank() }?.let { at ->
                            if (at to aa != tried[0]) tried.add(at to aa)
                        }
                    }
                    var settled = false
                    for ((tt, aa) in tried) {
                        if (!coroutineContext.isActive) return@withContext emptyList<Proposal>()
                        val hit = try {
                            WikiLookup.lookup(tt, aa)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            null
                        } ?: continue
                        val applied = applyWikiResult(tt, aa, g0, hit)
                        if (applied.high) {
                            m.title = applied.title
                            m.artist = applied.artist
                            m.reasons.addAll(applied.reasons)
                            m.wikiGenre = applied.genre
                            // Real album from the song page, when it has one.
                            m.wikiAlbum = try {
                                WikiLookup.albumOf(hit.pageId)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                null
                            }
                            if (m.wikiAlbum != null) m.reasons.add("Wikipedia album")
                            settled = true
                            break
                        } else if (!settled) {
                            // Keep the first answer as the review candidate.
                            m.title = applied.title
                            m.artist = applied.artist
                            m.reasons.addAll(applied.reasons)
                            m.high = false
                            m.wikiGenre = applied.genre
                            settled = true
                        }
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
            // Album: real one from Wikipedia, else "<Artist> Best Hits" so
            // artists never lump together under a folder name like "Music".
            val effAlbum0 = albumOf(m.song.mediaId)
            var album = m.wikiAlbum
            if (album == null && isPlaceholderAlbum(effAlbum0, m.song.filePath)) {
                val who = m.artist.takeIf { !isUnknownArtist(it) }
                    ?: m.song.artist.takeIf { !isUnknownArtist(it) }
                if (who != null) {
                    album = "$who Best Hits"
                    m.reasons.add("Best Hits album")
                } else {
                    album = "Unknown album"
                }
            }
            if (m.title.isBlank()) return@mapNotNull null
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
        val snippetArtist = WikiLookup.snippetArtist(hit.snippet)
        val artistMatch = snippetArtist != null &&
            (localUnknown || artistMatches(localArtist, snippetArtist))

        if (titleMatch && (localUnknown || artistMatch)) {
            // Clear match: take the canonical title and the performer.
            title = canon
            if (snippetArtist != null) artist = snippetArtist
            reasons.add("Wikipedia: ${hit.title}")
            high = true
        } else if (titleMatch && snippetArtist != null) {
            // Title confirmed, performer differs: keep ours, flag review.
            title = canon
            reasons.add("Wikipedia title match (artist differs: $snippetArtist)")
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
