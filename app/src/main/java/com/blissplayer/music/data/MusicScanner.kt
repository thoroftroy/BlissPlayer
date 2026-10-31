package com.blissplayer.music.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.blissplayer.music.model.Song

/** Scans device audio via MediaStore. Any audio file on shared storage shows up
 *  (mp3, m4a, opus, ogg, flac, wav), tolerant of missing or partial tags. */
object MusicScanner {

    /** Major audio extensions we list. MediaStore already filters to music, but we
     *  double-check the path so stray ringtones with odd names stay out. */
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "mid", "midi", "xmf", "mxmf", "rtttl", "rtx", "ota", "imy")

    /** System sound locations: never treated as music, even when unflagged. */
    private val SYS_DIRS = listOf(
        "/Ringtones/", "/Alarms/", "/Notifications/",
        "/Android/", "/MIUI/", "/DCIM/", "/Sounds/"
    )

    private fun isSystemPath(path: String): Boolean =
        SYS_DIRS.any { path.contains(it, ignoreCase = true) }

    fun scanAll(context: Context): List<Song> {
        val out = mutableListOf<Song>()
        val seen = mutableSetOf<Long>()
        var rowsSeen = 0
        var skippedExt = 0
        var skippedSys = 0
        var nullPath = 0
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.IS_MUSIC
        )
        fun readAll() {
            runCatching {
                context.contentResolver.query(
                    collection, proj, null, null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
                )?.use { c ->
                    val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val t = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val ar = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val al = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val d = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val s = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val m = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val p = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val mod = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val mus = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)
                    while (c.moveToNext()) {
                        rowsSeen++
                        val mediaId = c.getLong(id)
                        if (!seen.add(mediaId)) continue
                        val path: String? = runCatching { c.getString(p) }.getOrNull()
                        if (path == null) nullPath++
                        val ext = path?.substringAfterLast('.', "")?.lowercase()
                        if (ext != null && ext.isNotEmpty() && ext !in AUDIO_EXTS) {
                            skippedExt++
                            continue
                        }
                        val flagged = runCatching { c.getInt(mus) != 0 }.getOrDefault(true)
                        // Fresh downloads often arrive unflagged; trust the
                        // audio extension outside system sound folders.
                        if (!flagged && (path == null || isSystemPath(path))) {
                            skippedSys++
                            continue
                        }
                        val rawTitle = c.getString(t) ?: path?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Unknown title"
                        out.add(
                            Song(
                                mediaId = mediaId,
                                contentUri = ContentUris.withAppendedId(collection, mediaId).toString(),
                                filePath = path,
                                title = rawTitle.ifBlank { "Unknown title" },
                                artist = (c.getString(ar) ?: "Unknown artist").ifBlank { "Unknown artist" },
                                album = (c.getString(al) ?: "Unknown album").ifBlank { "Unknown album" },
                                durationMs = runCatching { c.getLong(d) }.getOrDefault(0L),
                                sizeBytes = runCatching { c.getLong(s) }.getOrDefault(0L),
                                mimeType = runCatching { c.getString(m) }.getOrNull(),
                                modified = runCatching { c.getLong(mod) }.getOrDefault(0L)
                            )
                        )
                    }
                }
            }
        }
        // One pass over everything: IS_MUSIC=1 rows are music, and so are
        // unflagged audio files outside system sound folders (fresh
        // downloads often arrive before the system flags them).
        readAll()
        android.util.Log.d(
            "BlissScan",
            "rows=$rowsSeen kept=${out.size} nullPath=$nullPath badExt=$skippedExt sysSkip=$skippedSys"
        )
        return out.sortedBy { it.title.lowercase() }
    }
}
