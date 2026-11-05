package com.blissplayer.music

import com.blissplayer.music.data.MusicBrainzLookup
import com.blissplayer.music.data.SongAnalyzer
import com.blissplayer.music.data.WikiLookup
import com.blissplayer.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test

/**
 * Analyzer + shuffle math tests on real YouTube-download filenames
 * (yt-dlp, no tags). Run: ./gradlew :app:testDebugUnitTest
 */
class AnalyzerTest {

    private fun song(title: String, artist: String = "Unknown artist"): Song =
        song(title, artist, title)

    private fun song(title: String, artist: String, base: String): Song =
        Song(
            mediaId = (title + artist).hashCode().toLong(), contentUri = "content://x/$title",
            filePath = "/storage/emulated/0/Music/$base.mp3",
            title = title, artist = artist, album = "Unknown album",
            durationMs = 200000, sizeBytes = 1000, mimeType = "audio/mpeg", modified = 1
        )

    /** Live API calls can be throttled: retry a few times before failing. */
    private fun liveLookup(title: String, artist: String): WikiLookup.Hit? {
        repeat(3) {
            try {
                runBlocking { WikiLookup.lookup(title, artist) }?.let { return it }
            } catch (e: Exception) {
            }
            Thread.sleep(3000)
        }
        return null
    }

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
    fun tagCacheKeepsRealExtension() {        val mp3 = song("x").copy(filePath = "/Music/a.mp3", mimeType = "audio/mpeg")
        assertEquals("mp3", com.blissplayer.music.data.TagTools.extFor(mp3))
        val flac = song("x").copy(filePath = "/Music/a.FLAC", mimeType = null)
        assertEquals("flac", com.blissplayer.music.data.TagTools.extFor(flac))
        val weird = song("x").copy(filePath = "/Music/a.!!!", mimeType = "audio/mpeg")
        assertEquals("mp3", com.blissplayer.music.data.TagTools.extFor(weird))
        val none = song("x").copy(filePath = null, mimeType = null)
        assertEquals("", com.blissplayer.music.data.TagTools.extFor(none))
    }

    @Ignore("needs network")
    @Test
    fun liveWikipediaLookup() {        // Hits the real API. Loose asserts: proves reachability + shapes.
        val hit = liveLookup("Never Gonna Give You Up", "Rick Astley")
        assertNotNull("no network or API changed shape", hit)
        assertEquals("Never Gonna Give You Up", WikiLookup.canonicalTitle(hit!!.title))
        assertEquals("Rick Astley", WikiLookup.snippetArtist(hit.snippet))
    }

    // ---- Obscure-download patterns (Message 32 batch) ----

    @Test
    fun turkishDiacriticsSurvive() {
        val c = SongAnalyzer.cleanLocal(
            "Barış Manço - Dağlar Dağlar (1970) [CKqxAiBZack]", "Unknown artist", ""
        )
        assertEquals("Barış Manço", c.artist)
        assertEquals("Dağlar Dağlar", c.title)
    }

    @Test
    fun japaneseCornerBracketsSplit() {
        val c = SongAnalyzer.cleanLocal(
            "山下達郎「SPARKLE」Music Video (2023) [pqobRu9aR3M]", "Unknown artist", ""
        )
        assertEquals("山下達郎", c.artist)
        assertEquals("SPARKLE", c.title)
    }

    @Test
    fun threeDashNameOffersBothReadings() {
        val v = SongAnalyzer.splitVariants(
            "Akhiyan Udeek Dian - Ustad Nusrat Fateh Ali Khan - OSA Official HD Video [YSMIDIt2Ku4]"
        )
        assertTrue(v.isNotEmpty())
        // One reading must put the performer second.
        assertTrue(v.any { (a, t) -> a.contains("Nusrat") && t.contains("Akhiyan") })
    }

    @Test
    fun honorificArtistStillMatches() {
        assertTrue(SongAnalyzer.artistMatches("Ustad Nusrat Fateh Ali Khan", "Nusrat Fateh Ali Khan"))
        assertTrue(SongAnalyzer.artistMatches("PSY", "Psy"))
        assertFalse(SongAnalyzer.artistMatches("Adele", "Rick Astley"))
    }

    @Test
    fun wikiFoldAdoptsHonorificPerformer() {
        val hit = WikiLookup.Hit(
            9, "Akhiyan Udeek Dian",
            "\"Akhiyan Udeek Dian\" is a song by Pakistani singer Nusrat Fateh Ali Khan.",
            emptyList()
        )
        val a = SongAnalyzer.applyWikiResult(
            "Akhiyan Udeek Dian", "Ustad Nusrat Fateh Ali Khan", "Uncategorized", hit
        )
        assertEquals("Akhiyan Udeek Dian", a.title)
        assertEquals("Nusrat Fateh Ali Khan", a.artist)
        assertTrue(a.high)
    }

