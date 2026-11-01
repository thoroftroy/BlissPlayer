package com.blissplayer.music.data

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import com.blissplayer.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
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

    /**
     * jaudiotagger picks its reader by file extension, so the cache copy
     * must wear the song's real extension. A ".tmp" copy reads as nothing
     * and every edit silently fails.
     */
    internal fun extFor(song: Song): String {        val fromPath = song.filePath?.substringAfterLast('.', "")?.lowercase()
        if (!fromPath.isNullOrBlank() && fromPath.length <= 5 && fromPath.all { it.isLetterOrDigit() }) {
            return fromPath
        }
        return when (song.mimeType?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/aac" -> "m4a"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/vnd.wave" -> "wav"
            else -> ""
        }
    }

    /**
     * Read the embedded tag for prefilling the editor. Null when unreadable.
     * Song name, artist, album, genre, in that order.
     */
    suspend fun readTags(context: Context, song: Song): Array<String?> =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(song.contentUri)
            val ext = extFor(song)
            if (ext.isBlank()) return@withContext arrayOfNulls<String>(4)
            val tmp = File.createTempFile("tagread_${song.mediaId}_", ".$ext", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    tmp.outputStream().use { ins.copyTo(it) }
                } ?: return@withContext arrayOfNulls<String>(4)
                val audio = runCatching { AudioFileIO.read(tmp) }.getOrNull()
                    ?: return@withContext arrayOfNulls<String>(4)
                val tag = audio.tag ?: return@withContext arrayOfNulls<String>(4)
                arrayOf(
                    runCatching { tag.getFirst(FieldKey.TITLE) }.getOrNull()?.takeIf { it.isNotBlank() },
                    runCatching { tag.getFirst(FieldKey.ARTIST) }.getOrNull()?.takeIf { it.isNotBlank() },
                    runCatching { tag.getFirst(FieldKey.ALBUM) }.getOrNull()?.takeIf { it.isNotBlank() },
                    runCatching { tag.getFirst(FieldKey.GENRE) }.getOrNull()?.takeIf { it.isNotBlank() }
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                arrayOfNulls(4)
            } finally {
                tmp.delete()
            }
        }

    suspend fun writeTags(context: Context, song: Song, edit: Edit): WriteResult =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(song.contentUri)
            val ext = extFor(song)
            if (ext.isBlank()) {
                return@withContext WriteResult.Failed("Unsupported audio format, cannot edit tags.")
            }
            var original: File? = null
            var tmp: File? = null
            var keepRecoveryCopy = false
            try {
                // Ask for access before doing expensive work, without truncating the song.
                context.contentResolver.openFileDescriptor(uri, "rw")?.close()
                    ?: return@withContext WriteResult.Failed("Could not open song for writing.")
                val backup = File.createTempFile("original_${song.mediaId}_", ".bak", context.cacheDir)
                original = backup
                val edited = File.createTempFile("tag_${song.mediaId}_", ".$ext", context.cacheDir)
                tmp = edited
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    backup.outputStream().use { ins.copyTo(it) }
                } ?: return@withContext WriteResult.Failed("Could not open song for editing.")
                backup.copyTo(edited, overwrite = true)
                try {
                    val audio = AudioFileIO.read(edited)
                    val tag = audio.tagOrCreateAndSetDefault
                    tag.setField(FieldKey.TITLE, edit.title)
                    tag.setField(FieldKey.ARTIST, edit.artist)
                    tag.setField(FieldKey.ALBUM, edit.album)
                    edit.genre?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.GENRE, it) }
                    edit.year?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.YEAR, it) }
                    edit.track?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.TRACK, it) }
                    audio.commit()
                    val verified = AudioFileIO.read(edited).tag
                    check(verified != null && verified.getFirst(FieldKey.TITLE) == edit.title &&
                        verified.getFirst(FieldKey.ARTIST) == edit.artist &&
                        verified.getFirst(FieldKey.ALBUM) == edit.album) { "Saved tags did not match the requested changes." }
                } catch (e: Exception) {
                    return@withContext WriteResult.Failed("Tag format not writable: ${e.message}")
                }
                // 2) Write bytes back into MediaStore.
                var writeStarted = false
                try {
                    context.contentResolver.openOutputStream(uri, "rwt")?.use { outs ->
                        writeStarted = true
                        edited.inputStream().use { it.copyTo(outs) }
                    } ?: return@withContext WriteResult.Failed("System refused the write.")
                } catch (e: Exception) {
                    if (writeStarted) {
                        // Keep the untouched original until a complete write succeeds.
                        val restored = runCatching {
                            checkNotNull(context.contentResolver.openOutputStream(uri, "rwt")).use { out ->
                                backup.inputStream().use { it.copyTo(out) }
                            }
                        }.isSuccess
                        keepRecoveryCopy = !restored
                        if (!restored) return@withContext WriteResult.Failed(
                            "Write failed. Original retained privately at ${backup.absolutePath}: ${e.message}")
                    }
                    throw e
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
                // A tag write alone does not refresh Android's cached library metadata.
                song.filePath?.let { path ->
                    withTimeoutOrNull(5000) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                    }
                }
                WriteResult.Ok
            } catch (e: SecurityException) {
                val sender = runCatching {
                    if (Build.VERSION.SDK_INT >= 30) MediaStore.createWriteRequest(
                        context.contentResolver, listOf(uri)).intentSender
                    else (e as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
                }.getOrNull()
                if (sender != null) WriteResult.NeedsPermission(sender)
                else WriteResult.Failed("Android denied write access. Allow access to this song and retry: ${e.message}")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                WriteResult.Failed(e.message ?: "Unknown error")
            } finally {
                tmp?.delete()
                if (!keepRecoveryCopy) original?.delete()
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
