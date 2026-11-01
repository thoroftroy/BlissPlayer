package com.blissplayer.music

import com.blissplayer.music.data.SongAnalyzer
import com.blissplayer.music.model.Song
import com.blissplayer.music.model.SongNames
import com.blissplayer.music.playback.AudioOutput
import com.blissplayer.music.playback.OutputPresetMemory
import com.blissplayer.music.playback.OutputRouting
import com.blissplayer.music.playback.ToneMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors

class StabilityTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun legacyCrashIsShownOnceButRemainsAvailable() {
        val store = CrashLogStore(files.root)
        store.record("BlissPlayer 0.6.0 old crash")
        assertEquals("BlissPlayer 0.6.0 old crash", store.takePending())
        repeat(20) { assertNull(CrashLogStore(files.root).takePending()) }
        assertEquals("BlissPlayer 0.6.0 old crash", store.read())
        assertEquals(listOf("crash_report.txt"), files.root.list()!!.toList())
    }

    @Test fun newCrashEvenWithSameTraceGetsItsOwnAlert() {
        val store = CrashLogStore(files.root)
        repeat(2) {
            store.record("same trace")
            assertEquals("same trace", store.takePending())
            assertNull(store.takePending())
        }
        store.clear()
        assertNull(store.read())
    }

    @Test fun surroundingQuotesAreCleanedWithoutDestroyingApostrophes() {
        for (title in listOf("\"The Hearse Song\"", "“The Hearse Song”", "＂The Hearse Song＂", "'The Hearse Song'")) {
            val cleaned = SongAnalyzer.cleanLocal(title, "“O'Connor”", "")
            assertEquals("The Hearse Song", cleaned.title)
            assertEquals("O'Connor", cleaned.artist)
            assertTrue(cleaned.reasons.contains("removed surrounding quotes"))
        }
        assertEquals("Don't Stop", SongNames.unquote("Don't Stop"))
        assertEquals("'Til Tuesday", SongNames.unquote("'Til Tuesday"))
        val topic = SongAnalyzer.cleanLocal("\"The Hearse Song\"", "\"An Artist - Topic\"", "")
        assertEquals("An Artist", topic.artist)
        val split = SongAnalyzer.cleanLocal("'An Artist' - 'The Hearse Song'", "Unknown artist", "")
        assertEquals("An Artist", split.artist)
        assertEquals("The Hearse Song", split.title)
    }

    @Test fun quotesDoNotJumpToTopOfAlphabeticalSort() {
        assertEquals(listOf("Alpha", "Bravo", "\"The Hearse Song\"", "Zulu"),
            listOf("Zulu", "\"The Hearse Song\"", "Bravo", "Alpha").sortedBy(SongNames::sortKey))
    }

    private fun song(title: String) = Song(1, "content://test/1", "/Music/song.m4a", title,
        "An Artist", "An Album", 10000, 1000, "audio/mp4")

    @Test fun folderScopeDoesNotEditSimilarlyNamedOrUnresolvedFolders() {
        assertTrue(song("Song").isInFolders(setOf("/Music")))
        assertFalse(song("Song").copy(filePath = "/Music2/song.m4a").isInFolders(setOf("/Music")))
        assertFalse(song("Song").copy(filePath = null).isInFolders(setOf("/Music")))
        assertTrue(song("Song").copy(filePath = null).isInFolders(emptySet()))
    }

    @Test fun analyzerDoesNotProposeUnchangedVersionTags() = runBlocking {
        val song = song("Song (Bass Boosted)")
        assertNull(SongAnalyzer.propose(song, "song.m4a", "Rock"))
        assertTrue(SongAnalyzer.analyze(listOf(song), { "Rock" }, { "An Album" }, false) { _, _, _ -> }.isEmpty())
    }

    @Test fun progressReturnsToCallingUiThread() {
        Executors.newSingleThreadExecutor { task -> Thread(task, "regression-ui") }.asCoroutineDispatcher().use { ui ->
            runBlocking(ui) {
                val caller = Thread.currentThread()
                var calls = 0
                val result = SongAnalyzer.analyze((1..100).map { song("\"Song $it\"").copy(mediaId = it.toLong()) },
                    { "Rock" }, { "An Album" }, false) { _, _, _ ->
                    assertSame(caller, Thread.currentThread())
                    calls++
                }
                assertEquals(100, result.size)
                assertTrue(calls > 1)
            }
        }
    }

    @Test fun cancelledAnalyzerDoesNotPretendItFinished() = runBlocking {
        try {
            SongAnalyzer.analyze(listOf(song("\"Song\"")), { "Rock" }, { "An Album" }, false) { _, _, _ ->
                throw CancellationException("stop")
            }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun carDisconnectRejectsStaleRouteAndRestoresSpeaker() {
        val car = AudioOutput("car", "BT: Test Car", true)
        val presets = mutableMapOf(OutputRouting.SPEAKER to 1f, car.name to 2.5f)
        val state = OutputPresetMemory(presets::get, { key, boost -> presets[key] = boost }, 1f)
        state.select(OutputRouting.select(listOf(OutputRouting.speaker, car), listOf(car)).name, true)
        assertEquals(2.5f, state.boost, 0f)
        state.select(OutputRouting.select(listOf(OutputRouting.speaker), listOf(car)).name, true)
        assertEquals(OutputRouting.SPEAKER, state.device)
        assertEquals(1f, state.boost, 0f)
        assertEquals(2.5f, presets[car.name]!!, 0f)
    }

    @Test fun connectedBluetoothIsNotNecessarilyTheSelectedOutput() {
        val car = AudioOutput("car", "BT: Test Car", true)
        assertEquals(OutputRouting.speaker,
            OutputRouting.select(listOf(OutputRouting.speaker, car), listOf(OutputRouting.speaker)))
    }

    @Test fun newOutputAutomaticallyRemembersCurrentBoost() {
        val presets = mutableMapOf<String, Float>()
        val state = OutputPresetMemory(presets::get, { key, boost -> presets[key] = boost }, 1f)
        state.setBoost(1.7f)
        state.select("BT: New Car", true)
        assertEquals(1.7f, presets["BT: New Car"]!!, 0f)
        state.setBoost(3f)
        state.select(OutputRouting.SPEAKER, true)
        assertEquals(1.7f, state.boost, 0f)
        state.select("BT: New Car", true)
        assertEquals(3f, state.boost, 0f)
    }

    @Test fun wiredAndCarPresetsSurviveRepeatedDisconnects() {
        val presets = mutableMapOf(OutputRouting.SPEAKER to 1f, "Wired: Headphones" to 0.8f, "BT: Car" to 2f)
        val state = OutputPresetMemory(presets::get, { key, boost -> presets[key] = boost }, 1f)
        repeat(100) {
            state.select("BT: Car", true)
            assertEquals(2f, state.boost, 0f)
            state.select("Wired: Headphones", true)
            assertEquals(0.8f, state.boost, 0f)
            state.select(OutputRouting.SPEAKER, true)
            assertEquals(1f, state.boost, 0f)
        }
    }

    @Test fun disablingAutomaticBoostDoesNotLieAboutCurrentOutput() {
        val presets = mutableMapOf(OutputRouting.SPEAKER to 1f, "BT: Car" to 3f)
        val state = OutputPresetMemory(presets::get, { key, boost -> presets[key] = boost }, 1f)
        state.select("BT: Car", false)
        assertEquals("BT: Car", state.device)
        assertEquals(1f, state.boost, 0f)
    }

    @Test fun flatMixerIsUnityAndHeadroomOffsetsPositiveGain() {
        assertEquals(1f, ToneMath.gain(0, 0), 0f)
        assertEquals(0.2512f, ToneMath.gain(0, 1200), 0.0001f)
        assertEquals(1f, ToneMath.gain(12, 1200), 0f)
        assertTrue(ToneMath.gain(-24, 1500) > 0f)
    }
}
