package com.blissplayer.music

import com.blissplayer.music.data.SongAnalyzer
import org.jaudiotagger.audio.AudioFileIO
import org.junit.Test
import java.io.File

class YMusicDiagnosticTest {
    @Test
    fun diagnoseYMusic() {
        val home = System.getProperty("user.home")
        val dir = listOf(
            File(home, "Applications/Wonderdesk Applications/YMusic"),
            File(home, "Applications/Wonderdesk Applications/Testing Files/YMusic"),
            File("/home/microwavedthebaby/Downloads/YMusic")
        ).firstOrNull { it.isDirectory }
        if (dir == null) {
            println("YMUSIC_DIAG no YMusic folder on this box, skipping")
            return
        }
        val files = dir.listFiles { f -> f.isFile }!!.sortedBy { it.name }
        var total = 0
        var withArtist = 0
        var withProposal = 0
        var high = 0
        val noProposal = mutableListOf<String>()
        for (f in files) {
            total++
            val base = f.nameWithoutExtension
            var tagTitle: String? = null
            var tagArtist: String? = null
            try {
                val audio = AudioFileIO.read(f)
                val tag = audio.tag
                if (tag != null) {
                    tagTitle = runCatching { tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE) }.getOrNull()?.takeIf { it.isNotBlank() }
                    tagArtist = runCatching { tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST) }.getOrNull()?.takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) {}
            val title = tagTitle ?: base
            val artist = tagArtist ?: "Unknown artist"
            val c = SongAnalyzer.cleanLocal(title, artist, base)
            if (!SongAnalyzer.isUnknownArtist(c.artist)) withArtist++
            if (c.reasons.isNotEmpty()) {
                withProposal++
                if (c.safe) high++
            } else if (noProposal.size < 30) {
                noProposal.add("FILE=${f.name} | titleTag=$title | artistTag=$artist => cleanedTitle=${c.title} | cleanedArtist=${c.artist}")
            }
        }
        println("YMUSIC_DIAG total=$total withArtist=$withArtist withProposal=$withProposal high=$high")
        println("YMUSIC_DIAG artistCoverage=${withArtist * 100.0 / total} proposalCoverage=${withProposal * 100.0 / total}")
        println("YMUSIC_DIAG --- NO PROPOSAL SAMPLE (${noProposal.size}) ---")
        noProposal.forEach { println("YMUSIC_DIAG NOPROP $it") }
    }
}