    @Test
    fun compilationPrefixStripped() {
        val (a, hit) = SongAnalyzer.stripLeadingJunk("80s Soviet Synthpop.  NRG (New Russian Group)")
        assertEquals("NRG (New Russian Group)", a)
        assertTrue(hit)
        val (b, hit2) = SongAnalyzer.stripLeadingJunk("80s Soviet Synthpop  Альянс")
        assertTrue(hit2)
        assertFalse("decade leaked: $b", Regex("^\\d{2,4}s?\\b").containsMatchIn(b))
    }

    @Test
    fun chiptuneParentheticalStripped() {
        val c = SongAnalyzer.cleanLocal(
            "iamjackg - Fair and Square (LSDJ Chiptune) [L1WRoUwGthQ]", "Unknown artist", ""
        )
        assertEquals("iamjackg", c.artist)
        assertEquals("Fair and Square", c.title)
    }

    @Test
    fun yearAndCountryTailsTrimmed() {
        val c = SongAnalyzer.cleanLocal(
            "Альянс - На заре (At dawn) USSR, 1987 [H6wl-EyhXl0]", "Unknown artist", ""
        )
        assertFalse("year leaked: ${c.title}", Regex("(19|20)\\d{2}").containsMatchIn(c.title))
        assertFalse("country leaked: ${c.title}", "USSR" in c.title)
        assertTrue("core lost: ${c.title}", "На заре" in c.title)
    }

    @Test
    fun wallOfTextIsNeverConfident() {
        val c = SongAnalyzer.cleanLocal(
            "70s-80s Japanese CITY POP Chill Mix [Playlist, DJ Mix] [IYb_myRNHHk]",
            "Unknown artist", ""
        )
        // Either no proposal fuel or an unsafe one: never a confident fix.
        assertFalse(c.safe && c.title.length > 80)
    }

    @Test
    fun noArtistNoCrashOnBareNames() {
        val c1 = SongAnalyzer.cleanLocal("Sea Shanty Collection [Oi7ZlNjATLQ]", "Unknown artist", "")
        assertEquals("Sea Shanty Collection", c1.title)
        val c2 = SongAnalyzer.cleanLocal(
            "Nusrat Fateh Ali Khan Live In Concert London 1993 [GhsvTxmbbnk]", "Unknown artist", ""
        )
        assertTrue(c2.title.isNotBlank())
        assertTrue(SongAnalyzer.splitVariants("No dashes here").isEmpty())
    }

    @Ignore("needs network")
    @Test
    fun liveObscureLookupGangnam() {
        val hit = liveLookup("GANGNAM STYLE", "PSY")
        assertNotNull("network down?", hit)
        val a = SongAnalyzer.applyWikiResult("GANGNAM STYLE", "PSY", "Uncategorized", hit!!)
        assertTrue("should be confident or review, got: ${a.reasons}", a.high || a.reasons.isNotEmpty())
    }

    // ---- Side-channel + m4a patterns (Message 33 batch) ----

    @Test
    fun vevoPremiereStripped() {
        val c = SongAnalyzer.cleanLocal(
            "Lady Gaga & Beyoncé - Telephone (VEVO Premiere) [r8GXHS4s9K4]", "Unknown artist", ""
        )
        assertEquals("Lady Gaga & Beyoncé", c.artist)
        assertEquals("Telephone", c.title)
        assertFalse(c.versioned)
    }

    @Test
    fun nightcoreMixIsReviewOnly() {
        val c = SongAnalyzer.cleanLocal(
            "Old School Classic Nightcore Mix ｜ Complete 2011 to 2021 Hottest Songs [xfjnN3sXYhk]",
            "Unknown artist", ""
        )
        assertFalse("compilation must not be confident", c.safe)
        assertTrue(c.versioned)
    }

    @Test
    fun bassBoostedKeepsVersionWords() {
        val c = SongAnalyzer.cleanLocal(
            "The Weeknd - The Hills (HXV Blurred Remix) (Bass Boosted) [SRtAjiCeit4]",
            "Unknown artist", ""
        )
        assertEquals("The Weeknd", c.artist)
        assertTrue("version kept: ${c.title}", "Bass Boosted" in c.title)
        assertTrue(c.versioned)
        assertFalse(c.safe)
    }

