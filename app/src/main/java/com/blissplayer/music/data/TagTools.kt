package com.blissplayer.music.data

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.blissplayer.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Edit song details + delete songs from inside the app.
 * Tag writes go to the actual file (round-tripped through app cache so it
 * works under scoped storage), then MediaStore is updated so lists refresh.
 */
object TagTools {

    data class Edit(
        val title: String,
        val artist: String,
        val album: String,
        val genre: String?,
        val year: String?,
        val track: String?
    )

    sealed interface WriteResult {
        data object Ok : WriteResult
        /** System needs one tap to grant write access, Activity must fire [sender] then retry. */
        data class NeedsPermission(val sender: IntentSender) : WriteResult
        data class Failed(val msg: String) : WriteResult
    }

    suspend fun writeTags(context: Context, song: Song, edit: Edit): WriteResult =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(song.contentUri)
            try {
                // 1) Copy audio into cache so jaudiotagger gets a real File.
                val tmp = File(context.cacheDir, "tag_${song.mediaId}.tmp")
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    tmp.outputStream().use { ins.copyTo(it) }
                } ?: return@withContext WriteResult.Failed("Could not open song for editing.")
                try {
                    val audio = AudioFileIO.read(tmp)
                    val tag = audio.tagOrCreateAndSetDefault
                    tag.setField(FieldKey.TITLE, edit.title)
                    tag.setField(FieldKey.ARTIST, edit.artist)
                    tag.setField(FieldKey.ALBUM, edit.album)
                    edit.genre?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.GENRE, it) }
                    edit.year?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.YEAR, it) }
                    edit.track?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.TRACK, it) }
                    audio.commit()
                } catch (e: Exception) {
                    tmp.delete()
                    return@withContext WriteResult.Failed("Tag format not writable: ${e.message}")
                }
                // 2) Write bytes back into MediaStore.
                try {
                    context.contentResolver.openOutputStream(uri, "w")?.use { outs ->
                        tmp.inputStream().use { it.copyTo(outs) }
                    } ?: return@withContext WriteResult.Failed("System refused the write.")
                } catch (e: RecoverableSecurityException) {
                    tmp.delete()
                    val sender = if (Build.VERSION.SDK_INT >= 30) {
                        MediaStore.createWriteRequest(
                            context.contentResolver, listOf(uri)
                        ).intentSender
                    } else return@withContext WriteResult.Failed("Permission denied.")
                    return@withContext WriteResult.NeedsPermission(sender)
                } finally {
                    tmp.delete()
                }
                // 3) Refresh MediaStore columns so lists update instantly.
                runCatching {
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply {
                            put(MediaStore.Audio.Media.TITLE, edit.title)
                            put(MediaStore.Audio.Media.ARTIST, edit.artist)
                            put(MediaStore.Audio.Media.ALBUM, edit.album)
                        },
                        null, null
                    )
                }
                WriteResult.Ok
            } catch (e: Exception) {
                WriteResult.Failed(e.message ?: "Unknown error")
            }
        }

    suspend fun delete(context: Context, song: Song): WriteResult =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(song.contentUri)
            try {
                val rows = context.contentResolver.delete(uri, null, null)
                if (rows > 0) WriteResult.Ok else WriteResult.Failed("Nothing was deleted.")
            } catch (e: RecoverableSecurityException) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val sender = MediaStore.createDeleteRequest(
                        context.contentResolver, listOf(uri)
                    ).intentSender
                    WriteResult.NeedsPermission(sender)
                } else WriteResult.Failed("Permission denied.")
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val sender = runCatching {
                        MediaStore.createDeleteRequest(
                            context.contentResolver, listOf(uri)
                        ).intentSender
                    }.getOrNull() ?: return@withContext WriteResult.Failed("Permission denied.")
                    WriteResult.NeedsPermission(sender)
                } else WriteResult.Failed("Permission denied.")
            } catch (e: Exception) {
                WriteResult.Failed(e.message ?: "Unknown error")
            }
        }
}
