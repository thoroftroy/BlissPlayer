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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Live proof for the v0.13.x background-job complaints on the full YMusic
 * library: trim must show waveform-edge progress within seconds with a sane
 * ETA, and Stop (cancelTrim) must actually stop with no restart.
 * Per the test note, this uses the early ETA instead of running the whole
 * 1177-song job: 25s of progress, then cancel.
 */
@RunWith(AndroidJUnit4::class)
class TrimStopTest {
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

    @Test fun trimShowsFastEtaAndStopActuallyStops() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val binder = serviceRule.bindService(Intent(context, PlayerService::class.java).setAction("com.blissplayer.music.PLAYER_UI"))
        val svc = (binder as PlayerService.LocalBinder).service().also { service = it }
        val songs = MusicScanner.scanAll(context).filter { (it.filePath ?: "").contains("/YMusic/") }
        assertTrue("YMusic not on device: found ${songs.size}", songs.size >= 1000)

        val events = CopyOnWriteArrayList<Triple<Int, Int, String>>()
        val firstProgress = CountDownLatch(1)
        instrumentation.runOnMainSync {
            svc.progressListener = { _, done, total, text ->
                events.add(Triple(done, total, text))
                if (done > 0) firstProgress.countDown()
            }
        }
        instrumentation.runOnMainSync { svc.startSilenceTrim(songs) }
        // ETA must appear within seconds, not after the whole library.
        assertTrue("no trim progress in 60s", firstProgress.await(60, TimeUnit.SECONDS))
        // Let it run a bit more so the ETA stabilizes, per the test note.
        Thread.sleep(20000)
        val last = events.maxByOrNull { it.first }!!
        val (done, total, text) = last
        android.util.Log.i("TrimStopTest", "trim progress done=$done total=$total text=$text events=${events.size}")
        assertTrue("trim made no progress: $text", done > 0)
        assertTrue("trim ETA missing total: $text", total == songs.size || total > 0)
        // Extrapolated wall time must be under 30 min: rate > total/1800 per sec.
        // We do not run the whole job; the early rate is the verdict.
        val elapsedSec = 25.0
        val rate = done / elapsedSec
        val projectedSec = total / rate.coerceAtLeast(0.01)
        android.util.Log.i("TrimStopTest", "rate=$rate/s projected=${projectedSec}s")
        assertTrue("trim too slow: $done in 25s projects to ${projectedSec.toInt()}s (>1800s). Text: $text", projectedSec < 1800)

        // Stop must stop: no forward progress after cancel, no restart.
        val doneAtCancel = last.first
        instrumentation.runOnMainSync { svc.cancelTrim() }
        Thread.sleep(6000)
        val forwards = events.filter { it.first > doneAtCancel }
        assertTrue("trim restarted after Stop (${forwards.size} forward events)", forwards.isEmpty())
        assertFalse("trim still running after Stop", svc.trimRunning)
    }
}
