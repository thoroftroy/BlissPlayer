package com.blissplayer.music.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.blissplayer.music.model.Song

/** Scans device audio via MediaStore. YouTube downloads show up here as long as
 *  they are audio files (mp3/m4a/opus/ogg/flac/wav) anywhere on shared storage. */
object MusicScanner {

    /** Major audio extensions we list. MediaStore already filters to music, but we
     *  double-check the path so stray ringtones with odd names stay out. */
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "mid", "midi", "xmf", "mxmf", "rtttl", "rtx", "ota", "imy")

    fun scanAll(context: Context): List<Song> {
        val out = mutableListOf<Song>()
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
            MediaStore.Audio.Media.IS_MUSIC
        )
        // IS_MUSIC=1 keeps ringtones/notifications out; fallback below includes them if empty.
        context.contentResolver.query(
            collection, proj,
            MediaStore.Audio.Media.IS_MUSIC + "!=0", null,
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
            while (c.moveToNext()) {
                val path: String? = runCatching { c.getString(p) }.getOrNull()
                val ext = path?.substringAfterLast('.', "")?.lowercase()
                if (ext != null && ext.isNotEmpty() && ext !in AUDIO_EXTS) continue
                val mediaId = c.getLong(id)
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
                        mimeType = runCatching { c.getString(m) }.getOrNull()
                    )
                )
            }
        }
        return out
    }
}
