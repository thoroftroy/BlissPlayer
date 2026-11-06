package com.blissplayer.music

import com.blissplayer.music.data.SongAnalyzer
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Dumps cleanLocal(title, artist, base) for every YMusic file to
 * /tmp/opencode/cleandump.tsv: the reference corpus for the Rust core
 * parity harness. Opt-in via BLISS_CLEAN_DUMP=1.
 */
class CleanCorpusTest {
    @Test
    fun dumpCleanCorpus() {
        Assume.assumeTrue("set BLISS_CLEAN_DUMP=1", System.getenv("BLISS_CLEAN_DUMP") == "1")
        val dir = File(System.getProperty("user.home"), "Applications/Wonderdesk Applications/YMusic")
            .takeIf { it.isDirectory } ?: File("/home/microwavedthebaby/Downloads/YMusic")
        val files = dir.listFiles { f -> f.isFile }!!.sortedBy { it.name }
        val out = StringBuilder()
        for (f in files) {
            val base = f.nameWithoutExtension
            val c = SongAnalyzer.cleanLocal(base, "Unknown artist", base)
            fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", " ").replace("\n", " ")
            out.append("${esc(base)}\t${esc(c.title)}\t${esc(c.artist)}\t${c.safe}\t${c.versioned}\n")
        }
        File("/tmp/opencode/cleandump.tsv").writeText(out.toString())
        println("CLEANDUMP ${files.size} rows")
        org.junit.Assert.assertTrue(files.size > 1000)
    }
}
