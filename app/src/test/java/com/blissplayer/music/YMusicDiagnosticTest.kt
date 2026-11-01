package com.blissplayer.music

import com.blissplayer.music.data.SongAnalyzer
import org.jaudiotagger.audio.AudioFileIO
import org.junit.Test
import java.io.File

class YMusicDiagnosticTest {
    @Test
    fun diagnoseYMusic() {
        val dir = File("/home/microwavedthebaby/Downloads/YMusic")
        val files = dir.listFiles { f -> f.isFile }!!.sortedBy { it.name }
        var total = 0
        var withArtist = 0
        var withProposal = 0
        var high = 0
        var unknownArtist = 0
        val noProposal = mutableListOf<String>()
        val unknownAfter = mutableListOf<String>()
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
            if (!SongAnalyzer.isUnknownArtist(c.artist)) withArtist++ else {
                unknownArtist++
                if (unknownAfter.size < 40) unknownAfter.add("FILE=${f.name} | titleTag=$title | artistTag=$artist => cleanedTitle=${c.title} | cleanedArtist=${c.artist} | reasons=${c.reasons}")
            }
            if (c.reasons.isNotEmpty()) {
                withProposal++
                if (c.safe) high++
            } else {
                if (noProposal.size < 60) noProposal.add("FILE=${f.name} | titleTag=$title | artistTag=$artist => cleanedTitle=${c.title} | cleanedArtist=${c.artist}")
            }
        }
        println("YMUSIC_DIAG total=$total withArtist=$withArtist unknownArtist=$unknownArtist withProposal=$withProposal high=$high")
        println("YMUSIC_DIAG artistCoverage=${withArtist * 100.0 / total} proposalCoverage=${withProposal * 100.0 / total}")
        println("YMUSIC_DIAG --- NO PROPOSAL SAMPLE (${noProposal.size}) ---")
        noProposal.forEach { println("YMUSIC_DIAG NOPROP $it") }
        println("YMUSIC_DIAG --- UNKNOWN ARTIST SAMPLE (${unknownAfter.size}) ---")
        unknownAfter.forEach { println("YMUSIC_DIAG UNK $it") }
    }
}
