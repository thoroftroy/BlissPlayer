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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        "(official|live|music\\s+video|video|audio|hd|hq|4k|lyrics?|visualizer|vevo|full\\s+album|playlist|dj\\s+mix|jukebox|topic|mv|interviews?|slowed|reverb|reverbed|sped|boosted|nightcore|daycore|8d|tiktok|bass|upgrade|upgrades|remaster|remastered|original|version|album|part|pt\\b|hardcore|worldwide|hcww)",
        RegexOption.IGNORE_CASE
    )

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
        // Leading/trailing quote underscores are wrappers: _Song_ -> Song.
        title = title.trim('_', ' ', '-', '.', '"', '“', '”', '\'', '‘', '’')
        artist = artist.trim('_', ' ', '-', '.', '"', '“', '”', '\'', '‘', '’')
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
        if (initialTitle != title || initialArtist != artist) reasons.add("removed surrounding quotes")
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

        // Dashed titles: decide Artist - Title vs Title - Artist vs Title - Description.
        if (" - " in title && !isUnknownArtist(artist)) {
            val head = title.substringBefore(" - ").trim()
            val tail = title.substringAfter(" - ").trim()
            val headIsArtist = head.isNotBlank() && normNoSpace(head) == normNoSpace(artist)
            val tailIsArtist = tail.isNotBlank() && normNoSpace(tail) == normNoSpace(artist)
            if (tailIsArtist && head.isNotBlank()) {
                // "Adipocere - Rusty Cage" with artist Rusty Cage: reversed.
                title = head
                if ("removed artist suffix" !in reasons) reasons.add("removed artist suffix")
            } else if (headIsArtist && tail.isNotBlank()) {
                title = tail
                if ("removed artist prefix" !in reasons) reasons.add("removed artist prefix")
            } else if (head.isNotBlank() && tail.isNotBlank() &&
                WikiLookup.norm(artist) !in WikiLookup.norm(title)
            ) {
                // "Bad Romance - Vintage ... Cover" is Title - Description: keep the
                // real performer, strip the description instead of inventing an artist.
                val tailIsDescription = isJunkTail(tail) ||
                    Regex("(?i)\\b(cover|vintage|style|tribute|ft\\.?|feat\\.?|version|remix|acoustic|live|official)\\b")
                        .containsMatchIn(tail)
                if (tailIsDescription) {
                    title = head
                    if ("removed description suffix" !in reasons) reasons.add("removed description suffix")
                } else if (labelWords.containsMatchIn(artist) || isUploaderLike(artist) ||
                    artist.endsWith("vevo", ignoreCase = true)
                ) {
                    // Uploader channel: "16 Stitch - Things They Say" by TheKrow.
                    // "Ain't Got No ... - Nina Simone" by drMandinga (order checked online).
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
        if (cleaned.title == song.title && cleaned.artist.ifBlank { song.artist } == song.artist &&
            genre == currentGenre) return null
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
    ): List<Proposal> {
        // Report on the caller's dispatcher (Main in the UI), never on the worker.
        val progressContext = currentCoroutineContext().minusKey(Job)
        return analyzeOnWorker(songs, genreOf, albumOf, online) { done, total, phase ->
            withContext(progressContext) { onProgress(done, total, phase) }
        }
    }

    private suspend fun analyzeOnWorker(
        songs: List<Song>, genreOf: (Long) -> String, albumOf: (Long) -> String, online: Boolean,
        report: suspend (Int, Int, String) -> Unit
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
            if (i % 25 == 0) report(i + 1, songs.size, "names")
        }
        report(songs.size, songs.size, "names")

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
                coroutineContext.ensureActive()
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
                        coroutineContext.ensureActive()
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
                            m.high = true
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
                report(i + 1, mids.size, "lookup")
            }
        }
        report(songs.size, songs.size, "done")

        val out = mids.mapNotNull { m ->
            val g0 = genreOf(m.song.mediaId)
            var genre = m.wikiGenre ?: keywordGenre(m.title, m.artist, g0)
            if (genre != g0 && genre != "Uncategorized" &&
                m.reasons.none { it.startsWith("genre") || it.startsWith("Wikipedia genre") }
            ) {
                m.reasons.add("keyword genre")
                m.high = false
            }
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
