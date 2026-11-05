package com.blissplayer.desktop

import com.blissplayer.core.TagData
import com.blissplayer.core.TagGateway
import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

/** Tag I/O through the same jaudiotagger engine as Android, desktop file paths. */
object DesktopTags : TagGateway {
    override fun read(key: String): TagData? = runCatching {
        val f = AudioFileIO.read(File(key))
        val tag = f.tag ?: return TagData("", "", "", "")
        TagData(
            tag.getFirst(FieldKey.TITLE),
            tag.getFirst(FieldKey.ARTIST),
            tag.getFirst(FieldKey.ALBUM),
            tag.getFirst(FieldKey.GENRE)
        )
    }.getOrNull()

    override fun write(key: String, tags: TagData): String? = runCatching {
        val f = AudioFileIO.read(File(key))
        val tag = f.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, tags.title)
        tag.setField(FieldKey.ARTIST, tags.artist)
        tag.setField(FieldKey.ALBUM, tags.album)
        if (tags.genre.isNotBlank()) tag.setField(FieldKey.GENRE, tags.genre)
        f.commit()
        null
    }.getOrElse { it.message ?: "Tag write failed." }

    fun durationMs(key: String): Long = runCatching {
        (AudioFileIO.read(File(key)).audioHeader.trackLength * 1000L)
    }.getOrDefault(0L)

    /** Tags + duration in a single open (library scans call this per file). */
    fun info(key: String): Pair<TagData, Long>? = runCatching {
        val f = AudioFileIO.read(File(key))
        val tag = f.tag
        val data = if (tag == null) TagData("", "", "", "") else TagData(
            tag.getFirst(FieldKey.TITLE),
            tag.getFirst(FieldKey.ARTIST),
            tag.getFirst(FieldKey.ALBUM),
            tag.getFirst(FieldKey.GENRE)
        )
        data to (f.audioHeader.trackLength * 1000L)
    }.getOrNull()
}
