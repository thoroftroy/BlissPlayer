package com.blissplayer.music.model

/** One audio file on the device. mediaId is the MediaStore audio id (stable per scan). */
data class Song(
    val mediaId: Long,
    val contentUri: String,
    val filePath: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    /** MediaStore DATE_MODIFIED (seconds). Used to skip re-measuring in Equalize. */
    val modified: Long = 0L
) {
    val sortTitle: String get() = SongNames.sortKey(title)

    fun isInFolders(folders: Set<String>): Boolean = folders.isEmpty() || filePath?.let { path ->
        folders.any { folder -> path == folder.trimEnd('/') || path.startsWith(folder.trimEnd('/') + "/") }
    } == true
}
