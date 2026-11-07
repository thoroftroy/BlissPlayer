package com.blissplayer.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Offline cleanup throughput on the user's real 1177-file YMusic corpus
 * (filenames only, no network). Gated on the folder existing so CI stays green.
 */
class CorpusThroughputTest {
    @Test fun cleanLocalKeepsUpWithThousandSongLibraries() {
        val dir = File(System.getProperty("user.home"), "Applications/Wonderdesk Applications/YMusic")
            .takeIf { it.isDirectory } ?: File(System.getProperty("user.home"), "Downloads/YMusic")
        assumeTrue("YMusic corpus not present", dir.isDirectory)
        val names = dir.listFiles()!!.filter { it.isFile }.map { it.nameWithoutExtension }
        assumeTrue("corpus empty", names.isNotEmpty())
        val t0 = System.nanoTime()
        var reasons = 0
        for (base in names) {
            val c = AnalyzerRules.cleanLocal(base, "", base)
            reasons += c.reasons.size
            check(c.title.isNotBlank())
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val perSec = (names.size * 1000L / ms.coerceAtLeast(1)).toInt()
        println("cleanLocal: ${names.size} real filenames in ${ms}ms ($perSec/s, $reasons reasons)")
    }
}