    @Test
    fun slowedTutorialOffersReversedReading() {
        val base = "You and I (Harleys in Hawaii) - Katy Perry - Slowed + Reverbed - Attractive Playlist"
        val v = SongAnalyzer.splitVariants(base)
        assertTrue(v.any { (a, t) -> a == "Katy Perry" && "Harleys in Hawaii" in t })
        val c = SongAnalyzer.cleanLocal("$base [ymjICcIMEFk]", "Unknown artist", "")
        assertTrue(c.versioned)
        assertFalse(c.safe)
    }

    @Test
    fun emojiStrippedScriptsKept() {
        assertEquals("Mix Best Songs", SongAnalyzer.stripEmoji("Mix 🎧 Best ⚡ Songs"))
        assertEquals("山下達郎「SPARKLE」", SongAnalyzer.stripEmoji("山下達郎「SPARKLE」"))
        assertEquals("Barış Manço", SongAnalyzer.stripEmoji("Barış Manço"))
        assertEquals("Альянс", SongAnalyzer.stripEmoji("Альянс"))
    }

    @Test
    fun spedUpCompilationIsReviewOnly() {
        val c = SongAnalyzer.cleanLocal(
            "Speed up tiktok songs - 2022 , 2023 x - Ari.luvvvs [AwLy-cMrtzo]", "Unknown artist", ""
        )
        assertTrue(c.versioned)
        assertFalse(c.safe)
    }

    @Test
    fun eightDeeMixIsReviewOnly() {
        val c = SongAnalyzer.cleanLocal(
            "8D Music Mix Best 8D Audio Songs HIT SONGS MIX [ruGUHgzK13c]", "Unknown artist", ""
        )
        assertFalse("8D mix must not be confident", c.safe)
    }

    @Test
    fun fullwidthSeparatorSplits() {
        val c = SongAnalyzer.cleanLocal("Artist X ｜ Song Y [abc1234]", "Unknown artist", "")
        assertEquals("Artist X", c.artist)
        assertEquals("Song Y", c.title)
    }

    @Test
    fun m4aKeepsExtension() {
        val m4a = song("x").copy(filePath = "/Music/a song.m4a", mimeType = "audio/mp4")
        assertEquals("m4a", com.blissplayer.music.data.TagTools.extFor(m4a))
    }

    private fun ytSong(file: String): com.blissplayer.music.model.Song =
        song(file.substringAfterLast('/').substringBeforeLast('.'), "Unknown artist").copy(
            mediaId = file.hashCode().toLong(),
            filePath = "/storage/emulated/0/Music/$file"
        )

    @Test
    fun sideChannelBatchStaysSaneOffline() = runBlocking {
        val files = listOf(
            "Lady Gaga & Beyoncé - Telephone (VEVO Premiere) [r8GXHS4s9K4].m4a",
            "The Weeknd - The Hills (HXV Blurred Remix) (Bass Boosted) [SRtAjiCeit4].m4a",
            "Old School Classic Nightcore Mix ｜ Complete 2011 to 2021 Hottest Songs [xfjnN3sXYhk].m4a",
            "Speed up tiktok songs - 2022 , 2023 x - Ari.luvvvs [AwLy-cMrtzo].m4a",
            "You and I (Harleys in Hawaii) - Katy Perry ｜ Slowed + Reverbed ｜ Attractive Playlist [ymjICcIMEFk].m4a",
            "8D Music Mix Best 8D Audio Songs HIT SONGS MIX [ruGUHgzK13c].m4a"
        )
        val songs = files.map { ytSong(it) }
        val out = SongAnalyzer.analyze(songs, { "Uncategorized" }, { "Unknown album" }, false) { _, _, _ -> }
        // Every messy file gets a proposal, none confident garbage.
        assertEquals(files.size, out.size)
        val gaga = out.first { it.song.filePath!!.contains("Telephone") }
        assertEquals("Telephone", gaga.newTitle)
        assertEquals("Lady Gaga & Beyoncé", gaga.newArtist)
        assertEquals("Unknown album", gaga.newAlbum) // never invent an album
        val hills = out.first { it.song.filePath!!.contains("The Hills") }
        assertEquals("Check", hills.confidence)
        assertTrue("version kept: ${hills.newTitle}", "Bass Boosted" in hills.newTitle)
        assertTrue(hills.reason.contains("modified version"))
        // Compilations and version edits are never confident.
        for (p in out) {
            if (p.newArtist.contains("Mix", true) || p.newTitle.contains("Mix", true) ||
                p.reason.contains("modified version") || p.reason.contains("compilation")
            ) {
                assertEquals("Check", p.confidence)
            }
        }
    }

