package com.blissplayer.music.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
        WikiLookup.norm(title) + "|" + WikiLookup.norm(artist)

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
        // Remaster/reissue/HD/audio tags are packaging, not the song itself.
        Regex("\\([^()]*\\bremaster(?:ed)?\\b[^()]*\\)", RegexOption.IGNORE_CASE),
        Regex("\\[[^\\[\\]]*\\bremaster(?:ed)?\\b[^\\[\\]]*\\]", RegexOption.IGNORE_CASE),
        Regex("\\([^()]*\\b(?:hd|hq|4k|8k)\\b[^()]*\\)", RegexOption.IGNORE_CASE),
        Regex("\\([^()]*\\b(?:audio|official\\s+audio|original\\s+audio)\\b[^()]*\\)", RegexOption.IGNORE_CASE),
        Regex("\\([^()]*\\boriginal\\s+video\\b[^()]*\\)", RegexOption.IGNORE_CASE),
        Regex("\\([^()]*\\bvideo\\s+oficial\\b[^()]*\\)", RegexOption.IGNORE_CASE),
        Regex("\\([^()]*\\bvideoclip\\b[^()]*\\)", RegexOption.IGNORE_CASE),
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
        // Decade leads ("80s Soviet Synthpop.  Real Artist") lose the decade.
        // Plain numbers are kept: bands really are called 16 Stitch or 100 gecs.
        if (decadeLead.containsMatchIn(a) &&
            Regex("^(?:[6789]0s?|(?:19|20)\\d{2}s?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(a)
        ) {
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
        "(official|live|music\\s+video|video|audio|hd|hq|4k|lyrics?|visualizer|vevo|full\\s+album|playlist|dj\\s+mix|jukebox|topic|mv|interviews?|slowed|reverb|reverbed|sped|boosted|nightcore|daycore|8d|tiktok|bass|upgrade|upgrades|remaster|remastered|original|version|album|part|pt\\b|hardcore|worldwide|hcww)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Same list but word-bounded, for adopting a side as the artist: Alive is
     * not live, Party is not part, Basshunter is not bass, Videos need the
     * description check instead.
     */
    private val junkWord = Regex(
        "\\b(?:official|live|music\\s+video|video|audio|hd|hq|4k|lyrics?|visualizer|vevo|full\\s+album|playlist|dj\\s+mix|jukebox|topic|mv|interviews?|slowed|reverb|reverbed|sped|boosted|nightcore|daycore|8d|tiktok|bass|upgrade|upgrades|remaster|remastered|original|version|album|part|pt|hardcore|worldwide|hcww)\\b",
        RegexOption.IGNORE_CASE
    )
    private fun hasJunkWord(seg: String): Boolean = junkWord.containsMatchIn(seg)

    private val trailingYear = Regex("^(?:19|20)\\d{2}$")
    private val trailingPart = Regex("(?i)^(?:part|pt)\\.?\\s*\\d+(?:\\s*of\\s*\\d+)?$")
    private fun isJunkTail(seg: String): Boolean {
        val t = seg.trim().trim('(', ')', '[', ']', '"', '“', '”', '\'')
        if (t.isBlank()) return true
        if (junkSeg.containsMatchIn(t)) return true
        if (trailingYear.matches(t)) return true
        if (trailingPart.matches(t)) return true
        // "Official - 1993", "Official HD Version HCWW": every word is junk.
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isNotEmpty() && words.all { w ->
                junkSeg.containsMatchIn(w) || trailingYear.matches(w) ||
                    w.equals("official", true) || w.equals("version", true)
            }) return true
        return false
    }

    /**
     * Split readings of a dashed name. YouTube uploaders write both
     * "Artist - Title" and "Title - Artist", so with 2+ parts both orders
     * are offered and Wikipedia picks the winner.
     */
    fun splitVariants(base: String): List<Pair<String, String>> {
        val parts = base.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return emptyList()
        val core = parts.toMutableList()
        while (core.size > 2 && isJunkTail(core.last())) core.removeAt(core.lastIndex)
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
        "\\b(records?|recordings?|music|entertainment|worldwide|official|vevo|tv|media|network|studios?|films?|presents?|publishing|labels?|fueled|ramen)\\b",
        RegexOption.IGNORE_CASE
    )

    /** VEVO glued to a name: AviciiOfficialVEVO, SadeVEVO, 3DoorsDownVEVO. */
    private fun stripGluedVevo(name: String): String {
        val m = Regex("^(.*?)(?:official)?vevo\\s*$", RegexOption.IGNORE_CASE).find(name.trim())
        val core = m?.groupValues?.get(1)?.trim() ?: return name
        if (core.length < 2) return name
        // 3DoorsDown -> 3 Doors Down, AviciiOfficial already trimmed above.
        var spaced = core.replace(Regex("([0-9])([A-Z])"), "$1 $2")
        spaced = spaced.replace(Regex("([a-z])([A-Z])"), "$1 $2").trim()
        return spaced.ifBlank { name }
    }

    /** True when the artist tag looks like an uploader, not the performer. */
    private fun isUploaderLike(artist: String): Boolean {
        val a = artist.trim()
        if (isUnknownArtist(a)) return false
        if (a.endsWith("vevo", ignoreCase = true) && a.length > 4) return true
        if (labelWords.containsMatchIn(a)) return true
        // Single token channel names: TheKrow, mistertata, musicforeveryone.
        return ' ' !in a && '-' !in a
    }

    /** Compare names ignoring case, spaces, dots and underscores. */
    fun normNoSpace(s: String): String =
        WikiLookup.norm(s).replace(" ", "").replace(".", "").replace("_", "")

    /**
     * One performer, one name: "Eminem from 8 Mile", "Beyonce for her third
     * studio album", "SomeBand - Topic" all collapse to the performer.
     * Pure string work, never a lookup: grouping and display only.
     */
    fun canonicalArtist(raw: String): String {
        var a = raw.trim().trim('_', ' ', '-', '.')
        if (a.isBlank()) return a
        // YouTube auto-channels and label tails are distributors, not artists.
        a = Regex("\\s+-\\s+topic\\s*$", RegexOption.IGNORE_CASE).replace(a, "").trim()
        // "X for his/her/their Nth studio album ..." is a description.
        val forAlbum = Regex("\\s+for\\s+(?:his|her|their|the)\\s+.*$", RegexOption.IGNORE_CASE)
        if (forAlbum.containsMatchIn(a)) a = a.replace(forAlbum, "").trim()
        // "X from <movie/film/soundtrack/show> ..." tails.
        val fromMedia = Regex(
            "\\s+from\\s+(?:the\\s+)?.*\\b(movie|film|motion\\s+picture|soundtrack|\\bost\\b|show|series|game|musical|cover|tribute|commercial)\\b.*$",
            RegexOption.IGNORE_CASE)
        if (fromMedia.containsMatchIn(a)) a = a.replace(fromMedia, "").trim()
        // Parenthetical movie/label/channel/year tags: "Eminem (8 Mile)".
        a = Regex("\\s*\\([^()]*\\b(movie|film|soundtrack|ost|records|tv|vevo|topic|official|19\\d{2}|20\\d{2})\\b[^()]*\\)\\s*$",
            RegexOption.IGNORE_CASE).replace(a, "").trim()
        // Trailing " - <movie>" style tails.
        val dashMedia = Regex(
            "\\s+-\\s+.*\\b(movie|film|soundtrack|ost|records|tv)\\b.*$",
            RegexOption.IGNORE_CASE)
        if (dashMedia.containsMatchIn(a) && " - " in a) {
            val cut = a.replace(dashMedia, "").trim()
            if (cut.length >= 2) a = cut
        }
        return a.ifBlank { raw.trim() }
    }

    /** Grouping key: canonical, case- and accent-blind. */
    fun artistKey(raw: String): String = WikiLookup.norm(canonicalArtist(raw))

    /**
     * Merge key for grouping: spaceless and channel-prefix-proof, so
     * "panicatthedisco" lands with "Panic at the disco" and "thebandred"
     * lands with "Red". Display names are untouched; only grouping merges.
     */
    fun artistMergeKey(raw: String): String {
        var k = artistKey(raw).replace(" ", "")
        if (k.startsWith("theband") && k.length > 7) k = k.removePrefix("theband")
        return k
    }

    /** Nicer display wins: prefer a spaced/cased variant over a glued one. */
    fun preferArtistDisplay(old: String?, new: String): String {
        if (old == null) return new
        val oldGlued = ' ' !in old
        val newGlued = ' ' !in new
        if (oldGlued && !newGlued) return new
        return old
    }

    private val albumVersionWords = Regex(
        "\\s*[\\(\\[][^\\(\\)\\[\\]]*\\b(live|unplugged|unpluged|remix|remixes|remixed|remaster|remastered|deluxe|expanded|anniversary|bonus|acoustic|demo|instrumental|version|edition|reissue|re-recorded|rerecorded|single|ep)\\b[^\\(\\)\\[\\]]*[\\)\\]]\\s*$",
        RegexOption.IGNORE_CASE)

    /**
     * One album, one entry: years ("Album (1999)"), casing ("ALBUM" merges
     * via the key, not here) and version words ("Album (Live)", "X
     * Remastered", "Deluxe Edition") collapse to the original album name.
     * Compilation names stay as they are (different releases).
     */
    fun canonicalAlbum(raw: String): String {
        var a = raw.trim().trim('_', ' ', '-', '.')
        if (a.isBlank() || a.equals("Unknown album", true)) return raw.trim()
        // Year wrappers: "(1999)", "[2004]", " - 2004", "2004 - ".
        a = Regex("\\s*[\\(\\[]\\s*(?:19|20)\\d{2}\\s*[\\)\\]]").replace(a, " ").trim()
        a = Regex("\\s*-\\s*(?:19|20)\\d{2}\\s*$").replace(a, "").trim()
        a = Regex("^\\s*(?:19|20)\\d{2}\\s*-\\s*").replace(a, "").trim()
        // Trailing bare version words: "X Remastered", "X Deluxe".
        a = Regex("\\s+\\b(remastered|remaster|deluxe)\\s*$", RegexOption.IGNORE_CASE).replace(a, "").trim()
        // Trailing parenthetical version words, repeatedly: "(Live) (Remaster)".
        var prev: String
        do {
            prev = a
            a = albumVersionWords.replace(a, "").trim()
        } while (a != prev && a.isNotBlank())
        // "Deluxe Edition" style tails without parens.
        a = Regex("\\s+\\b(deluxe\\s+edition|expanded\\s+edition|anniversary\\s+edition|bonus\\s+edition)\\s*$",
            RegexOption.IGNORE_CASE).replace(a, "").trim()
        return a.ifBlank { raw.trim() }
    }

    /** Grouping key: canonical, case- and accent-blind. */
    fun albumKey(raw: String): String = WikiLookup.norm(canonicalAlbum(raw))

    /**
     * One song, several artists: "A & B" browses under both A and B.
     * Commas split "A, B, C" but never "Earth, Wind & Fire".
     */    fun splitArtists(displayArtist: String): List<String> {
        val s = displayArtist.trim()
        if (s.isBlank()) return emptyList()
        val parts = s.split(
            Regex("\\s+(?:&|and|feat\\.?|ft\\.?|featuring|with|x|vs\\.?|versus)\\s+|\\s*;\\s*", RegexOption.IGNORE_CASE)
        ).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val base = if (parts.isEmpty()) mutableListOf(s) else parts
        // "TheDooo, GBSN, John Silkie": comma-separated headliners, no conjunction.
        // "Earth, Wind & Fire" and "Peter, Paul and Mary" keep their commas.
        val joined = base.joinToString(" ")
        val hasConjunction = Regex(
            "\\b(?:and|&|feat\\.?|ft\\.?|featuring|with|x|vs\\.?|versus)\\b|;",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(joined)
        if (!hasConjunction && base.size == 1 && ',' in s) {
            val commaParts = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (commaParts.size in 2..6) return commaParts.distinct()
        }
        return base.distinct()
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
        // Normalize every separator first so later splits see the same shape:
        // fullwidth/en-em dashes, pipes, slashes, colons, underscores, "by".
        var title = rawTitle.trim()
            .replace("＂", "\"").replace("＂", "\"")
            .replace("｜", " - ").replace("│", " - ")
            .replace(Regex("[–—―‐‑‒]"), " - ")
        var artist = rawArtist.trim()
        // "A | B" is "A - B", but AC/DC keeps its slash.
        title = title.replace(Regex("\\s*\\|\\s*"), " - ")
        // "Artist: Title" is "Artist - Title" (times like 10:00 are left alone).
        title = title.replace(Regex("(?<!\\d)\\s*[:：]\\s*(?!\\d)"), " - ")
        // Leading/trailing underscores are wrappers: _Song_ -> Song.
        // Quotes are NOT trimmed here: SongNames.unquote removes wrappers
        // (and only wrappers) just below, keeping the reason accurate and
        // protecting inner apostrophes like 'Til Tuesday.
        // Saved first: split branches below need the pre-trim shape to spot
        // outer quotes broken by an Artist - Title split ('A' - 'B').
        val preTrimTitle = title
        val preTrimArtist = artist
        title = title.trim('_', ' ', '-', '.')
        artist = artist.trim('_', ' ', '-', '.')
        // Underscores between words are separators or spaces.
        if ("_" in title) {
            var us = title.replace(Regex("\\s_+\\s+"), " - ")
            us = us.replace(Regex("_+\\s+"), " - ").replace(Regex("\\s_+"), " - ")
            us = us.trim('_', ' ', '-', '.').replace('_', ' ')
            us = us.replace(Regex("\\s{2,}"), " ").trim()
            if (us.isNotBlank() && us != title) title = us
        }
        // "Title by Artist" is reversed: split on the last " by ".
        val byMatches = Regex("\\s+[Bb][Yy]\\s+").findAll(title).toList()
        if (byMatches.isNotEmpty()) {
            val last = byMatches.last()
            val head = title.substring(0, last.range.first).trim()
            val tail = title.substring(last.range.last + 1).trim()
            val artistLooksUploader = isUnknownArtist(artist) || isUploaderLike(artist) ||
                WikiLookup.norm(artist) !in WikiLookup.norm(title)
            if (artistLooksUploader && head.isNotBlank() && tail.length >= 2 &&
                !tail.equals("topic", true)
            ) {
                title = head
                artist = tail.removeSuffix(" - Topic").trim()
            }
        }
        // Collapse doubled dashes from "_4K UPGRADE_ –" style mashups.
        title = title.replace(Regex("(\\s*-\\s*){2,}"), " - ").trim()
        val reasons = mutableListOf<String>()
        var safe = true

        val initialTitle = SongNames.unquote(title)
        val initialArtist = SongNames.unquote(artist)
        // The trim above already strips wrappers, so compare against the
        // pre-trim shape: `"The Hearse Song"` lost quotes, not just spaces.
        // Apostrophes inside words (Don't, O'Connor) are never wrappers.
        if (initialTitle != title || initialArtist != artist ||
            SongNames.unquote(preTrimTitle) != preTrimTitle.trim() ||
            SongNames.unquote(preTrimArtist) != preTrimArtist.trim()
        ) reasons.add("removed surrounding quotes")
        title = initialTitle
        artist = initialArtist

        // Leading junk upload markers: "NA - Real Artist - Title", "(NEW SONG) A - B".
        val naPrefix = Regex("^(?:na|n/a|unknown(?:\\s+artist)?)\\s+-\\s+", RegexOption.IGNORE_CASE)
        if (naPrefix.containsMatchIn(title)) {
            title = title.replace(naPrefix, "").trim()
            reasons.add("dropped junk prefix")
        }
        val newSongPrefix = Regex("^\\(\\s*new\\s+song\\s*\\)\\s*(?:-\\s*)?", RegexOption.IGNORE_CASE)
        if (newSongPrefix.containsMatchIn(title)) {
            title = title.replace(newSongPrefix, "").trim()
            reasons.add("dropped junk prefix")
        }

        // Leading "OFFICIAL ..." upload markers: the song is after the prefix.
        val officialPrefix = Regex("^\"?official\"?\\s+", RegexOption.IGNORE_CASE)
        if (officialPrefix.containsMatchIn(title)) {
            val cut = title.replace(officialPrefix, "").trim()
            if (cut.isNotBlank() && cut.length >= 2) {
                title = cut
                reasons.add("dropped junk prefix")
            }
        }

        // Track numbers first, before any Artist - Title split ("01 - A - B").
        val trackNo = Regex("^\\d{1,3}\\s*[.\\-]\\s+").find(title)
        if (trackNo != null && title.removeRange(trackNo.range).isNotBlank()) {
            title = title.removeRange(trackNo.range).trim()
            if ("dropped track number" !in reasons) reasons.add("dropped track number")
        }
        title = Regex("^(?:…+|(?:\\.\\s*){2,})").replace(title, "").trim()
        val base = fileBase.trim().replace(Regex("[–—―‐‑‒]"), " - ")
            .replace(Regex("\\s*\\|\\s*"), " - ")
        val artistUnknown = artist.isBlank() || artist.equals("Unknown artist", true) ||
            artist.equals("<unknown>", true)
        if (artistUnknown && " - " in title) {
            val parts = title.split(" - ", limit = 2)
            if (parts[0].isNotBlank() && parts[1].isNotBlank()) {
                // Each side may carry its own wrapper quotes: 'A' - 'B'.
                artist = SongNames.unquote(parts[0].trim()).removeSuffix(" - Topic").trim()
                title = SongNames.unquote(parts[1].trim())
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
                artist = SongNames.unquote(parts[0].trim()).removeSuffix(" - Topic").trim()
                if (title.isBlank()) title = SongNames.unquote(parts[1].trim())
                reasons.add("split filename Artist - Title")
            }
        }
        val topicCut = Regex("\\s+-\\s+topic\\s*$", RegexOption.IGNORE_CASE)
        val noTopic = topicCut.replace(artist, "").trim()
        if (noTopic != artist.trim() && noTopic.isNotBlank()) {
            artist = noTopic
            if ("dropped Topic suffix" !in reasons) reasons.add("dropped Topic suffix")
        }

        // "A / B" with spaces is "A - B" (AC/DC keeps its slash).
        if ("/" in title && Regex("\\s+/\\s+").containsMatchIn(title)) {
            title = title.replace(Regex("\\s+/\\s+"), " - ").replace(Regex("(\\s*-\\s*){2,}"), " - ").trim()
        }

        // "Artist "Quoted Title"" without a dash: strip the artist prefix.
        // Covers The Classic Crime "You and Me Both" w and T-ARA "NUMBER NINE.
        if (" - " !in title && !isUnknownArtist(artist)) {
            val quotedLead = Regex("^(.*?)\\s+[\"“”](.+?)[\"“”]?\\s*(?:w\\s*)?$").find(title)
            if (quotedLead != null) {
                val pre = quotedLead.groupValues[1].trim()
                var inner = quotedLead.groupValues[2].trim().trimEnd('w', ' ').trim()
                if (pre.isNotBlank() && inner.isNotBlank()) {
                    if (normNoSpace(pre) == normNoSpace(artist)) {
                        title = inner
                        if ("removed artist prefix" !in reasons) reasons.add("removed artist prefix")
                    } else if ((labelWords.containsMatchIn(artist) || isUploaderLike(artist) ||
                                WikiLookup.norm(artist) !in WikiLookup.norm(title)) &&
                        pre.length >= 2 && !isJunkTail(pre)
                    ) {
                        artist = pre
                        title = inner
                        safe = false
                        if ("moved artist from title" !in reasons) reasons.add("moved artist from title")
                    }
                }
            } else if (!isUploaderLike(artist)) {
                // "The Classic Crime Dead Rose" (unquoted): artist prefix without dash.
                val art = artist.trim()
                if (art.length >= 3 && title.startsWith(art, ignoreCase = true)) {
                    val rest = title.removePrefix(title.substring(0, art.length)).trim()
                        .trimStart('-', '_', ':', '|', '.', ' ').trim()
                    if (rest.length >= 2) {
                        title = rest
                        if ("removed artist prefix" !in reasons) reasons.add("removed artist prefix")
                    }
                }
            }
        }
        // Trailing lone " w" (truncated "w/ Lyrics"): strip it.
        val loneW = Regex("\\s+[wW]\\s*$").find(title)
        if (loneW != null) {
            val cut = title.removeRange(loneW.range).trim()
            if (cut.isNotBlank() && cut.length >= 2) title = cut
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

        // Glued channel names: AviciiOfficialVEVO -> Avicii, SadeVEVO -> Sade.
        // When the title already holds "Artist - Title", the title wins below.
        if (!isUnknownArtist(artist) && artist.endsWith("vevo", ignoreCase = true) && artist.length > 4 &&
            " - " !in title
        ) {
            val cleanedVevo = stripGluedVevo(artist)
            if (cleanedVevo != artist && cleanedVevo.isNotBlank()) {
                artist = cleanedVevo
                if ("dropped VEVO suffix" !in reasons) reasons.add("dropped VEVO suffix")
            }
        } else if (!isUnknownArtist(artist)) {
            val vevoCut = Regex("(\\s*-\\s*)?\\bvevo\\b\\s*$", RegexOption.IGNORE_CASE)
            val deVevo = vevoCut.replace(artist, "").trim()
            if (deVevo != artist && deVevo.isNotBlank()) {
                var fixed = deVevo.replace(Regex("([a-z])([A-Z])"), "$1 $2").trim()
                if (fixed.isNotEmpty() && fixed[0].isLowerCase()) {
                    fixed = fixed.replaceFirstChar { it.uppercase() }
                }
                if (fixed.isNotBlank()) {
                    artist = fixed
                    if (fixed != deVevo) {
                        safe = false
                        if ("cleaned channel name" !in reasons) reasons.add("cleaned channel name")
                    } else if ("dropped VEVO suffix" !in reasons) reasons.add("dropped VEVO suffix")
                }
            }
        }

        // Three parts with the tag repeating the last one:
        // "NORTHBREAKER - NEVER GIVE UP ... - HARDCORE WORLDWIDE"
        // tagged HARDCORE WORLDWIDE (the label). Head is the act, the middle
        // is the song. Runs first so the chain below never sees a solved title.
        var dashHandled = false
        if (" - " in title && !isUnknownArtist(artist)) {
            val head3 = title.substringBefore(" - ").trim()
            val tail3 = title.substringAfter(" - ").trim()
            if (" - " in tail3 && head3.isNotBlank()) {
                val last = tail3.substringAfterLast(" - ").trim()
                val middle = tail3.substringBeforeLast(" - ").trim()
                if (middle.isNotBlank() && last.isNotBlank() &&
                    normNoSpace(last) == normNoSpace(artist) &&
                    (isJunkTail(last) || labelWords.containsMatchIn(last)) &&
                    !isJunkTail(head3) && !isJunkTail(middle)
                ) {
                    artist = head3
                    title = middle
                    safe = false
                    if ("moved artist from title" !in reasons) reasons.add("moved artist from title")
                    dashHandled = true
                }
            }
        }

        // Dashed titles: decide Artist - Title vs Title - Artist vs Title - Description.
        if (!dashHandled && " - " in title && !isUnknownArtist(artist)) {
            val head = title.substringBefore(" - ").trim()
            val tail = title.substringAfter(" - ").trim()
            val headIsArtist = head.isNotBlank() &&
                (normNoSpace(head) == normNoSpace(artist) || artistMatches(artist, head))
            val tailIsArtist = tail.isNotBlank() && !(" - " in tail) &&
                (normNoSpace(tail) == normNoSpace(artist) || artistMatches(artist, tail))
            // The tag may name one performer of a multi-artist side
            // ("TheDooo" for "TheDooo, GBSN, John Silkie - ...").
            val artistInHead = !headIsArtist && head.isNotBlank() && splitArtists(head).any {
                normNoSpace(it) == normNoSpace(artist)
            }
            val artistInTail = !tailIsArtist && tail.isNotBlank() && !(" - " in tail) &&
                splitArtists(tail).any { normNoSpace(it) == normNoSpace(artist) }
            // Channel names that embed the performer ("Scatman John YouTube Channel").
            val headInArtist = head.length >= 3 && normNoSpace(head).length >= 5 &&
                normNoSpace(artist).contains(normNoSpace(head))
            val tailInArtist = tail.length >= 3 && !(" - " in tail) && normNoSpace(tail).length >= 5 &&
                normNoSpace(artist).contains(normNoSpace(tail))
            if (tailIsArtist && head.isNotBlank()) {
                // "Adipocere - Rusty Cage" with artist Rusty Cage: reversed.
                title = head
                if ("removed artist suffix" !in reasons) reasons.add("removed artist suffix")
            } else if (headIsArtist && tail.isNotBlank()) {
                title = tail
                if ("removed artist prefix" !in reasons) reasons.add("removed artist prefix")
            } else if (artistInHead && normNoSpace(head) != normNoSpace(artist) &&
                !versionWords.containsMatchIn(title) && !versionWords.containsMatchIn(artist)
            ) {
                // Partial credit completed: the head lists every performer.
                artist = head
                title = tail
                safe = false
                if ("moved artist from title" !in reasons) reasons.add("moved artist from title")
            } else if (artistInTail && normNoSpace(tail) != normNoSpace(artist) &&
                !versionWords.containsMatchIn(title) && !versionWords.containsMatchIn(artist)
            ) {
                // Partial credit completed: the tail lists every performer.
                artist = tail
                title = head
                safe = false
                if ("moved artist from title" !in reasons) reasons.add("moved artist from title")
            } else if (head.isNotBlank() && tail.isNotBlank() &&
                WikiLookup.norm(artist) !in WikiLookup.norm(title)
            ) {
                // Modified re-uploads keep their identity: never invent an
                // artist for a slowed/nightcore/tiktok file. The version words
                // stay and the file is flagged for review below.
                val versionedTitle = versionWords.containsMatchIn(title) ||
                    versionWords.containsMatchIn(artist)
                // "Bad Romance - Vintage ... Cover" is Title - Description: keep the
                // real performer, strip the description instead of inventing an artist.
                val tailIsDescription = !versionedTitle && (isJunkTail(tail) ||
                    Regex("(?i)\\b(cover|vintage|style|tribute|ft\\.?|feat\\.?|version|remix(?:es)?|edit|acoustic|live|official)\\b")
                        .containsMatchIn(tail))
                if (tailIsDescription) {
                    title = head
                    if ("removed description suffix" !in reasons) reasons.add("removed description suffix")
                } else if (!versionedTitle && tailInArtist && !headInArtist) {
                    // Uploader channel built on the performer's name.
                    artist = tail
                    title = head
                    safe = false
                    if ("moved artist from title" !in reasons) reasons.add("moved artist from title")
                } else if (!versionedTitle &&
                    (labelWords.containsMatchIn(artist) || isUploaderLike(artist) ||
                        artist.endsWith("vevo", ignoreCase = true) || headInArtist)
                ) {
                    // Uploader channel: "16 Stitch - Things They Say" by TheKrow.
                    // "Ain't Got No ... - Nina Simone" by drMandinga (order checked online).
                    artist = head
                    title = tail
                    safe = false
                    if ("moved artist from title" !in reasons) reasons.add("moved artist from title")
                } else if (!versionedTitle && !(" - " in tail) && head.length >= 2 && tail.length >= 2 &&
                    !hasJunkWord(head) && !hasJunkWord(tail) &&
                    normNoSpace(head) != normNoSpace(tail)
                ) {
                    // Last resort: the tag names an uploader nobody can verify
                    // ("Bill Withers - Ain't No Sunshine" tagged Andres Trevino).
                    // Head-first is the common upload order; the online pass
                    // tries the swapped order before trusting it.
                    artist = head
                    title = tail
                    safe = false
                    if ("moved artist from title" !in reasons) reasons.add("moved artist from title")
                }
            }
        } else if (" - " in title && isUnknownArtist(artist)) {
            // Already split above for unknown artists; collapse any leftover doubles.
            title = title.replace(Regex("(\\s*-\\s*){2,}"), " - ").trim()
        }

        // Spaceless hyphens: "Don McLean- American Pie" is still Artist - Title.
        // Both sides must be phrases so compounds (mother-in-law, Twenty-one)
        // and single-word artists are never touched.
        if (" - " !in title && Regex("\\S-|-\\S").containsMatchIn(title) &&
            !versionWords.containsMatchIn(title) && !versionWords.containsMatchIn(artist)
        ) {
            val hm = Regex("^(.+?\\S)\\s*-\\s*(\\S.+)$").find(title)
            if (hm != null) {
                val head = hm.groupValues[1].trim()
                val tail = hm.groupValues[2].trim()
                val sidesArePhrases = ' ' in head && ' ' in tail &&
                    head.length >= 4 && tail.length >= 4
                val artistUnrelated = !isUnknownArtist(artist) &&
                    WikiLookup.norm(artist) !in WikiLookup.norm(title) &&
                    normNoSpace(head) != normNoSpace(artist) &&
                    normNoSpace(tail) != normNoSpace(artist)
                val artistBlank = isUnknownArtist(artist) || artist.isBlank()
                if (sidesArePhrases && (artistUnrelated || artistBlank) &&
                    !isJunkTail(head) && !isJunkTail(tail)
                ) {
                    // "Fire-The Ink Spots" is reversed (tail starts with The);
                    // otherwise head-first is the common upload order.
                    val tailFirst = Regex("^the\\s+\\S+", RegexOption.IGNORE_CASE).containsMatchIn(tail) &&
                        head.split(Regex("\\s+")).size >= 5
                    if (tailFirst) {
                        title = head
                        artist = tail
                    } else {
                        artist = head
                        title = tail
                    }
                    safe = false
                    if (artistUnknown) {
                        if ("split Artist - Title" !in reasons) reasons.add("split Artist - Title")
                    } else if ("moved artist from title" !in reasons) reasons.add("moved artist from title")
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
        cleaned = cleaned.replace(Regex("\\(\\s*\\)|\\[\\s*\\]"), " ").replace(Regex("\\s{2,}"), " ")
            .trim { it == ' ' || it == '-' || it == '_' || it == '.' }
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
        val unquotedTitle = SongNames.unquote(title)
        val unquotedArtist = SongNames.unquote(artist)
        if (unquotedTitle != title || unquotedArtist != artist) {
            title = unquotedTitle
            artist = unquotedArtist
            if ("removed surrounding quotes" !in reasons) reasons.add("removed surrounding quotes")
        }
        if (reasons.isEmpty() && (title != rawTitle || artist != rawArtist)) reasons.add("trimmed names")
        // Descriptions are not performers: "X for her third studio album".
        val canon = canonicalArtist(artist)
        if (canon != artist && canon.isNotBlank()) {
            artist = canon
            if ("kept the performer only" !in reasons) reasons.add("kept the performer only")
        }
        return Cleaned(title, artist, reasons, safe, versioned)
    }

    /**
     * Genre words, most specific first. Matched with word boundaries so
     * "pop" never fires on "popular" and "mix" never fires on "remix".
     * Covers the shapes YouTube uploads actually carry: subgenres
     * (death metal, synthwave), scenes (k-pop, lo-fi, chiptune), moods
     * (worship, acoustic) and famous names (Mozart is always Classical).
     */
    private val genreWords = listOf(
        "death metal" to "Metal", "black metal" to "Metal", "thrash metal" to "Metal",
        "doom metal" to "Metal", "power metal" to "Metal", "speed metal" to "Metal",
        "heavy metal" to "Metal", "nu metal" to "Metal", "metalcore" to "Metal",
        "deathcore" to "Metal", "grindcore" to "Metal", "hardcore punk" to "Punk",
        "metal" to "Metal", "metalhead" to "Metal",
        "hard rock" to "Rock", "classic rock" to "Rock", "alt rock" to "Rock",
        "alternative rock" to "Rock", "indie rock" to "Rock", "indie" to "Rock",
        "alternative" to "Rock", "punk rock" to "Punk", "punk" to "Punk",
        "pop punk" to "Punk", "ska" to "Reggae", "reggae" to "Reggae",
        "grunge" to "Rock", "britpop" to "Rock", "rock" to "Rock",
        "rocker" to "Rock", "rockabilly" to "Rock",
        "hip hop" to "Hip-Hop", "hip-hop" to "Hip-Hop", "rap" to "Hip-Hop",
        "trap" to "Hip-Hop", "drill" to "Hip-Hop", "grime" to "Hip-Hop",
        "r&b" to "R&B", "rnb" to "R&B", "soul" to "Soul", "funk" to "Soul", "funky" to "Soul",
        "motown" to "Soul", "disco" to "Dance", "dance-pop" to "Dance-Pop",
        "dance" to "Dance", "house music" to "Electronic", "deep house" to "Electronic", "french house" to "Electronic",
        "techno" to "Electronic", "trance" to "Electronic", "dubstep" to "Electronic",
        "drum and bass" to "Electronic", "dnb" to "Electronic", "edm" to "Electronic",
        "electro" to "Electronic", "electronic" to "Electronic", "electronica" to "Electronic",
        "synthwave" to "Electronic", "synthpop" to "Electronic", "synth pop" to "Electronic",
        "synth" to "Electronic", "retrowave" to "Electronic", "vaporwave" to "Electronic",
        "chillwave" to "Electronic", "ambient" to "Ambient", "downtempo" to "Electronic",
        "lo-fi" to "Lo-Fi", "lofi" to "Lo-Fi", "lo fi" to "Lo-Fi",
        "chillhop" to "Lo-Fi", "jazzhop" to "Lo-Fi",
        "chiptune" to "Chiptune", "8-bit" to "Chiptune", "8bit" to "Chiptune",
        "16-bit" to "Chiptune", "bitpop" to "Chiptune",
        "k-pop" to "K-Pop", "kpop" to "K-Pop", "j-pop" to "J-Pop", "jpop" to "J-Pop",
        "c-pop" to "K-Pop", "city pop" to "J-Pop", "citypop" to "J-Pop",
        "pop" to "Pop", "poppy" to "Pop",
        "jazz" to "Jazz", "bebop" to "Jazz", "bossa" to "Jazz", "bossa nova" to "Jazz",
        "swing" to "Jazz", "blues" to "Blues", "rhythm and blues" to "Blues",
        "country" to "Country", "bluegrass" to "Country", "honky tonk" to "Country",
        "americana" to "Country", "western" to "Country",
        "folk" to "Folk", "acoustic folk" to "Folk", "singer-songwriter" to "Folk",
        "classical" to "Classical", "symphony" to "Classical", "symphonic" to "Classical",
        "concerto" to "Classical", "sonata" to "Classical", "opera" to "Classical",
        "overture" to "Classical", "nocturne" to "Classical", "etude" to "Classical",
        "waltz" to "Classical", "requiem" to "Classical", "oratorio" to "Classical",
        "baroque" to "Classical", "romantic era" to "Classical",
        "latin" to "Latin", "reggaeton" to "Latin", "salsa" to "Latin",
        "bachata" to "Latin", "samba" to "Latin", "banda" to "Latin",
        "norteno" to "Latin", "mariachi" to "Latin", "tango" to "Latin",
        "kizomba" to "Latin", "afrobeat" to "Latin", "afrobeats" to "Latin",
        "gospel" to "Gospel", "worship" to "Gospel", "hymn" to "Gospel",
        "praise" to "Gospel", "christian rock" to "Gospel", "ccm" to "Gospel",
        "soundtrack" to "Soundtrack", "ost" to "Soundtrack",
        "musical theatre" to "Soundtrack", "showtune" to "Soundtrack",
        "shanty" to "Folk", "sea shanty" to "Folk", "celtic" to "Folk",
        "medieval" to "Folk", "bardcore" to "Folk", "tavern" to "Folk",
        "qawwali" to "World", "bhajan" to "World", "kirtan" to "World",
        "enchante" to "World", "flamenco" to "World",
        "lullaby" to "Acoustic",
        "christmas" to "Holiday", "carol" to "Holiday",
        "halloween" to "Holiday", "spooky" to "Holiday",
        "kids" to "Kids", "nursery" to "Kids", "cartoon" to "Kids", "anime" to "Soundtrack",
        "comedy" to "Comedy", "parody" to "Comedy", "novelty" to "Comedy",
        "spoken" to "Spoken", "poetry" to "Spoken", "sermon" to "Spoken"
    )

    private fun genreWordHit(hay: String, word: String): Boolean {
        if (' ' !in word && '-' !in word && '&' !in word) {
            return Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(hay)
        }
        return word in hay
    }

    /** First known genre word found in Wikipedia categories, else null. */
    fun genreFromCategories(categories: List<String>): String? {
        for (c in categories) {
            val low = c.lowercase()
            for ((word, name) in genreWords) {
                if (genreWordHit(low, word)) return name
            }
        }
        return null
    }

    fun propose(song: Song, fileName: String, currentGenre: String): Proposal? {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.').trim()
        val cleaned = cleanLocal(song.title, song.artist, base)
        if (cleaned.title.isBlank()) return null
        // Genre and album come only from the online index (or existing tags):
        // never guessed offline.
        val reasons = cleaned.reasons.toMutableList()
        val high = cleaned.safe
        if (reasons.isEmpty()) return null
        if (cleaned.title == song.title && cleaned.artist.ifBlank { song.artist } == song.artist) return null
        return Proposal(
            song, cleaned.title.ifBlank { song.title },
            cleaned.artist.ifBlank { song.artist },
            song.album, currentGenre.ifBlank { "Uncategorized" },
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
                isPlaceholderAlbum(albumOf(s.mediaId), s.filePath)
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
        // minutes on per-song timeouts. Songs run several-wide with a
        // persistent answer cache, so a thousand-song library costs one
        // lookup per unique song ever.
        var offline = false
        if (online && mids.isNotEmpty()) {
            offline = try {
                !WikiLookup.reachable()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                true
            }
            if (!offline) {
                val sem = kotlinx.coroutines.sync.Semaphore(8)
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                mids.map { m ->
                    async {
                        sem.withPermit { lookupOne(m, genreOf, lookupCache) }
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
            if (!isPlaceholderAlbum(alb, m.song.filePath)) {
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
            if (!isPlaceholderAlbum(alb, m.song.filePath)) {
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
            // Never manufacture an album when the online lookup has no evidence.
            val effAlbum0 = albumOf(m.song.mediaId)
            var album = m.wikiAlbum
            if (album == null && isPlaceholderAlbum(effAlbum0, m.song.filePath)) {
                album = "Unknown album"
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
        isUnknownArtist(artist) || isUploaderLike(artist) ||
            labelWords.containsMatchIn(artist) || artist.endsWith("vevo", ignoreCase = true)

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
        hits: List<MusicBrainzLookup.Hit>
    ): WikiApplied? {
        if (hits.isEmpty() || localTitle.isBlank()) return null
        val want = WikiLookup.norm(MusicBrainzLookup.baseTitle(localTitle))
        if (want.isBlank()) return null
        val localUnknown = isUnknownArtist(localArtist)
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
                return WikiApplied(localTitle, artist, genre, reasons, true, true, h.artist)
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
        }.mapNotNull { it.album?.takeIf { a -> a.isNotBlank() } }
        return matching.firstOrNull { !isBadAlbumTitle(it) }
    }

    /**
     * Live, concert and best-of titles are never albums here: the original
     * release (or the single itself) always wins. Singles pass through
     * untouched; only the compilation/concert packaging is rejected.
     */
    fun isBadAlbumTitle(title: String): Boolean {
        val t = " $title ".lowercase()
        if (Regex("\\b(best\\s+of|greatest\\s+hits|the\\s+very\\s+best|number\\s+ones|#1'?s|anthology|collection|essentials|compilation|greatest|hits\\s+collection)\\b").containsMatchIn(t)) return true
        if (Regex("\\b(live\\s+(at|in|from|on)|live\\s+in\\s+concert|unplugged|mtv\\s+unplugged|in\\s+concert|live\\s+album|concert)\\b").containsMatchIn(t)) return true
        return false
    }

    private suspend fun lookupOne(
        m: Mid, genreOf: (Long) -> String, cache: LookupCache?
    ) {
        coroutineContext.ensureActive()
        // Modified re-uploads keep their version words: Wikipedia
        // would only find the unmodified original, so skip it.
        if (m.versioned) return
        val g0 = genreOf(m.song.mediaId)
        // Primary reading first, then the swapped order ("Title - Artist"
        // uploaders), then the bare title (uploader tags poison the query).
        val tried = mutableListOf<Pair<String, String>>(m.title to m.artist)
        m.altArtist.takeIf { it.isNotBlank() }?.let { aa ->
            m.altTitle.takeIf { it.isNotBlank() }?.let { at ->
                if (at to aa != tried[0]) tried.add(at to aa)
            }
        }
        if (!isUnknownArtist(m.artist) && WikiLookup.norm(m.artist) !in WikiLookup.norm(m.title)) {
            val solo = m.title to ""
            if (solo != tried[0] && solo !in tried) tried.add(solo)
        }
        // Once any reading confirms the title, later readings still try cheap
        // MusicBrainz (it may upgrade to High) but skip slow Wikipedia: it would
        // only re-confirm the same title.
        var confirmed = false
        for ((tt, aa) in tried) {
            coroutineContext.ensureActive()
            val solo = aa.isBlank()
            val memo = runCatching { cache?.get(tt, aa) }.getOrNull()
            if (memo != null && !memo.found) continue
            if (memo != null && memo.found) {
                // Memo artist differs from this reading: a recorded suggestion.
                val suggested = memo.artist.takeIf { it.isNotBlank() && it != aa }
                val f = Folded(
                    memo.title, suggested, memo.genre, memo.album,
                    memo.hitTitle, memo.pageId, memo.high, memo.titleConfirmed,
                    if (memo.high) listOf("Online: ${memo.hitTitle}")
                    else listOf("Online title match")
                )
                if (absorb(m, f, g0, solo)) return
                if (f.titleConfirmed) confirmed = true
                continue
            }
            // MusicBrainz first: one structured call carries performer,
            // album, genre. Wikipedia runs only when it has nothing at all.
            val mb = try {
                MusicBrainzLookup.search(tt, aa)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList()
            }
            val folded = mbFold(tt, aa, g0, mb)
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
                    if (absorb(m, f, g0, solo)) return
                    if (f.titleConfirmed) confirmed = true
                    continue
                }
            }
            if (!confirmed) {
            val hit = try {
                WikiLookup.lookup(tt, aa)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            if (hit == null) {
                runCatching { cache?.put(tt, aa, miss()) }
                continue
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
                continue
            }
            var genre = applied.genre
            var album: String? = null
            if (applied.titleConfirmed) {
                // One combined call serves categories-genre and infobox
                // genre/album together.
                val details = pageDetails(hit.pageId)
                if (genre == null) {
                    genre = details?.first?.let { genreFromCategories(it) }
                        ?: details?.second?.genre?.let { genreFromCategories(listOf(it)) }
                }
                album = WikiLookup.snippetAlbum(hit.snippet) ?: details?.second?.album
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
            if (absorb(m, f, g0, solo)) return
            if (f.titleConfirmed) confirmed = true
            } // (!confirmed)
        }
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
        // Live and best-of packaging is rejected everywhere: originals only.
        if (f.album != null && !isBadAlbumTitle(f.album)) {
            m.wikiAlbum = f.album
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
