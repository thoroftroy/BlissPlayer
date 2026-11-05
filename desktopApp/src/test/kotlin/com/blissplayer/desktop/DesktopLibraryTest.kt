package com.blissplayer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopLibraryTest {
    private fun tone(path: File) {
        path.parentFile.mkdirs()
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "lavfi",
            "-i", "sine=frequency=440:duration=2", "-c:a", "aac", path.absolutePath)
            .start()
        check(p.waitFor() == 0) { "ffmpeg fixture failed" }
    }

    @Test fun scanFindsAudioSkipsSystemSoundsAndOrganizes() {
        val root = Files.createTempDirectory("blisslib").toFile()
        tone(File(root, "ArtistA/AlbumA/song1.m4a"))
        tone(File(root, "ArtistA/AlbumA/song2.m4a"))
        tone(File(root, "Ringtones/ring.m4a"))
        val lib = DesktopLibrary(root)
        val entries = lib.scan(emptySet()) { _, _ -> }
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.artist.isNotBlank() })
        val dests = entries.associate {
            it.key to lib.destinationFor(root, "NewArtist", "NewAlbum", File(it.path).name)
        }
        val errors = lib.organize(entries, dests)
        assertTrue(errors.isEmpty())
        assertTrue(File(root, "BlissPlayer/NewArtist/NewAlbum/song1.m4a").exists())
        assertEquals(2, lib.scan(emptySet()) { _, _ -> }.size)
    }
}