    @Ignore("needs network")
    @Test
    fun liveVevoLookup() {
        val hit = liveLookup("Telephone", "Lady Gaga")
        assertNotNull("network down?", hit)
        val a = SongAnalyzer.applyWikiResult("Telephone", "Lady Gaga", "Uncategorized", hit!!)
        assertEquals("Telephone", a.title)
        assertTrue(a.high)
    }

    @Ignore("needs network")
    @Test
    fun liveAlbumLookup() {
        // "Hello" page -> album "25"; NGGU page -> its album.
        val hello = liveLookup("Hello", "Adele")
        assertNotNull("network down?", hello)
        assertEquals("25", WikiLookup.albumOf(hello!!.pageId))
        val nggu = liveLookup("Never Gonna Give You Up", "Rick Astley")
        assertNotNull("network down?", nggu)
        assertEquals("Whenever You Need Somebody", WikiLookup.albumOf(nggu!!.pageId))
        assertNull(WikiLookup.albumOf(-1))
    }

    @Test
    fun mbParseShapes() {
        val body = "{\"recordings\":[{\"id\":\"3f66\",\"score\":100,\"title\":\"Lonely\"," +
            "\"artist-credit\":[{\"name\":\"Akon\"}]," +
            "\"releases\":[{\"title\":\"Trouble (Deluxe Edition)\",\"status\":\"Official\"}]," +
            "\"tags\":[{\"count\":5,\"name\":\"hip hop\"},{\"count\":2,\"name\":\"rnb\"}]}]}"
        val hits = MusicBrainzLookup.parse(body)
        assertEquals(1, hits.size)
        assertEquals("Lonely", hits[0].track)
        assertEquals("Akon", hits[0].artist)
        assertEquals("Trouble (Deluxe Edition)", hits[0].album)
        assertEquals("Hip-Hop", hits[0].genre)
        assertTrue(MusicBrainzLookup.parse("{\"recordings\":[]}").isEmpty())
        assertEquals("Rock", MusicBrainzLookup.genreDisplay("alternative rock"))
        assertEquals("Rock", MusicBrainzLookup.genreDisplay("emo"))
        assertNull(MusicBrainzLookup.genreDisplay("!?!"))
        assertEquals("Rough Hands", MusicBrainzLookup.baseTitle("Rough Hands (Live)"))
        val folded = SongAnalyzer.mbFold("Lonely", "Akon", "Uncategorized", hits)
        assertNotNull(folded)
        assertTrue(folded!!.high)
        assertEquals("Hip-Hop", folded.genre)
        // Studio album wins over a live Official release.
        val live = "{\"recordings\":[{\"id\":\"y\",\"title\":\"Drink\"," +
            "\"artist-credit\":[{\"name\":\"Alestorm\"}]," +
            "\"releases\":[{\"title\":\"Live in Tilburg\",\"status\":\"Official\"}]," +
            "\"release-groups\":[{\"title\":\"Live in Tilburg\",\"primary-type\":\"Live\"}," +
            "{\"title\":\"Captain Morgan's Revenge\",\"primary-type\":\"Album\"}]," +
            "\"tags\":[]}]}"
        val lhits = MusicBrainzLookup.parse(live)
        assertEquals(1, lhits.size)
        assertEquals("Captain Morgan's Revenge", lhits[0].album)
    }

    @Test
    fun canonicalNamesMerge() {
        assertEquals("Eminem", SongAnalyzer.canonicalArtist("Eminem from 8 Mile (Movie Soundtrack)"))
        assertEquals("Beyonce", SongAnalyzer.canonicalArtist("Beyonce for her third studio album I Am..."))
        assertEquals("SomeBand", SongAnalyzer.canonicalArtist("SomeBand - Topic"))
        assertEquals("EMINEM", SongAnalyzer.canonicalArtist("EMINEM"))
        assertEquals("eminem", SongAnalyzer.artistKey("Eminem from the 8 Mile soundtrack"))
        assertEquals("eminem", SongAnalyzer.artistKey("EMINEM"))
        assertEquals("Back in Black", SongAnalyzer.canonicalAlbum("Back in Black (1991)"))
        assertEquals("Back In Black", SongAnalyzer.canonicalAlbum("Back In Black (Live)"))
        assertEquals("Back in Black", SongAnalyzer.canonicalAlbum("Back in Black Remastered"))
        assertEquals("Back in Black", SongAnalyzer.canonicalAlbum("Back in Black Deluxe Edition"))
        assertEquals("back in black", SongAnalyzer.albumKey("BACK IN BLACK (Live)"))
        assertEquals("Unknown album", SongAnalyzer.canonicalAlbum("Unknown album"))
        // Cleaning keeps the performer only.
        val c = SongAnalyzer.cleanLocal(
            "Beyonce for her third studio album - Halo (Video)", "Beyonce for her third studio album", "")
        assertEquals("Beyonce", c.artist)
    }

