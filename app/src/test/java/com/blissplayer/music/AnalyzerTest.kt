package com.blissplayer.music

import com.blissplayer.music.data.SongAnalyzer
import com.blissplayer.music.data.WikiLookup
import com.blissplayer.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Analyzer + shuffle math tests on real YouTube-download filenames
 * (yt-dlp, no tags). Run: ./gradlew :app:testDebugUnitTest
 */
class AnalyzerTest {

    private fun song(title: String, artist: String = "Unknown artist"): Song =
        Song(
            mediaId = title.hashCode().toLong(), contentUri = "content://x/$title",
            filePath = "/storage/emulated/0/Music/$title.mp3",
            title = title, artist = artist, album = "Unknown album",
            durationMs = 200000, sizeBytes = 1000, mimeType = "audio/mpeg", modified = 1
        )

    @Test
    fun officialVideoAnd4kStripped() {
        val c = SongAnalyzer.cleanLocal(
            "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)",
            "Unknown artist", ""
        )
        assertEquals("Rick Astley", c.artist)
        assertEquals("Never Gonna Give You Up", c.title)
    }

    @Test
    fun naPrefixAndVideoIdAndMVStripped() {
        val c = SongAnalyzer.cleanLocal(
            "NA - PSY - GANGNAM STYLE(강남스타일) M⧸V [9bZkp7q19f0]",
            "Unknown artist", ""
        )
        assertEquals("PSY", c.artist)
        assertTrue("title was: ${c.title}", "GANGNAM STYLE" in c.title)
        assertFalse("id leaked: ${c.title}", "[9bZkp7q19f0]" in c.title)
        assertFalse("mv leaked: ${c.title}", "M/V" in c.title || "⧸" in c.title)
    }

    @Test
    fun lyricsAndIdStripped() {
        val c = SongAnalyzer.cleanLocal(
            "Adele - Hello (Lyrics) [testid99]", "Unknown artist", ""
        )
        assertEquals("Adele", c.artist)
        assertEquals("Hello", c.title)
    }

    @Test
    fun featuredArtistKeptInTitle() {
        val c = SongAnalyzer.cleanLocal(
            "Luis Fonsi - Despacito ft. Daddy Yankee", "Unknown artist", ""
        )
        assertEquals("Luis Fonsi", c.artist)
        assertEquals("Despacito ft. Daddy Yankee", c.title)
    }

    @Test
    fun meaningfulBracketsKept() {
        val (t, stripped) = SongAnalyzer.stripVideoId("Song Name [Remix]")
        assertEquals("Song Name [Remix]", t)
        assertFalse(stripped)
        val (t2, stripped2) = SongAnalyzer.stripVideoId("Song Name [dQw4w9WgXcQ]")
        assertEquals("Song Name", t2)
        assertTrue(stripped2)
    }

    @Test
    fun fullProposeOnUntaggedDownload() {
        val s = song("Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)")
        val p = SongAnalyzer.propose(s, "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)", "Uncategorized")
        assertNotNull(p)
        assertEquals("Never Gonna Give You Up", p!!.newTitle)
        assertEquals("Rick Astley", p.newArtist)
        assertEquals("High", p.confidence)
    }

    @Test
    fun canonicalTitleStripsSongDisambiguation() {
        assertEquals("Hello", WikiLookup.canonicalTitle("Hello (Adele song)"))
        assertEquals("Never Gonna Give You Up", WikiLookup.canonicalTitle("Never Gonna Give You Up"))
        // Real parentheticals that are part of the title stay.
        assertEquals("Hello (Taylor's Version)", WikiLookup.canonicalTitle("Hello (Taylor's Version)"))
    }

    @Test
    fun snippetArtistParsing() {
        assertEquals(
            "Rick Astley",
            WikiLookup.snippetArtist("\"Never Gonna Give You Up\" is a song by English singer Rick Astley, released on 27 July 1987.")
        )
        assertEquals(
            "Adele",
            WikiLookup.snippetArtist("\"Hello\" is a song recorded by British singer-songwriter Adele, released on 23 October 2015.")
        )
        assertEquals(
            "Luis Fonsi",
            WikiLookup.snippetArtist("\"Despacito\" is a Spanish-language song recorded by Puerto Rican singer Luis Fonsi.")
        )
        assertNull(WikiLookup.snippetArtist("No song mention here at all."))
    }

    @Test
    fun genreFromCategories() {
        assertEquals("K-Pop", SongAnalyzer.genreFromCategories(listOf("K-pop songs", "2012 singles")))
        assertEquals("Latin", SongAnalyzer.genreFromCategories(listOf("Reggaeton songs", "2017 singles")))
        assertNull(SongAnalyzer.genreFromCategories(listOf("Rick Astley songs", "1987 singles")))
    }

