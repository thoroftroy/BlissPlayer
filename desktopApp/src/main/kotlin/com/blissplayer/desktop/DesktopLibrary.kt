package com.blissplayer.desktop

import com.blissplayer.core.LibraryAccess
import com.blissplayer.core.LibraryEntry
import java.io.File
import java.nio.file.Files

/**
 * Filesystem library: same audio extensions and system-sound exclusions as
 * the Android MediaStore scan, same organize destinations
 * (Music/BlissPlayer/Artist/Album), same enabled-folders-only rule.
 * Stable ids are path hashes (desktop has no MediaStore ids).
 */
class DesktopLibrary(private val musicRoot: File) : LibraryAccess {

    companion object {
        val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "mid", "midi")
        private val SYS_DIRS = listOf("/Ringtones/", "/Alarms/", "/Notifications/", "/Android/", "/MIUI/", "/DCIM/", "/Sounds/")
        fun isSystemPath(path: String): Boolean = SYS_DIRS.any { path.contains(it, ignoreCase = true) }
        fun stableId(path: String): Long = (path.hashCode().toLong() shl 32) xor (path.length.toLong() and 0xffffffffL)
        fun sanitize(name: String): String {
            val s = name.trim().ifBlank { "Unknown" }
            return s.map { c -> if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') '_' else c }
                .joinToString("").take(80).trim().ifBlank { "Unknown" }
        }
    }

    override fun roots(): List<String> {
        if (!musicRoot.exists()) return emptyList()
        return musicRoot.walkTopDown().maxDepth(3)
            .filter { it.isDirectory && !isSystemPath(it.absolutePath) }
            .map { it.absolutePath }.toList()
    }

    override fun scan(enabledRoots: Set<String>, onProgress: (done: Int, total: Int) -> Unit): List<LibraryEntry> {
        val files = musicRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTS && !isSystemPath(it.absolutePath) }
            .toList()
        val enabled = if (enabledRoots.isEmpty()) setOf(musicRoot.absolutePath) else enabledRoots
        val out = mutableListOf<LibraryEntry>()
        files.forEachIndexed { i, f ->
            if (enabled.any { root -> f.absolutePath == root || f.absolutePath.startsWith(root.trimEnd('/') + "/") }) {
                val (tags, dur) = DesktopTags.info(f.absolutePath) ?: (null to 0L)
                out.add(LibraryEntry(
                    key = f.absolutePath,
                    path = f.absolutePath,
                    title = tags?.title?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension,
                    artist = tags?.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
                    album = tags?.album?.takeIf { it.isNotBlank() } ?: "Unknown album",
                    durationMs = dur,
                    sizeBytes = f.length()
                ))
            }
            if (i % 50 == 0) onProgress(i + 1, files.size)
        }
        onProgress(files.size, files.size)
        return out.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.title.lowercase() }))
    }

    override fun organize(entries: List<LibraryEntry>, destinations: Map<String, String>): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        for (e in entries) {
            val dest = destinations[e.key] ?: continue
            try {
                val src = File(e.path)
                val dst = File(dest)
                if (!src.exists()) {
                    errors[e.key] = "Source is gone."
                    continue
                }
                dst.parentFile?.mkdirs()
                if (src.absolutePath == dst.absolutePath) continue
                Files.move(src.toPath(), dst.toPath())
            } catch (ex: Exception) {
                errors[e.key] = ex.message ?: "Move failed."
            }
        }
        return errors
    }

    /** Destination for one entry under root/Music/BlissPlayer/Artist/Album. */
    fun destinationFor(root: File, artist: String, album: String, fileName: String): String =
        File(root, "BlissPlayer/${sanitize(artist)}/${sanitize(album)}/${sanitize(fileName)}").absolutePath
}
