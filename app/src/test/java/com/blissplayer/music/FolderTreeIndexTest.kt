package com.blissplayer.music

import com.blissplayer.music.model.FolderTreeIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTreeIndexTest {
    @Test fun parentAndCountsMatchTheExistingFolderRules() {
        val paths = setOf("", "/Music", "/Music/Artist", "/Music/Artist/Album", "/Music/Other", "/Music2", "relative", "relative/sub")
        val songs = listOf("/Music/Artist/Album/a.m4a", "/Music/Artist/b.m4a", "/Music2/c.flac", "relative/sub/d.mp3", "/other.mp3")
        val index = FolderTreeIndex.build(paths, songs)
        paths.forEach { path ->
            val parent = paths.filter { it != path && path.startsWith("$it/") }.maxByOrNull { it.length }
            assertEquals("parent of $path", parent, index.children.entries.first { path in it.value }.key)
            assertEquals("song count for $path", songs.count { it == path || it.startsWith("$path/") }, index.songCounts[path] ?: 0)
        }
    }

    @Test fun generatedFolderTreesMatchStraightforwardReference() {
        val random = java.util.Random(11)
        repeat(30) {
            val paths = mutableSetOf<String>()
            repeat(80) {
                val depth = 1 + random.nextInt(4)
                paths.add((0 until depth).joinToString("", prefix = "") { "/${random.nextInt(6)}" })
            }
            val songs = List(250) { "${paths.elementAt(random.nextInt(paths.size))}/song$it.mp3" }
            val index = FolderTreeIndex.build(paths, songs)
            paths.forEach { path ->
                val parent = paths.filter { it != path && path.startsWith("$it/") }.maxByOrNull { it.length }
                assertEquals(parent, index.children.entries.first { path in it.value }.key)
                assertEquals(songs.count { it == path || it.startsWith("$path/") }, index.songCounts[path] ?: 0)
            }
        }
    }
}
