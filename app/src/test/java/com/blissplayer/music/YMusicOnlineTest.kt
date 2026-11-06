package com.blissplayer.music

import com.blissplayer.music.data.MusicBrainzLookup
import com.blissplayer.music.data.SongAnalyzer
import com.blissplayer.music.data.WikiLookup
import com.blissplayer.music.model.Song
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Real online coverage over the whole YMusic corpus. Slow (network) and
 * opt-in: run with BLISS_YMUSIC_ONLINE=1. Normal unit runs skip it.
 */
class YMusicOnlineTest {
    @Test
    fun onlineGenreAlbumCoverage() {
        Assume.assumeTrue(
            "set BLISS_YMUSIC_ONLINE=1 to run the live bulk measurement",
            System.getenv("BLISS_YMUSIC_ONLINE") == "1"
        )
        val dir = File(System.getProperty("user.home"), "Applications/Wonderdesk Applications/YMusic")
            .takeIf { it.isDirectory } ?: File("/home/microwavedthebaby/Downloads/YMusic")
        val files = dir.listFiles { f -> f.isFile }!!.sortedBy { it.name }
        // Persistent tag metadata: jaudiotagger re-reads of 1177 files cost
        // minutes on every run, so file name -> [tagTitle, tagArtist] is kept
        // in /tmp/opencode/ymeta.json across runs.
        val metaFile = File("/tmp/opencode/ymeta.json")
        val meta = mutableMapOf<String, List<String>>()
        runCatching {
            if (metaFile.exists()) {
                val raw = metaFile.readText()
                Regex("\"((?:[^\"\\\\]|\\\\.)*)\":\\[\"((?:[^\"\\\\]|\\\\.)*)\",\"((?:[^\"\\\\]|\\\\.)*)\"\\]")
                    .findAll(raw).forEach { m ->
                        fun un(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")
                        meta[un(m.groupValues[1])] = listOf(un(m.groupValues[2]), un(m.groupValues[3]))
                    }
            }
        }
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        var metaDirty = false
        val songs = files.mapIndexed { i, f ->
            val base = f.nameWithoutExtension
            var tagTitle: String? = null
            var tagArtist: String? = null
            val memo = meta[f.name]
            if (memo != null) {
                tagTitle = memo[0].takeIf { it.isNotEmpty() }
                tagArtist = memo[1].takeIf { it.isNotEmpty() }
            } else {
                try {
                    val audio = AudioFileIO.read(f)
                    val tag = audio.tag
                    if (tag != null) {
                        tagTitle = runCatching { tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE) }.getOrNull()?.takeIf { it.isNotBlank() }
                        tagArtist = runCatching { tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST) }.getOrNull()?.takeIf { it.isNotBlank() }
                    }
                } catch (_: Exception) {}
                meta[f.name] = listOf(tagTitle ?: "", tagArtist ?: "")
                metaDirty = true
            }
            Song(
                mediaId = 1000000L + i, contentUri = "content://ymusic/$i",
                filePath = f.absolutePath,
                title = tagTitle ?: base, artist = tagArtist ?: "Unknown artist",
                album = "Unknown album", durationMs = 200000, sizeBytes = f.length(),
                mimeType = null, modified = 1
            )
        }
        if (metaDirty) {
            runCatching {
                metaFile.writeText(meta.entries.joinToString(",", "{", "}") { (k, v) ->
                    "\"${esc(k)}\":[\"${esc(v[0])}\",\"${esc(v[1])}\"]"
                })
            }
        }
        // Persistent lookup answers: (title, artist) -> CachedLookup fields,
        // so reruns only pay for misses and coverage accumulates across runs.
        val cacheFile = File("/tmp/opencode/ycache.json")
        val cmap = java.util.concurrent.ConcurrentHashMap<String, SongAnalyzer.CachedLookup>()
        val pendingKeys = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
        runCatching {
            if (cacheFile.exists()) {
                var n = 0
                Regex("\\[\"((?:[^\"\\\\]|\\\\.)*)\",\"((?:[^\"\\\\]|\\\\.)*)\",\"((?:[^\"\\\\]|\\\\.)*)\",\"((?:[^\"\\\\]|\\\\.)*)\",\"((?:[^\"\\\\]|\\\\.)*)\",\"((?:[^\"\\\\]|\\\\.)*)\",(-?\\d+),\"((?:[^\"\\\\]|\\\\.)*)\",(true|false),(true|false),(true|false)\\]")
                    .findAll(cacheFile.readText()).forEach { m ->
                        fun un(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")
                        val key = SongAnalyzer.cacheKey(un(m.groupValues[1]), un(m.groupValues[2]))
                        fun unN(s: String) = un(s).takeIf { it.isNotEmpty() }
                        cmap[key] = SongAnalyzer.CachedLookup(
                            un(m.groupValues[3]), un(m.groupValues[4]),
                            unN(m.groupValues[5]), unN(m.groupValues[6]),
                            m.groupValues[7].toLong(), un(m.groupValues[8]),
                            m.groupValues[9].toBoolean(), m.groupValues[10].toBoolean(),
                            m.groupValues[11].toBoolean()
                        )
                        pendingKeys[key] = un(m.groupValues[1]) to un(m.groupValues[2])
                        n++
                    }
                println("YMONLINE resumed with $n cached lookups")
            }
        }
        var puts = 0
        fun saveCache() {
            runCatching {
                cacheFile.writeText(cmap.entries.joinToString(",", "[", "]") { (k, c) ->
                    val q = pendingKeys[k]
                    "[\"${esc(q?.first ?: c.title)}\",\"${esc(q?.second ?: c.artist)}\",\"${esc(c.title)}\",\"${esc(c.artist)}\",\"${esc(c.genre ?: "")}\",\"${esc(c.album ?: "")}\",${c.pageId},\"${esc(c.hitTitle)}\",${c.high},${c.titleConfirmed},${c.found}]"
                })
            }
        }
        val cache = object : SongAnalyzer.LookupCache {
            override fun get(title: String, artist: String) = cmap[SongAnalyzer.cacheKey(title, artist)]
            override fun put(title: String, artist: String, hit: SongAnalyzer.CachedLookup) {
                val k = SongAnalyzer.cacheKey(title, artist)
                cmap[k] = hit
                pendingKeys[k] = title to artist
                if (++puts % 25 == 0) saveCache()
            }
        }
        val out = runBlocking {
            SongAnalyzer.analyze(songs, { "Uncategorized" }, { "Unknown album" }, true, cache) { done, total, phase ->
                if (done % 100 == 0 || done == total) println("YMONLINE $phase $done/$total")
                if (done % 25 == 0 || done == total) {
                    runCatching {
                        File("/tmp/opencode/ymprogress.txt").writeText("$phase $done/$total ${System.currentTimeMillis()} mbLimited=${MusicBrainzLookup.limitCount()} wiki429=${WikiLookup.throttleCount()}")
                    }
                }
            }
        }
        saveCache()
        val byId = out.associateBy { it.song.mediaId }
        var complete = 0
        var genreFilled = 0
        var albumFilled = 0
        var high = 0
        val missing = mutableListOf<String>()
        for (s in songs) {
            val p = byId[s.mediaId]
            val genre = p?.newGenre ?: "Uncategorized"
            val album = p?.newAlbum ?: "Unknown album"
            val artist = p?.newArtist ?: s.artist
            if (genre != "Uncategorized") genreFilled++
            if (album != "Unknown album") albumFilled++
            if (p != null && p.confidence == "High") high++
            if (!SongAnalyzer.isUnknownArtist(artist) && genre != "Uncategorized" && album != "Unknown album") {
                complete++
            } else if (missing.size < 60) {
                missing.add("FILE=${s.filePath?.substringAfterLast('/')} | title=${p?.newTitle ?: s.title} | artist=$artist | genre=$genre | album=$album")
            }
        }
        println("YMONLINE total=${songs.size} proposals=${out.size} high=$high genreFilled=$genreFilled albumFilled=$albumFilled complete=$complete")
        println("YMONLINE completeCoverage=${complete * 100.0 / songs.size}")
        println("YMONLINE --- INCOMPLETE SAMPLE (${missing.size}) ---")
        missing.forEach { println("YMONLINE INC $it") }
        assertTrue("online completeness too low: $complete/${songs.size}", complete >= 1000)
    }
}
