package com.blissplayer.music.data

import com.blissplayer.core.AnalyzerRules
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Keyless MusicBrainz WS/2 lookup: one recording query returns the track's
 * performer, releases (album) and folksonomy tags (genre) together.
 * Used before Wikipedia: most commercial songs resolve here in one round
 * trip, and obscure misses fall through to the Wikipedia path.
 *
 * Rate limit is one request per second; every fetch goes through a token
 * bucket (1 permit, 1s hold) plus backoff on 429/503, and a circuit
 * breaker stops burning song turns while the service refuses us.
 */
object MusicBrainzLookup {

    data class Hit(
        val track: String,
        val artist: String,
        val album: String?,
        val genre: String?,
        val lengthMs: Long? = null
    )

    private const val API = "https://musicbrainz.org/ws/2/recording/"
    private const val TIMEOUT = 8000
    private const val UA = "BlissPlayer/0.9 (local music tag lookup)"

    /** Thrown when MusicBrainz answers with slow-down/overload instead of results. */
    class RateLimited : Exception()

    private val bucket = kotlinx.coroutines.sync.Semaphore(1)
    private const val HOLD_MS = 1000L

    suspend fun <T> paced(block: suspend () -> T): T {
        bucket.acquire()
        try {
            return block()
        } finally {
            delay(HOLD_MS)
            bucket.release()
        }
    }

    private val limits = java.util.concurrent.atomic.AtomicLong(0)
    fun limitCount(): Long = limits.get()

    private val consecutiveLimits = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastProbeMs = java.util.concurrent.atomic.AtomicLong(0)
    private const val BOX_AFTER = 6
    private const val PROBE_EVERY_MS = 60000L

    private fun boxed(): Boolean {
        if (consecutiveLimits.get() < BOX_AFTER) return false
        val now = System.currentTimeMillis()
        val last = lastProbeMs.get()
        return if (now - last > PROBE_EVERY_MS && lastProbeMs.compareAndSet(last, now)) {
            false
        } else true
    }

