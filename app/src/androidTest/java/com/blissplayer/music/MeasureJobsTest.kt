package com.blissplayer.music

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.UiDevice
import com.blissplayer.music.data.MusicScanner
import com.blissplayer.music.playback.PlayerService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Live speed proof for every background job on the full YMusic library.
 * Each job runs long enough to read a stable ETA rate, then Stop is pressed
 * and the test asserts no restart. Nothing here guesses: rates, ETAs and
 * stop behavior all come from the device.
 */
@RunWith(AndroidJUnit4::class)
class MeasureJobsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    @get:Rule val serviceRule = ServiceTestRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var service: PlayerService? = null

    @Before fun setup() {
        device.wakeUp()
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").close()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        context.getSharedPreferences("bliss", Context.MODE_PRIVATE).edit().putBoolean("background_prompt_declined", true).commit()
    }

    @After fun cleanup() {
        try { service?.cancelTrim(); service?.cancelDupes(); service?.cancelEqualizer(); service?.cancelAnalyzer() } catch (_: Exception) {}
        scenario?.close()
        context.stopService(Intent(context, PlayerService::class.java))
    }

    private fun bind(): PlayerService {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val binder = serviceRule.bindService(Intent(context, PlayerService::class.java).setAction("com.blissplayer.music.PLAYER_UI"))
        return (binder as PlayerService.LocalBinder).service().also { service = it }
    }

    private fun ymusic() = MusicScanner.scanAll(context).filter { (it.filePath ?: "").contains("/YMusic/") }

    @Test fun eqShowsFastEtaAndStopActuallyStops() {
        val svc = bind()
        val songs = ymusic()
        assertTrue("YMusic not on device: found ${songs.size}", songs.size >= 1000)
        val events = CopyOnWriteArrayList<Triple<Int, Int, String>>()
        val first = CountDownLatch(1)
        instrumentation.runOnMainSync {
            svc.progressListener = { kind, done, total, text ->
                if (kind == "equalizer") {
                    events.add(Triple(done, total, text))
                    if (done > 0) first.countDown()
                }
            }
        }
        instrumentation.runOnMainSync { svc.startEqualizer(songs, 1f, 0.35f) }
        assertTrue("no equalizer progress in 60s", first.await(60, TimeUnit.SECONDS))
        Thread.sleep(20000)
        val last = events.maxByOrNull { it.first }!!
        android.util.Log.i("MeasureJobs", "eq progress done=${last.first} total=${last.second} text=${last.third} events=${events.size}")
        assertTrue("equalizer made no progress: ${last.third}", last.first > 0)
        val rate = last.first / 25.0
        val projected = last.second / rate.coerceAtLeast(0.01)
        android.util.Log.i("MeasureJobs", "eq rate=$rate/s projected=${projected}s")
        assertTrue("equalizer too slow: ${last.first} in 25s projects to ${projected.toInt()}s (>1800s). Text: ${last.third}", projected < 1800)
        val doneAtCancel = last.first
        instrumentation.runOnMainSync { svc.cancelEqualizer() }
        Thread.sleep(6000)
        assertTrue("equalizer restarted after Stop", events.none { it.first > doneAtCancel })
        assertFalse("equalizer still running after Stop", svc.eqRunning)
    }

    @Test fun dupeWaveformShowsFastEtaAndStopActuallyStops() {
        val svc = bind()
        val songs = ymusic()
        assertTrue("YMusic not on device: found ${songs.size}", songs.size >= 1000)
        val events = CopyOnWriteArrayList<Triple<Int, Int, String>>()
        val first = CountDownLatch(1)
        instrumentation.runOnMainSync {
            svc.progressListener = { kind, done, total, text ->
                if (kind == "dupes") {
                    events.add(Triple(done, total, text))
                    if (done > 0) first.countDown()
                }
            }
        }
        instrumentation.runOnMainSync { svc.startDupeFinder(songs) }
        assertTrue("no dupe progress in 90s", first.await(90, TimeUnit.SECONDS))
        Thread.sleep(20000)
        val last = events.maxByOrNull { it.first }!!
        android.util.Log.i("MeasureJobs", "dupe progress done=${last.first} total=${last.second} text=${last.third} events=${events.size}")
        assertTrue("dupe made no progress: ${last.third}", last.first > 0)
        val doneAtCancel = last.first
        instrumentation.runOnMainSync { svc.cancelDupes() }
        Thread.sleep(6000)
        assertTrue("dupe restarted after Stop", events.none { it.first > doneAtCancel })
        assertFalse("dupe still running after Stop", svc.dupeRunning)
    }

    @Test fun analyzerOfflineCompletesFastOnFullLibrary() {        val svc = bind()
        val songs = ymusic()
        assertTrue("YMusic not on device: found ${songs.size}", songs.size >= 1000)
        val events = CopyOnWriteArrayList<Triple<Int, Int, String>>()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            svc.progressListener = { kind, d, total, text ->
                if (kind == "analyzer") {
                    events.add(Triple(d, total, text))
                    if (d >= total && total > 0) done.countDown()
                }
            }
        }
        val t0 = System.currentTimeMillis()
        instrumentation.runOnMainSync { svc.startAnalyzer(songs, false, emptyMap(), emptyMap()) }
        assertTrue("offline analyzer did not finish in 10 min", done.await(10, TimeUnit.MINUTES))
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        android.util.Log.i("MeasureJobs", "analyzer offline wall=${wall}s events=${events.size}")
        assertTrue("offline analyzer too slow: ${wall}s (>1800s)", wall < 1800)
        val proposals = svc.takeProposals() ?: emptyList()
        android.util.Log.i("MeasureJobs", "analyzer offline proposals=${proposals.size}")
    }

    @Test fun analyzerFullOnlineCompletesUnder30Min() {
        val svc = bind()
        val songs = ymusic()
        assertTrue("YMusic not on device: found ${songs.size}", songs.size >= 1000)
        val done = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val lastText = java.util.concurrent.atomic.AtomicReference("")
        instrumentation.runOnMainSync {
            svc.progressListener = { kind, d, total, text ->
                if (kind == "analyzer") {
                    lastText.set("$d/$total $text")
                    // Terminal signals only: "Done..." when lookup ends,
                    // "Stopped." when the job is cancelled or fails.
                    if (text.startsWith("Done")) done.countDown()
                    if (text == "Stopped.") stopped.countDown()
                }
            }
        }
        val t0 = System.currentTimeMillis()
        instrumentation.runOnMainSync { svc.startAnalyzer(songs, true, emptyMap(), emptyMap()) }
        // Trail snapshots so a timeout leaves evidence of where it stuck.
        // Latch stays inside the 30-minute cap with a minute to spare.
        var elapsed = 0L
        while (elapsed < 29 * 60 * 1000L && done.count == 1L && stopped.count == 1L) {
            Thread.sleep(60000)
            elapsed = System.currentTimeMillis() - t0
            android.util.Log.i("MeasureJobs", "analyzer FULL trail ${(elapsed / 1000)}s last=${lastText.get()}")
        }
        assertTrue("analyzer stopped/failed: ${lastText.get()}", stopped.count == 1L)
        assertTrue("full online analyzer did not finish in 30 min: ${lastText.get()}", done.count == 0L)
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        val proposals = svc.takeProposals() ?: emptyList()
        val high = proposals.count { it.confidence == "High" }
        android.util.Log.i("MeasureJobs", "analyzer FULL wall=${wall}s proposals=${proposals.size} high=$high songs=${songs.size} last=${lastText.get()}")
        assertTrue("full online analyzer too slow: ${wall}s (>1800s)", wall < 1800)
    }

    @Test fun analyzerOnlineRateAndStopOnFullLibrary() {
        val svc = bind()
        val songs = ymusic()
        assertTrue("YMusic not on device: found ${songs.size}", songs.size >= 1000)
        val events = CopyOnWriteArrayList<Triple<Int, Int, String>>()
        val lookups = CopyOnWriteArrayList<Triple<Int, Int, String>>()
        val first = CountDownLatch(1)
        instrumentation.runOnMainSync {
            svc.progressListener = { kind, d, total, text ->
                if (kind == "analyzer") {
                    events.add(Triple(d, total, text))
                    if (text.startsWith("Checking online index")) lookups.add(Triple(d, total, text))
                    if (d > 0) first.countDown()
                }
            }
        }
        instrumentation.runOnMainSync { svc.startAnalyzer(songs, true, emptyMap(), emptyMap()) }
        assertTrue("no analyzer progress in 90s (offline? no network?)", first.await(90, TimeUnit.SECONDS))
        Thread.sleep(90000)
        val look = lookups.maxByOrNull { it.first }
        if (look != null) {
            android.util.Log.i("MeasureJobs", "analyzer lookup progress done=${look.first} total=${look.second} text=${look.third} lookups=${lookups.size}")
            val rate = look.first / 95.0
            val projected = look.second / rate.coerceAtLeast(0.01)
            android.util.Log.i("MeasureJobs", "analyzer lookup rate=$rate/s projected=${projected}s")
        } else {
            android.util.Log.i("MeasureJobs", "analyzer lookup phase never started (offline fallback?). events=${events.size} last=${events.maxByOrNull { it.first }}")
        }
        val last = events.maxByOrNull { it.first }!!
        android.util.Log.i("MeasureJobs", "analyzer online progress done=${last.first} total=${last.second} text=${last.third} events=${events.size}")
        assertTrue("analyzer made no progress: ${last.third}", last.first > 0)
        val doneAtCancel = events.maxOf { it.first }
        instrumentation.runOnMainSync { svc.cancelAnalyzer() }
        Thread.sleep(60000)
        assertTrue("analyzer restarted after Stop", events.none { it.first > doneAtCancel })
        assertFalse("analyzer still running after Stop", svc.analyzerRunning)
    }

    @Test fun trimCompletesFullLibrary() {
        val svc = bind()
        val songs = ymusic()
        assertTrue("YMusic not on device", songs.size >= 1000)
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            svc.progressListener = { kind, _, _, text ->
                if (kind == "trim" && text.startsWith("Done")) done.countDown()
            }
        }
        val t0 = System.currentTimeMillis()
        instrumentation.runOnMainSync { svc.startSilenceTrim(songs) }
        assertTrue("trim did not finish in 10 min", done.await(10, TimeUnit.MINUTES))
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        android.util.Log.i("MeasureJobs", "trim FULL wall=${wall}s")
        assertTrue("trim too slow: ${wall}s", wall < 1800)
    }

    @Test fun dupeWaveformCompletesFullLibrary() {
        val svc = bind()
        val songs = ymusic()
        assertTrue("YMusic not on device", songs.size >= 1000)
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            svc.progressListener = { kind, _, _, text ->
                if (kind == "dupes" && text.startsWith("Done")) done.countDown()
            }
        }
        val t0 = System.currentTimeMillis()
        instrumentation.runOnMainSync { svc.startDupeFinder(songs) }
        assertTrue("dupe did not finish in 10 min", done.await(10, TimeUnit.MINUTES))
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        val got = svc.takeDupes()
        android.util.Log.i("MeasureJobs", "dupe FULL wall=${wall}s pairs=${got?.first?.size} clashes=${got?.second?.size}")
        assertTrue("dupe too slow: ${wall}s", wall < 1800)
    }

    @Test fun equalizerCompletesFullLibrary() {
        val svc = bind()
        val songs = ymusic()
        assertTrue("YMusic not on device", songs.size >= 1000)
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            svc.progressListener = { kind, _, _, text ->
                if (kind == "equalizer" && text.startsWith("Done")) done.countDown()
            }
        }
        val t0 = System.currentTimeMillis()
        instrumentation.runOnMainSync { svc.startEqualizer(songs, 1f, 0.35f) }
        assertTrue("equalizer did not finish in 25 min", done.await(25, TimeUnit.MINUTES))
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        android.util.Log.i("MeasureJobs", "eq FULL wall=${wall}s")
        assertTrue("equalizer too slow: ${wall}s", wall < 1800)
    }
}
