package com.blissplayer.music.data

import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/**
 * Keyless Wikipedia lookup for the song analyzer. Two cheap calls per song:
 * search (title + artist) then categories of the top hit for genre words.
 * Everything is parsed with a tiny purpose-built reader (no JSON library),
 * polite user agent, short timeouts, and a small delay between songs.
 * Any network failure returns null so the caller falls back to offline rules.
 */
object WikiLookup {

    data class Hit(
        val pageId: Long,
        val title: String,
        val snippet: String,
        val categories: List<String>
    )

    private const val API = "https://en.wikipedia.org/w/api.php"
    private const val UA = "BlissPlayer/0.7 (local music tag lookup)"
    private const val TIMEOUT = 7000

    /** Cheap connectivity probe: one tiny search, short timeout. */
    fun reachable(): Boolean {
        // Any HTTP answer (even 429 throttling) means the network is up.
        // A null body from get() on a throttle must not read as "offline".
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$API?action=query&list=search&srsearch=test&format=json&srlimit=1")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            conn.responseCode > 0
        } catch (e: Exception) {
            // Wikipedia down/blocked: MusicBrainz may still answer.
            try {
                MusicBrainzLookup.reachable()
            } catch (e2: Exception) {
                false
            }
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * The album a song belongs to, from the song page infobox (section 0).
     * Returns null when the page has no readable album field.
     */
    fun albumOf(pageId: Long): String? = infoboxOf(pageId)?.album

    /** Album + genre from one section-0 wikitext fetch. */
    data class Infobox(val album: String?, val genre: String?)

    fun infoboxOf(pageId: Long): Infobox? {
        return try {
            paceBlocking()
            val url = "$API?action=query&prop=revisions&rvprop=content&rvslots=main" +
                "&rvsection=0&pageids=$pageId&format=json"
            val body = get(url, 9000) ?: return null
            Infobox(parseAlbum(body), parseGenre(body))
        } catch (e: Exception) {
            null
        }
    }

    // Blocking twin of [paced] for the blocking infobox path: its own small
    // permit pool so blocking callers never deadlock the coroutine bucket.
    private val blockingPermits = java.util.concurrent.Semaphore(2)

    fun paceBlocking() {
        blockingPermits.acquire()
        try {
            Thread.sleep(600L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            blockingPermits.release()
        }
    }

    /** First "| genre = ..." line in wikitext: first value only, links stripped. */
    fun parseGenre(body: String): String? {
        val text = extractContent(body) ?: body
        val m = Regex("(?im)^\\s*\\|\\s*genre\\s*=\\s*(.+)$").find(text) ?: return null
        var v = m.groupValues[1].trim()
        if (v.isBlank()) return null
        // First of several ("Heavy metal, Hard rock", "Pop<br/>Rock").
        // Never split link pipes: [[Rhythm and blues|R&B]] is one genre.
        v = v.split(Regex("(?i)<br\\s*/?>|\\n|,|;"))[0].trim()
        v = v.replace("''", "").trim()
        v = Regex("<!--.*?-->").replace(v, "").trim()
        v = v.trimStart('*', '#', '-', ' ', '\t').trim()
        val link = Regex("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]").find(v)
        v = if (link != null) {
            val shown = link.groupValues[2]
            if (shown.isNotBlank()) shown else link.groupValues[1]
        } else {
            v.replace("\"", "").trim()
        }.trim()
        v = Regex("\\s*\\([^()]*\\)\\s*$").replace(v, "").trim()
        if (v.isBlank() || v.length > 40) return null
        return v
    }

    /**
     * Album named in the article prose: "…song … from their fifth studio
     * album, Rattle and Hum" or "…for her third studio album, 25 (2015)".
     * Many song pages never fill the infobox album field but say it here.
     */
    fun snippetAlbum(snippet: String): String? {
        val plain = plainSnippet(snippet)
        val m = Regex(
            "\\b(?:from|for)\\s+(?:the|their|his|her|its|a)\\s+(?:[A-Za-z-]+\\s+){0,4}?(?:studio\\s+)?album[,:]?\\s+([^.,;()]{2,60})",
            RegexOption.IGNORE_CASE
        ).find(plain) ?: return null
        var v = m.groupValues[1].trim().trim('"', '\'')
        // "25 (2015)" already lost its parens above; drop trailing years.
        v = Regex("\\s+\\d{4}\\s*$").replace(v, "").trim()
        if (v.isBlank() || v.length < 2) return null
        val low = v.lowercase()
        if (low in setOf("era", "cycle", "trilogy", "series", "project", "the same name", "of the same name")) return null
        if (!v[0].isUpperCase() && !v[0].isDigit()) return null
        return v
    }

    /** First "| album = ..." line in wikitext, cleaned of links and markup. */
    fun parseAlbum(body: String): String? {
        // API responses wrap section-0 wikitext in a JSON string; unwrap it.
        // Bare wikitext (tests) is used as-is.
        val text = extractContent(body) ?: body
        val m = Regex("(?im)^\\s*\\|\\s*album\\s*=\\s*(.+)$").find(text) ?: return null
        var v = m.groupValues[1].trim()
        if (v.isBlank()) return null
        // Italic markup and HTML comments out.
        v = v.replace("''", "").trim()
        v = Regex("<!--.*?-->").replace(v, "").trim()
        // [[Target (disambiguation)|Display]] -> Display; [[Target]] -> Target.
        val link = Regex("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]").find(v)
        v = if (link != null) {
            val shown = link.groupValues[2]
            if (shown.isNotBlank()) shown else link.groupValues[1]
        } else {
            v.replace("\"", "").trim()
        }.trim()
        // Strip "(artist album)" style disambiguation, keep the real name.
        v = Regex("\\s*\\([^()]*\\b(album|song|single)\\b[^()]*\\)\\s*$", RegexOption.IGNORE_CASE)
            .replace(v, "").trim()
        if (v.isBlank() || v.length > 80) return null
        return v
    }

    /**
     * Page id of the best song-article match, without fetching categories.
     * Used for genre backfill when another source already confirmed the
     * title: one search call, details follow only on a match.
     */
    suspend fun searchPageId(title: String, artist: String): Long? {
        val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (q.isBlank()) return null
        return try {
            val searchUrl = "$API?action=query&list=search&srsearch=" +
                URLEncoder.encode(q, "UTF-8") +
                "&format=json&srlimit=8&srnamespace=0"
            val body = getPatient(searchUrl) ?: return null
            val hits = parseSearch(body)
            if (hits.isEmpty()) return null
            if (!coroutineContext.isActive) return null
            pickBest(hits, title)?.first
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /** Pull the revisions content string out of an API response, unescaped. */
    fun extractContent(body: String): String? {
        for (key in listOf("\"content\":\"", "\"*\":\"")) {
            val k = body.indexOf(key)
            if (k < 0) continue
            val q = quoted(body, k + key.length - 1) ?: continue
            return unescape(q.first)
        }
        return null
    }

    // Wikipedia throttles concurrent bulk callers, so fetches go through a
    // token bucket: at most PERMITS overlap, each holding its permit for
    // HOLD_MS. Throttles back off with one retry and never poison the cache.
    private val bucket = kotlinx.coroutines.sync.Semaphore(4)
    private const val HOLD_MS = 500L

    suspend fun <T> paced(block: suspend () -> T): T {
        bucket.acquire()
        try {
            return block()
        } finally {
            // Cancellation-safe: a Stop pressed mid-hold must never skip
            // the release, or every later lookup in this process stalls.
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    kotlinx.coroutines.delay(HOLD_MS)
                }
            } finally {
                bucket.release()
            }
        }
    }

    class Throttled : Exception()

    private val throttles = java.util.concurrent.atomic.AtomicLong(0)
    fun throttleCount(): Long = throttles.get()

    suspend fun lookup(title: String, artist: String): Hit? {
        val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (q.isBlank()) return null
        return try {
            val searchUrl = "$API?action=query&list=search&srsearch=" +
                URLEncoder.encode(q, "UTF-8") +
                "&format=json&srlimit=8&srnamespace=0"
            val body = getPatient(searchUrl) ?: return null
            val hits = parseSearch(body)
            if (hits.isEmpty()) return null
            if (!coroutineContext.isActive) return null
            val top = pickBest(hits, title) ?: return null
            // Categories ride along later in one combined pageDetails call,
            // only for title-confirmed hits: unconfirmed guesses cannot
            // complete, so fetching their categories is pure overhead.
            Hit(top.first, top.second, top.third, emptyList())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /**
     * Categories + infobox (genre, album) for one page in a single request.
     * Used only for title-confirmed hits, so the bulk run pays one call
     * instead of two. Null on any trouble.
     */
    suspend fun pageDetails(pageId: Long): Pair<List<String>, Infobox?>? {
        return try {
            val url = "$API?action=query&prop=categories|revisions&pageids=$pageId" +
                "&format=json&cllimit=100&rvprop=content&rvslots=main&rvsection=0"
            val body = getPatient(url, 9000) ?: return null
            parseCategories(body) to
                Infobox(parseAlbum(body), parseGenre(body)).takeIf { it.album != null || it.genre != null }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /**
     * Paced fetch with one immediate retry on 429. Returns null on plain
     * network trouble or a second throttle: the song simply stays
     * unresolved for this pass rather than stalling the whole bulk run.
     */
    private suspend fun getPatient(url: String, timeout: Int = TIMEOUT): String? {
        try {
            return paced { getThrowing(url, timeout) }
        } catch (e: Throttled) {
            throttles.incrementAndGet()
            return try {
                paced { getThrowing(url, timeout) }
            } catch (e2: Exception) {
                if (e2 is kotlinx.coroutines.CancellationException) throw e2
                if (e2 is Throttled) throttles.incrementAndGet()
                null
            }
        }
    }

    private fun getThrowing(url: String, timeout: Int = TIMEOUT): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.instanceFollowRedirects = true
            if (conn.responseCode == 429) throw Throttled()
            if (conn.responseCode != 200) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: Throttled) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun get(url: String, timeout: Int = TIMEOUT): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Minimal JSON string unescaper for \" \\ \/ \n and \uXXXX. */
    fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                out.append(c)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                '"', '\\', '/' -> {
                    out.append(n)
                    i += 2
                }
                'n' -> {
                    out.append('\n')
                    i += 2
                }
                't' -> {
                    out.append('\t')
                    i += 2
                }
                'u' -> {
                    if (i + 5 < s.length) {
                        val hex = s.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) out.append(code.toChar())
                        i += 6
                    } else i += 2
                }
                else -> {
                    out.append(n)
                    i += 2
                }
            }
        }
        return out.toString()
    }

    /**
     * Pull (pageid, title, snippet) triples out of a list=search response.
     * Search items arrive as {"ns":..,"title":..,"pageid":..,..., "snippet":..},
     * so each triple is read forward from its title and never crosses into
     * the next item.
     */
    fun parseSearch(body: String): List<Triple<Long, String, String>> {
        val out = mutableListOf<Triple<Long, String, String>>()
        var idx = 0
        while (true) {
            val tKey = body.indexOf("\"title\":", idx)
            if (tKey < 0) break
            val tVal = quoted(body, tKey + 8) ?: break
            val nextTitle = body.indexOf("\"title\":", tVal.second)
            val pKey = body.indexOf("\"pageid\":", tVal.second)
            if (pKey < 0 || (nextTitle >= 0 && pKey > nextTitle)) {
                idx = tVal.second
                continue
            }
            var j = pKey + 9
            while (j < body.length && (body[j].isWhitespace() || body[j] == ':')) j++
            var k = j
            while (k < body.length && body[k].isDigit()) k++
            val id = body.substring(j, k).toLongOrNull()
            if (id == null) {
                idx = tVal.second
                continue
            }
            val sKey = body.indexOf("\"snippet\":", k)
            if (sKey < 0 || (nextTitle >= 0 && sKey > nextTitle)) {
                idx = tVal.second
                continue
            }
            val sVal = quoted(body, sKey + 10) ?: break
            // Skip query-level echoes (searchinfo/suggestion titles).
            if (id > 0) out.add(Triple(id, unescape(tVal.first), unescape(sVal.first)))
            idx = sVal.second
        }
        return out
    }

    /** Pull Category: names out of a prop=categories response. */
    fun parseCategories(body: String): List<String> {
        val out = mutableListOf<String>()
        var idx = 0
        while (true) {
            val t = body.indexOf("\"title\":", idx)
            if (t < 0) break
            val v = quoted(body, t + 8) ?: break
            val name = unescape(v.first)
            if (name.startsWith("Category:")) out.add(name.removePrefix("Category:"))
            idx = v.second
        }
        return out
    }

    /** Read the next "..." literal starting at or after [from]. Returns text + end offset. */
    fun quoted(body: String, from: Int): Pair<String, Int>? {
        var i = from
        while (i < body.length && body[i] != '"') i++
        if (i >= body.length) return null
        i++
        val sb = StringBuilder()
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                sb.append(c)
                sb.append(body[i + 1])
                i += 2
                continue
            }
            if (c == '"') return sb.toString() to (i + 1)
            sb.append(c)
            i++
        }
        return null
    }

    /**
     * Search ranking is fickle (a discography can outrank the song), so do
     * not blindly take the first hit. Prefer an exact title match, then any
     * hit that reads like a song article, then the first hit.
     */
    fun pickBest(
        hits: List<Triple<Long, String, String>>,
        localTitle: String
    ): Triple<Long, String, String>? {
        if (hits.isEmpty()) return null
        val want = norm(localTitle)
        if (want.isNotBlank()) {
            hits.firstOrNull { norm(canonicalTitle(it.second)) == want }?.let { return it }
        }
        hits.firstOrNull { h ->
            plainSnippet(h.third).contains(Regex("\\bsong\\b.*\\bby\\b", RegexOption.IGNORE_CASE))
        }?.let { return it }
        return hits.first()
    }

    /** Strip tag soup from snippets so names can be compared. */    fun plainSnippet(snippet: String): String =
        snippet.replace(Regex("<[^>]*>"), " ")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Pull the performer out of "X" is a ... song (recorded|performed|...) by NAME,
     * dropping leading descriptors like "English singer" or "South Korean singer".
     */
    fun snippetArtist(snippet: String): String? {
        val plain = plainSnippet(snippet)
        val m = Regex(
            "\"[^\"]+\"[^.]{0,120}?\\bsong\\b[^.]{0,80}?\\bby\\b\\s+([^.,;()]+)",
            RegexOption.IGNORE_CASE
        ).find(plain) ?: return null
        var name = m.groupValues[1].trim()
        name = name.replace(
            Regex("^(?:[A-Z][a-z]+(?:-[A-Z][a-z]+)?\\s+){1,2}(singer-songwriter|singer|rapper|band|duo|group|trio|musician|artist|songwriter|producer|DJ)\\s+", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        if (name.length < 2 || name.length > 60) return null
        if (!name[0].isUpperCase()) return null
        return name
    }

    /**
     * Song-page title reduced for comparison and display. Trailing brackets
     * go except real version markers: "(Adele song)" and "(A Man After
     * Midnight)" are index/subtitle noise, but "(Taylor's Version)" names
     * the recording the file might actually be.
     */
    private val keepParen = Regex(
        "\\b(version|remix|remixes|live|acoustic|unplugged|explicit|clean|demo|edit|mix|cover|extended|original|radio|re-recorded|re-record)\\b",
        RegexOption.IGNORE_CASE
    )

    /** "Title (Artist song)" -> "Title". Only strips song-disambiguation brackets. */
    fun canonicalTitle(pageTitle: String): String {
        var t = pageTitle.trim()
        while (true) {
            val m = Regex("\\s*\\(([^()]*)\\)\\s*$").find(t) ?: break
            val inner = m.groupValues[1]
            if (!inner.contains("song", ignoreCase = true) &&
                !inner.contains("single", ignoreCase = true) &&
                keepParen.containsMatchIn(inner)
            ) break
            t = t.removeRange(m.range).trim()
        }
        return t
    }

    /** Normalize for comparison: lowercase, no accents, only letters and digits. */
    fun norm(s: String): String {
        val n = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        return n.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