    @Test
    fun albumParseShapes() {
        assertEquals("25", WikiLookup.parseAlbum("| album = [[25 (Adele album)|25]]"))
        assertEquals("Whenever You Need Somebody", WikiLookup.parseAlbum("|album=[[Whenever You Need Somebody]]"))
        assertEquals("Abbey Road", WikiLookup.parseAlbum("| album = ''[[Abbey Road]]''"))
        assertNull(WikiLookup.parseAlbum("no infobox here"))
        assertNull(WikiLookup.parseAlbum("| album = "))
    }

    @Test
    fun genreParseShapes() {
        assertEquals("Heavy metal", WikiLookup.parseGenre("| genre = [[Heavy metal]]<br/>[[Hard rock]]"))
        assertEquals("Dance-pop", WikiLookup.parseGenre("|genre=''[[Dance-pop]]''"))
        assertEquals("Hip hop music", WikiLookup.parseGenre("| genre = Hip hop music"))
        assertNull(WikiLookup.parseGenre("no infobox here"))
        assertNull(WikiLookup.parseGenre("| genre = "))
        assertNull(WikiLookup.parseGenre("| genre = " + "x".repeat(50)))
    }

    @Test
    fun snippetAlbumShapes() {
        assertEquals(
            "Rattle and Hum",
            WikiLookup.snippetAlbum("\"I Still Haven't Found What I'm Looking For\" is a song by Irish rock band U2. It is the second track from their fifth studio album, Rattle and Hum.")
        )
        assertEquals(
            "25",
            WikiLookup.snippetAlbum("\"Hello\" is a song recorded by English singer-songwriter Adele for her third studio album, 25 (2015).")
        )
        assertNull(WikiLookup.snippetAlbum("\"Hello\" is a song recorded by Adele."))
        assertNull(WikiLookup.snippetAlbum("No song mention here at all."))
    }

    @Test
    fun placeholderAlbumsDetected() {
        assertTrue(SongAnalyzer.isPlaceholderAlbum("Music", "/storage/emulated/0/Music/song.mp3"))
        assertTrue(SongAnalyzer.isPlaceholderAlbum("Unknown album", "/x/y.mp3"))
        assertTrue(SongAnalyzer.isPlaceholderAlbum("", "/x/y.mp3"))
        assertTrue(SongAnalyzer.isPlaceholderAlbum("<unknown>", "/x/y.mp3"))
        assertFalse(SongAnalyzer.isPlaceholderAlbum("Golden Oldies Collection", "/m/Disc1/s.mp3"))
        assertFalse(SongAnalyzer.isPlaceholderAlbum("25", "/m/Adele/s.mp3"))
    }

    @Test
    fun artistsSplitForBrowsing() {
        assertEquals(listOf("Lady Gaga", "Beyoncé"), SongAnalyzer.splitArtists("Lady Gaga & Beyoncé"))
        assertEquals(listOf("Simon", "Garfunkel"), SongAnalyzer.splitArtists("Simon & Garfunkel"))
        assertEquals(listOf("A", "B"), SongAnalyzer.splitArtists("A feat. B"))
        assertEquals(listOf("A", "B"), SongAnalyzer.splitArtists("A ft B"))
        assertEquals(listOf("A", "B"), SongAnalyzer.splitArtists("A with B"))
        assertEquals(listOf("A", "B"), SongAnalyzer.splitArtists("A X B"))
        assertEquals(listOf("A", "B"), SongAnalyzer.splitArtists("A vs B"))
        assertEquals(listOf("Adele"), SongAnalyzer.splitArtists("Adele"))
        assertEquals(listOf("R&B"), SongAnalyzer.splitArtists("R&B"))
        assertEquals(listOf("Radiohead"), SongAnalyzer.splitArtists("Radiohead"))
        assertEquals(listOf("Peter, Paul", "Mary"), SongAnalyzer.splitArtists("Peter, Paul and Mary"))
        assertEquals(emptyList<String>(), SongAnalyzer.splitArtists("  "))
    }
}
