package com.blissplayer.music

import com.blissplayer.music.data.SongAnalyzer
import com.blissplayer.music.data.WikiLookup
import kotlinx.coroutines.runBlocking
import org.junit.Test

class WikiLiveProbeTest {
    @Test
    fun probeFamous() {
        val cases = listOf(
            "Kryptonite" to "3 Doors Down",
            "Ain't No Sunshine" to "Bill Withers",
            "Gimme! Gimme! Gimme!" to "ABBA",
            "Hello" to "Adele",
            "Bohemian Rhapsody" to "Queen"
        )
        for ((t, a) in cases) {
            val hit = runBlocking { WikiLookup.lookup(t, a) }
            println("PROBE [$t / $a] hit=${hit?.title} page=${hit?.pageId}")
            if (hit != null) {
                val applied = SongAnalyzer.applyWikiResult(t, a, "Uncategorized", hit)
                println("PROBE   applied title=${applied.title} artist=${applied.artist} genre=${applied.genre} high=${applied.high} confirmed=${applied.titleConfirmed} reasons=${applied.reasons}")
                val info = runBlocking { WikiLookup.infoboxOf(hit.pageId) }
                println("PROBE   infobox album=${info?.album} genre=${info?.genre}")
                println("PROBE   snippetAlbum=${WikiLookup.snippetAlbum(hit.snippet)}")
                println("PROBE   snippet=${hit.snippet.take(200)}")
            }
        }
    }
}
