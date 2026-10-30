package com.blissplayer.music.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.blissplayer.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline song analyzer + organizer.
 *
 * No network lookup: Android has no built-in YouTube fingerprint API, and
 * uploading 1000+ FLACs for matching would be slow, battery-heavy, and
 * unreliable. Instead this does fast local heuristics:
 *
 *  - Strips video noise from titles: Official Video, Official Audio, Lyrics,
 *    HD, 4K, MV, Visualizer, etc.
 *  - Splits "Artist - Title" filenames when tags are Unknown.
 *  - Guesses genre from title/artist keywords only when confident,
 *    otherwise leaves Uncategorized.
 *  - Confidence: High when only noise words are removed or an obvious
 *    Artist - Title split exists. Medium when the guess changes real words.
 *    Low guesses are never auto-applied; they are shown for confirmation.
 *
 * Organize copies nothing and deletes nothing. On Android 10+ it moves each
 * file's MediaStore RELATIVE_PATH into Music/BlissPlayer/Artist/Album so the
 * system file itself becomes organized. Only folders the user enabled are
 * touched. Playlists survive because they key on mediaId, which does not
 * change on move.
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
        Regex("\\b(4k|8k|hd|hq|4phd)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bm/?v\\b", RegexOption.IGNORE_CASE),
        Regex("\\bletras?\\b", RegexOption.IGNORE_CASE)
    )

    fun propose(song: Song, fileName: String, currentGenre: String): Proposal? {
        var title = song.title.trim()
        var artist = song.artist.trim()
        var album = song.album.trim()
        val reasons = mutableListOf<String>()
        var high = true

        // 1) Filename split when tags are placeholders.
        val base = fileName.substringAfterLast('/').substringBeforeLast('.').trim()
        if ((artist.equals("Unknown artist", true) || artist.isBlank()) && " - " in base) {
            val parts = base.split(" - ", limit = 2)
            if (parts[0].isNotBlank() && parts[1].isNotBlank()) {
                artist = parts[0].trim()
                title = parts[1].trim()
                reasons.add("split filename Artist - Title")
            }
        } else if ((title.equals("Unknown title", true) || title.isBlank()) && base.isNotBlank()) {
            title = base
            reasons.add("used filename as title")
            high = false
        }

        // 2) Strip video noise.
        var cleaned = title
        noise.forEach { r -> cleaned = r.replace(cleaned, " ") }
        cleaned = cleaned.replace(Regex("\\s{2,}"), " ").trim { it == ' ' || it == '-' || it == '_' || it == '.' }
            .replace(Regex("\\s+"), " ").trim()
        if (cleaned.isNotBlank() && cleaned != title) {
            // If we only removed bracketed noise, confidence stays high.
            val removedOnlyNoise = title.length - cleaned.length < 30
            if (!removedOnlyNoise) high = false
            title = cleaned
            reasons.add("removed video tags")
        }

        // 3) Genre guess only on strong keywords.
        var genre = currentGenre
        if (genre.isBlank() || genre == "Uncategorized") {
            val hay = "$title $artist".lowercase()
            genre = when {
                listOf("lofi", "lo-fi").any { it in hay } -> "Lo-Fi"
                listOf("jazz", "swing", "bossa").any { it in hay } -> "Jazz"
                listOf("rock", "metal", "punk").any { it in hay } -> "Rock"
                listOf("hip hop", "hip-hop", "rap", "trap").any { it in hay } -> "Hip-Hop"
                listOf("classical", "symphony", "sonata", "op.").any { it in hay } -> "Classical"
                listOf("edm", "house", "techno", "trance", "dubstep").any { it in hay } -> "Electronic"
                listOf("country", "bluegrass").any { it in hay } -> "Country"
                listOf("gospel", "worship", "hymn").any { it in hay } -> "Gospel"
                else -> genre
            }
            if (genre != currentGenre) {
                reasons.add("keyword genre")
                high = false
            }
        }

        if (reasons.isEmpty()) return null
        if (title.isBlank()) return null
        return Proposal(
            song, title.ifBlank { song.title },
            artist.ifBlank { song.artist },
            album.ifBlank { song.album },
            genre.ifBlank { "Uncategorized" },
            if (high) "High" else "Check",
            reasons.joinToString(", ")
        )
    }

    suspend fun analyze(
        songs: List<Song>,
        genreOf: (Long) -> String,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<Proposal> = withContext(Dispatchers.Default) {
        val out = mutableListOf<Proposal>()
        songs.forEachIndexed { i, s ->
            val fn = s.filePath?.substringAfterLast('/')?.substringBeforeLast('.') ?: s.title
            propose(s, fn, genreOf(s.mediaId))?.let { out.add(it) }
            if (i % 25 == 0) onProgress(i + 1, songs.size)
        }
        onProgress(songs.size, songs.size)
        // High-confidence first so bulk apply is safe.
        out.sortedWith(compareBy({ it.confidence != "High" }, { it.song.title.lowercase() }))
    }

    private fun sanitize(name: String): String {
        val s = name.trim().ifBlank { "Unknown" }
        val cleaned = s.map { c ->
            if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') '_' else c
        }.joinToString("").take(60).trim()
        return cleaned.ifBlank { "Unknown" }
    }

    /**
     * Move files into Music/BlissPlayer/Artist/Album via MediaStore.
     * Returns Pair(moved, skipped).
     */
    suspend fun organize(
        context: Context,
        songs: List<Song>,
        onProgress: (done: Int, total: Int, name: String) -> Unit
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext 0 to songs.size
        var moved = 0
        var skipped = 0
        songs.forEachIndexed { i, s ->
            try {
                val uri = android.net.Uri.parse(s.contentUri)
                val artist = sanitize(s.artist)
                val album = sanitize(s.album)
                val rel = "Music/BlissPlayer/$artist/$album"
                // Skip if already organized.
                if ((s.filePath ?: "").contains("/BlissPlayer/")) {
                    skipped++
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, rel)
                    }
                    val rows = context.contentResolver.update(uri, values, null, null)
                    if (rows > 0) moved++ else skipped++
                }
            } catch (e: Exception) {
                skipped++
            }
            if (i % 10 == 0) onProgress(i + 1, songs.size, s.title)
        }
        onProgress(songs.size, songs.size, "")
        moved to skipped
    }
}