    @Test
    fun wikiFoldClearMatch() {
        val hit = WikiLookup.Hit(
            5472903, "Never Gonna Give You Up",
            "\"Never Gonna Give You Up\" is a song by English singer Rick Astley, released in 1987.",
            listOf("Dance-pop songs", "1987 singles")
        )
        val a = SongAnalyzer.applyWikiResult("Never Gonna Give You Up", "Rick Astley", "Uncategorized", hit)
        assertEquals("Never Gonna Give You Up", a.title)
        assertEquals("Rick Astley", a.artist)
        assertEquals("Dance-Pop", a.genre)
        assertTrue(a.high)
    }

    @Test
    fun wikiFoldUnknownArtistAdoptsPerformer() {
        val hit = WikiLookup.Hit(
            48324759, "Hello (Adele song)",
            "\"Hello\" is a song recorded by British singer-songwriter Adele.",
            listOf("Soul songs")
        )
        val a = SongAnalyzer.applyWikiResult("Hello", "Unknown artist", "Uncategorized", hit)
        assertEquals("Hello", a.title)
        assertEquals("Adele", a.artist)
        assertEquals("Soul", a.genre)
        assertTrue(a.high)
    }

    @Test
    fun wikiFoldMismatchStaysUnconfident() {
        val hit = WikiLookup.Hit(1, "Hello (Adele song)", "\"Hello\" is a song by Adele.", emptyList())
        val a = SongAnalyzer.applyWikiResult("Goodbye", "Some Band", "Uncategorized", hit)
        assertFalse(a.high)
        assertEquals("Goodbye", a.title)
    }

    @Test
    fun weightedOrderFavorsRareSongs() {
        // One heavily played song vs 49 unplayed: the played one should
        // land in the back half almost every seed.
        val ids = (0L until 50L).toList()
        val weights = ids.associate { if (it == 0L) it to 1.0 / 12.0 else it to 1.0 }
        var backHalf = 0
        repeat(20) { seed ->
            val order = ShuffleMath.weightedOrder(ids, weights, seed.toLong())
            assertEquals(50, order.toSet().size) // permutation, no duplicates
            if (order.indexOf(0L) >= 25) backHalf++
        }
        assertTrue("played song keeps surfacing first (backHalf=$backHalf)", backHalf >= 15)
    }

    @Test
    fun chanceFormatsWithDecimals() {
        assertEquals("45%", ShuffleMath.fmtChance(0.454))
        assertEquals("4.5%", ShuffleMath.fmtChance(0.045))
        assertEquals("0.08%", ShuffleMath.fmtChance(0.0008))
        assertEquals("0.00%", ShuffleMath.fmtChance(0.0))
        assertEquals("100%", ShuffleMath.fmtChance(1.0))
    }

    @Test
    fun bestPickSkipsDiscography() {
        val hits = listOf(
            Triple(1L, "Rick Astley discography", "No. 10 on the charts. Astley became a sensation with Never Gonna Give You Up."),
            Triple(2L, "Never Gonna Give You Up", "\"Never Gonna Give You Up\" is a song by Rick Astley.")
        )
        val best = WikiLookup.pickBest(hits, "Never Gonna Give You Up")
        assertEquals(2L, best!!.first)
    }

    @Test
    fun searchPairsTitleWithItsOwnPage() {
        val body = "{\"query\":{\"search\":[" +
            "{\"ns\":0,\"title\":\"Never Gonna Give You Up\",\"pageid\":5472903,\"snippet\":\"\\\"Never\\\" is a song by Rick Astley.\"}," +
            "{\"ns\":0,\"title\":\"Rick Astley discography\",\"pageid\":15994842,\"snippet\":\"charts.\"}" +
            "]}}"
        val hits = WikiLookup.parseSearch(body)
        assertEquals(2, hits.size)
        assertEquals(5472903L, hits[0].first)
        assertEquals("Never Gonna Give You Up", hits[0].second)
        assertEquals(15994842L, hits[1].first)
        assertEquals("Rick Astley discography", hits[1].second)
    }

    @Test
    fun tagCacheKeepsRealExtension() {
        val mp3 = song("x").copy(filePath = "/Music/a.mp3", mimeType = "audio/mpeg")
        assertEquals("mp3", com.blissplayer.music.data.TagTools.extFor(mp3))
        val flac = song("x").copy(filePath = "/Music/a.FLAC", mimeType = null)
        assertEquals("flac", com.blissplayer.music.data.TagTools.extFor(flac))
        val weird = song("x").copy(filePath = "/Music/a.!!!", mimeType = "audio/mpeg")
        assertEquals("mp3", com.blissplayer.music.data.TagTools.extFor(weird))
        val none = song("x").copy(filePath = null, mimeType = null)
        assertEquals("", com.blissplayer.music.data.TagTools.extFor(none))
    }

    @Test
    fun liveWikipediaLookup() {        // Hits the real API. Loose asserts: proves reachability + shapes.
        val hit = runBlocking { WikiLookup.lookup("Never Gonna Give You Up", "Rick Astley") }
        assertNotNull("no network or API changed shape", hit)
        assertEquals("Never Gonna Give You Up", WikiLookup.canonicalTitle(hit!!.title))
        assertEquals("Rick Astley", WikiLookup.snippetArtist(hit.snippet))
    }
}
