package com.blissplayer.music.model

/** A part of a song to jump over automatically. Times in milliseconds. */
data class Seg(
    val id: Long,
    val mediaId: Long,
    val startMs: Long,
    val endMs: Long,
    val label: String
)
