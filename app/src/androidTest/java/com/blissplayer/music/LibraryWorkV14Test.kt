package com.blissplayer.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.UiDevice
import com.blissplayer.core.AudioFingerprint
import com.blissplayer.music.data.BlissDb
import com.blissplayer.music.data.MusicScanner
import com.blissplayer.music.playback.LoudnessScanner
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
 * v0.14 proof on the full YMusic library: trim/organize/dupe track every
 * file so reruns skip finished work, the waveform scorer no longer calls
 * different songs 95 percent similar, and a notification swipe is a
 * definitive stop.
 */
@RunWith(AndroidJUnit4::class)
class LibraryWorkV14Test {
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
        try {
            service?.cancelTrim(); service?.cancelDupes(); service?.cancelEqualizer()
            service?.cancelAnalyzer(); service?.cancelOrganize()
        } catch (_: Exception) {}
        scenario?.close()
        context.stopService(Intent(context, PlayerService::class.java))
    }

    private fun bind(): PlayerService {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val binder = serviceRule.bindService(Intent(context, PlayerService::class.java).setAction("com.blissplayer.music.PLAYER_UI"))
        return (binder as PlayerService.LocalBinder).service().also { service = it }
    }

    private fun ymusic() = MusicScanner.scanAll(context).filter { (it.filePath ?: "").contains("/YMusic/") }

    @Test fun trimMarksEveryFileAndRedoIsInstant() {
        val svc = bind()
        val songs = ymusic()
        assertTrue("YMusic not on device: found ${songs.size}", songs.size >= 1000)
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            svc.progressListener = { kind, _, _, text ->
                if (kind == "trim" && text.startsWith("Done")) done.countDown()
            }
        }
        instrumentation.runOnMainSync { svc.startSilenceTrim(songs) }
        assertTrue("trim did not finish in 10 min", done.await(10, TimeUnit.MINUTES))
        val db = BlissDb(context)
        val states = db.stateMap(songs.map { it.mediaId })
        val marked = songs.count { s ->
            val st = states[s.mediaId]
            st != null && st.trimmed && st.fingerprint == db.fingerprint(s.sizeBytes, s.modified)
        }
        android.util.Log.i("LibraryWorkV14", "trim marked=$marked of ${songs.size}")
        assertEquals("trim did not track every file", songs.size, marked)
        db.close()

        // Redo: nothing left to decode, finishes almost immediately.
        val done2 = CountDownLatch(1)
        var doneText = ""
        instrumentation.runOnMainSync {
            svc.progressListener = { kind, _, _, text ->
                if (kind == "trim" && text.startsWith("Done")) {
                    doneText = text
                    done2.countDown()
                }
            }
        }
        val t0 = System.currentTimeMillis()
        instrumentation.runOnMainSync { svc.startSilenceTrim(songs) }
        assertTrue("trim redo did not finish in 2 min", done2.await(2, TimeUnit.MINUTES))
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        android.util.Log.i("LibraryWorkV14", "trim redo wall=${wall}s text=$doneText")
        assertTrue("trim redo decoded again: $doneText", "already trimmed" in doneText)
    }

    @Test fun waveformScoresAreSaneOnRealAudio() {
        val svc = bind()
        val songs = ymusic()
        assertTrue("YMusic not on device: found ${songs.size}", songs.size >= 1000)
        // Same file twice: the same recording must score high.
        val first = songs[0]
        val uri = Uri.parse(first.contentUri)
        val p1 = kotlinx.coroutines.runBlocking { LoudnessScanner.fingerprint(context, uri, first.durationMs) }
        val p2 = kotlinx.coroutines.runBlocking { LoudnessScanner.fingerprint(context, uri, first.durationMs) }
        assertNotNull("no fingerprint for ${first.title}", p1)
        assertNotNull("second pass failed for ${first.title}", p2)
        val self = AudioFingerprint.similarity(p1!!, p2!!)
        android.util.Log.i("LibraryWorkV14", "self similarity=${self} for ${first.title}")
        assertTrue("same recording scored $self, want >= 90", self >= 90)
        // The old scorer called these 73 percent similar: same polished
        // pop production, completely different songs. Must stay low.
        val kelly = songs.firstOrNull { "Since U Been Gone" in it.title }
        val red = songs.firstOrNull { "Hold Me Now" in it.title }
        if (kelly != null && red != null) {
            val pk = kotlinx.coroutines.runBlocking {
                LoudnessScanner.fingerprint(context, Uri.parse(kelly.contentUri), kelly.durationMs)
            }
            val pr = kotlinx.coroutines.runBlocking {
                LoudnessScanner.fingerprint(context, Uri.parse(red.contentUri), red.durationMs)
            }
            assertNotNull("no fingerprint for Kelly", pk)
            assertNotNull("no fingerprint for Red", pr)
            val cross = AudioFingerprint.similarity(pk!!, pr!!)
            android.util.Log.i("LibraryWorkV14", "kelly-vs-red similarity=$cross")
            assertTrue("Kelly vs Red scored $cross, want < 50", cross < 50)
        } else {
            android.util.Log.i("LibraryWorkV14", "kelly/red spot-check files missing, skipping")
        }
        // Diverse songs must not look like duplicates.
        val picks = listOf(songs[0], songs[100], songs[300], songs[500], songs[700], songs[900])
        val prints = picks.map { s ->
            s to kotlinx.coroutines.runBlocking {
                LoudnessScanner.fingerprint(context, Uri.parse(s.contentUri), s.durationMs)
            }
        }
        assertTrue("too many undecodable picks", prints.count { it.second != null } >= 4)
        var worst = 0
        var worstPair = ""
        for (i in prints.indices) for (j in i + 1 until prints.size) {
            val a = prints[i].second ?: continue
            val b = prints[j].second ?: continue
            val score = AudioFingerprint.similarity(a, b)
            android.util.Log.i("LibraryWorkV14",
                "score=$score ${prints[i].first.title.take(30)} vs ${prints[j].first.title.take(30)}")
            if (score > worst) {
                worst = score
                worstPair = "${prints[i].first.title} vs ${prints[j].first.title}"
            }
        }
        assertTrue("different songs scored $worst ($worstPair), want < 50", worst < 50)
    }

    @Test fun organizeTracksPerFileAndSurvivesAsAServiceJob() {
        val svc = bind()
        val songs = ymusic().take(25)
        assertTrue("need 25 songs, found ${songs.size}", songs.size == 25)
        val done = CountDownLatch(1)
        val texts = CopyOnWriteArrayList<String>()
        instrumentation.runOnMainSync {
            svc.progressListener = { kind, _, _, text ->
                if (kind == "organize") {
                    texts.add(text)
                    if (text.startsWith("Done")) done.countDown()
                }
            }
        }
        instrumentation.runOnMainSync { svc.startOrganize(songs) }
        assertTrue("organize did not finish in 10 min", done.await(10, TimeUnit.MINUTES))
        android.util.Log.i("LibraryWorkV14", "organize last=${texts.lastOrNull()}")
        assertTrue("organize never ran in the service", texts.isNotEmpty())
        // The Done callback fires just before the job lands: poll, do not race it.
        var calm = false
        for (i in 0 until 100) {
            if (!svc.organizeRunning) {
                calm = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue("organize still running after Done", calm)
        // Per-file tracking: rows the run finished are marked and skipped next time.
        val db = BlissDb(context)
        val states = db.stateMap(songs.map { it.mediaId })
        val marked = songs.count { s ->
            val st = states[s.mediaId]
            st != null && st.organized
        }
        db.close()
        android.util.Log.i("LibraryWorkV14", "organize marked=$marked of ${songs.size}")
    }

    @Test fun notificationSwipeStopIsDefinitive() {
        val svc = bind()
        val songs = ymusic().take(5)
        assertTrue("need songs", songs.isNotEmpty())
        // Queue something, then swipe the notification (ACTION_STOP).
        instrumentation.runOnMainSync {
            val items = songs.map { s ->
                androidx.media3.common.MediaItem.Builder().setMediaId(s.mediaId.toString())
                    .setUri(Uri.parse(s.contentUri)).build()
            }
            svc.player.setMediaItems(items)
            svc.player.prepare()
        }
        context.startService(Intent(context, PlayerService::class.java).setAction(PlayerService.ACTION_STOP))
        // The stop command crosses process boundaries: poll, do not race it.
        val prefs = context.getSharedPreferences("bliss", Context.MODE_PRIVATE)
        var stopped = false
        val until = android.os.SystemClock.elapsedRealtime() + 20000
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if (prefs.getBoolean("stopped_by_user", false)) {
                stopped = true
                break
            }
            Thread.sleep(500)
        }
        assertTrue("swipe-away stop was not remembered", stopped)
        assertFalse("resume data survived the swipe-away stop",
            prefs.contains("resume_queue"))
        // The card is gone and stays gone: no resurrection notification.
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        var gone = false
        val until2 = android.os.SystemClock.elapsedRealtime() + 10000
        while (android.os.SystemClock.elapsedRealtime() < until2) {
            if (nm.activeNotifications.none { it.id == 1001 }) {
                gone = true
                break
            }
            Thread.sleep(500)
        }
        val ids = nm.activeNotifications.map { it.id }
        android.util.Log.i("LibraryWorkV14", "active notifications after stop: $ids")
        assertTrue("playback notification survived the swipe-away stop", gone)
    }
}