    /** Lucene-escape one phrase for the WS/2 query syntax. */
    fun esc(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) {
            if (c in "+-!(){}[]^\"~*?:\\/") b.append('\\')
            b.append(c)
        }
        b.append('"')
        return b.toString()
    }

    /** Top recording candidates for title + artist. Empty on any trouble. */
    suspend fun search(title: String, artist: String): List<Hit> {
        if (title.isBlank()) return emptyList()
        val q = buildString {
            append("recording:").append(esc(title))
            if (artist.isNotBlank()) append(" AND artist:").append(esc(artist))
        }
        val url = API + "?query=" + URLEncoder.encode(q, "UTF-8") +
            "&fmt=json&limit=5&inc=releases+release-groups+tags"
        var attempt = 0
        while (true) {
            if (boxed()) return emptyList()
            try {
                val body = paced { getThrowing(url) } ?: return emptyList()
                consecutiveLimits.set(0)
                return parse(body)
            } catch (e: RateLimited) {
                limits.incrementAndGet()
                consecutiveLimits.incrementAndGet()
                if (++attempt > 2) return emptyList()
                delay(attempt * 5000L)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return emptyList()
            }
        }
    }

    /** True when MusicBrainz answers at all (any HTTP code counts). */
    fun reachable(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(API + "?query=test&fmt=json&limit=1").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            conn.responseCode > 0
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun getThrowing(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code == 429 || code == 503) throw RateLimited()
            if (code != 200) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: RateLimited) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Track title reduced for comparison: trailing "(feat. …)" unwanted. */
    fun baseTitle(track: String): String {
        var t = track.trim()
        while (true) {
            val m = Regex("\\s*\\(([^()]*)\\)\\s*$").find(t) ?: break
            t = t.removeRange(m.range).trim()
        }
        return t
    }

    fun norm(s: String): String = WikiLookup.norm(s)

    /** Tag names mapped to the app's display set; unknown but sane names pass through. */
    fun genreDisplay(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val low = raw.trim().lowercase()
        val mapped = mapOf(
            "rock" to "Rock", "pop" to "Pop", "metal" to "Rock",
            "alternative rock" to "Rock", "alt-rock" to "Rock", "indie" to "Rock",
            "indie rock" to "Rock", "punk" to "Rock", "punk rock" to "Rock",
            "emo" to "Rock", "grunge" to "Rock", "hard rock" to "Rock",
            "classic rock" to "Rock", "soft rock" to "Rock", "folk rock" to "Rock",
            "progressive rock" to "Rock", "psychedelic rock" to "Rock",
            "hip-hop" to "Hip-Hop", "hip hop" to "Hip-Hop", "rap" to "Hip-Hop",
            "r&b" to "R&B", "rhythm and blues" to "R&B", "soul" to "Soul",
            "funk" to "Soul", "motown" to "Soul", "disco" to "Dance",
            "dance" to "Dance", "dance-pop" to "Dance",
            "electronic" to "Electronic", "electronica" to "Electronic",
            "edm" to "Electronic", "house" to "Electronic", "techno" to "Electronic",
            "trance" to "Electronic", "dubstep" to "Electronic", "ambient" to "Electronic",
            "synth-pop" to "Electronic", "synthpop" to "Electronic", "new wave" to "Electronic",
            "industrial" to "Electronic", "country" to "Country", "jazz" to "Jazz",
            "blues" to "Blues", "classical" to "Classical", "latin" to "Latin",
            "reggaeton" to "Latin", "reggae" to "Reggae", "ska" to "Reggae",
            "k-pop" to "K-Pop", "j-pop" to "J-Pop", "folk" to "Folk",
            "singer-songwriter" to "Folk", "gospel" to "Gospel", "christian" to "Gospel",
            "soundtrack" to "Soundtrack", "christmas" to "Holiday", "jazz fusion" to "Jazz"
        )
        mapped[low]?.let { return it }
        if (raw.trim().length in 2..30 &&
            raw.trim().all { it.isLetterOrDigit() || it in " &-/" }
        ) {
            return raw.trim().split(' ').joinToString(" ") { w ->
                w.replaceFirstChar { c -> c.uppercase() }
            }
        }
        return null
    }

    /** Minimal reader for the recordings array. */
    fun parse(body: String): List<Hit> {
        val out = mutableListOf<Hit>()
        var idx = body.indexOf("\"recordings\":")
        if (idx < 0) return out
        idx = body.indexOf('[', idx)
        if (idx < 0) return out
        var i = idx + 1
        while (out.size < 5 && i < body.length) {
            // Next top-level object in the array.
            while (i < body.length && body[i] != '{' && body[i] != ']') i++
            if (i >= body.length || body[i] == ']') break
            var depth = 0
            val start = i
            var instr = false
            var esc = false
            while (i < body.length) {
                val c = body[i]
                if (instr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') instr = false
                } else {
                    if (c == '"') instr = true
                    else if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) {
                            i++
                            break
                        }
                    }
                }
                i++
            }
            readHit(body.substring(start, i))?.let { out.add(it) }
        }
        return out
    }

    private fun readHit(obj: String): Hit? {
        val track = field(obj, "\"title\"") ?: return null
        val ac = obj.indexOf("\"artist-credit\"")
        val artist = if (ac >= 0) field(obj.substring(ac), "\"name\"") ?: "" else ""
        val album = bestRelease(obj)
        val genre = bestTag(obj)
        val lengthMs = Regex("\"length\"\\s*:\\s*(\\d+)").find(obj)?.groupValues?.get(1)?.toLongOrNull()
        return Hit(track, artist, album, genre, lengthMs)
    }

    /** Prefer a dated original album, then a single; never fall back to known live/compilation groups. */
    private fun bestRelease(obj: String): String? {
        if (obj.contains("\"release-groups\"")) return bestAlbumGroup(obj)
        // Older responses without group metadata can only be filtered by title.
        return bestReleaseByStatus(obj)
    }

    /** Skip concert and compilation groups even when their title looks harmless. */
    private fun bestAlbumGroup(obj: String): String? {
        val gi = obj.indexOf("\"release-groups\"")
        if (gi < 0) return null
        val groups = splitArrayObjects(obj.substring(gi))
        fun secondary(o: String): String {
            val i = o.indexOf("\"secondary-types\"")
            if (i < 0) return ""
            val end = o.indexOf(']', i).takeIf { it >= 0 } ?: return ""
            return o.substring(i, end + 1)
        }
        val eligible = groups.filter { g ->
            val s = secondary(g)
            val title = field(g, "\"title\"")
            !title.isNullOrBlank() && !AnalyzerRules.isBadAlbumTitle(title) &&
                listOf("Live", "Compilation", "Soundtrack", "Remix").none { "\"$it\"" in s }
        }
        for (kind in listOf("Album", "Single")) {
            eligible.filter { field(it, "\"primary-type\"") == kind }
                .minByOrNull { field(it, "\"first-release-date\"")?.takeIf(String::isNotBlank) ?: "9999" }
                ?.let { return field(it, "\"title\"") }
        }
        return null
    }

    /** Top-level {...} objects of the first [...] array in sub. */
    private fun splitArrayObjects(sub: String): List<String> {
        val objs = mutableListOf<String>()
        var i = sub.indexOf('[')
        if (i < 0) return objs
        i++
        while (i in 1 until sub.length && objs.size < 100) {
            while (i < sub.length && sub[i] != '{' && sub[i] != ']') i++
            if (i >= sub.length || sub[i] == ']') break
            var depth = 0
            val start = i
            var instr = false
            var esc = false
            while (i < sub.length) {
                val c = sub[i]
                if (instr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') instr = false
                } else {
                    if (c == '"') instr = true
                    else if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) {
                            i++
                            break
                        }
                    }
                }
                i++
            }
            objs.add(sub.substring(start, i))
        }
        return objs
    }

    private fun bestReleaseByStatus(obj: String): String? {
        val ri = obj.indexOf("\"releases\"")
        if (ri < 0) return null
        val sub = obj.substring(ri)
        // Split release objects by depth like parse() does.
        val objs = mutableListOf<String>()
        var i = sub.indexOf('[') + 1
        while (i in 1 until sub.length && objs.size < 6) {
            while (i < sub.length && sub[i] != '{' && sub[i] != ']') i++
            if (i >= sub.length || sub[i] == ']') break
            var depth = 0
            val start = i
            var instr = false
            var esc = false
            while (i < sub.length) {
                val c = sub[i]
                if (instr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') instr = false
                } else {
                    if (c == '"') instr = true
                    else if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) {
                            i++
                            break
                        }
                    }
                }
                i++
            }
            objs.add(sub.substring(start, i))
        }
        var fallback: String? = null
        for (o in objs) {
            val t = field(o, "\"title\"") ?: continue
            if (AnalyzerRules.isBadAlbumTitle(t)) continue
            if (fallback == null) fallback = t
            if (field(o, "\"status\"") == "Official") return t
        }
        return fallback
    }

    /** Highest-count tag that maps to a display genre. */
    private fun bestTag(obj: String): String? {
        val scored = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (true) {
            val ti = obj.indexOf("\"tags\"", i)
            if (ti < 0) break
            val bi = obj.indexOf('[', ti)
            if (bi < 0) break
            var j = bi + 1
            var depth = 0
            val start = j
            var instr = false
            var esc = false
            while (j < obj.length) {
                val c = obj[j]
                if (instr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') instr = false
                } else {
                    if (c == '"') instr = true
                    else if (c == '[') depth++
                    else if (c == ']') {
                        if (depth == 0) break
                        depth--
                    }
                }
                j++
            }
            val arr = obj.substring(start, j)
            for (m in Regex("\\{[^\\{\\}]*\\}").findAll(arr)) {
                val name = field(m.value, "\"name\"") ?: continue
                val count = Regex("\"count\"\\s*:\\s*(\\d+)").find(m.value)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                scored.add(count to name)
            }
            i = j + 1
        }
        return scored.sortedByDescending { it.first }
            .firstNotNullOfOrNull { genreDisplay(it.second) }
    }

    /** "key": "string value" reader with unescaping. */
    private fun field(obj: String, key: String): String? {
        var i = obj.indexOf(key)
        if (i < 0) return null
        i = obj.indexOf(':', i + key.length)
        if (i < 0) return null
        i++
        while (i < obj.length && obj[i].isWhitespace()) i++
        if (i >= obj.length || obj[i] != '"') return null
        val b = StringBuilder()
        i++
        while (i < obj.length) {
            val c = obj[i]
            if (c == '\\' && i + 1 < obj.length) {
                when (obj[i + 1]) {
                    '"', '\\', '/' -> b.append(obj[i + 1])
                    'n' -> b.append('\n')
                    'u' -> {
                        val hex = obj.substring(i + 2, (i + 6).coerceAtMost(obj.length))
                        b.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> b.append(obj[i + 1])
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                b.append(c)
                i++
            }
        }
        return b.toString()
    }
}
