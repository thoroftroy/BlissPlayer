package com.blissplayer.music

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.blissplayer.music.data.BlissDb
import com.blissplayer.music.data.MusicScanner
import com.blissplayer.music.data.SongAnalyzer
import com.blissplayer.music.data.TagTools
import com.blissplayer.music.model.Song
import com.blissplayer.music.playback.AudioOutput
import com.blissplayer.music.playback.OutputRouting
import com.blissplayer.music.playback.PlayerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Runs against a real Android service/MediaStore. All songs are disposable test copies. */
@RunWith(AndroidJUnit4::class)
class StabilityRegressionTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val created = mutableListOf<Uri>()
    private var service: PlayerService? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private var oldDeclined = false
    private var oldFocus: String? = null

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Before fun setup() {
        device.wakeUp()
        shell("wm dismiss-keyguard")
        val permission = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
        shell("pm grant ${context.packageName} $permission")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        if (Build.VERSION.SDK_INT >= 31) shell("pm grant ${context.packageName} android.permission.BLUETOOTH_CONNECT")
        val prefs = context.getSharedPreferences("bliss", Context.MODE_PRIVATE)
        oldDeclined = prefs.getBoolean("background_prompt_declined", false)
        oldFocus = prefs.getString("focus_mode", null)
        prefs.edit().putBoolean("background_prompt_declined", true).putString("focus_mode", "CallsOnly").commit()
    }

    @After fun cleanup() {
        shell("dumpsys deviceidle unforce")
        shell("dumpsys battery reset")
        device.wakeUp()
        scenario?.close()
        main { service?.player?.stop(); service?.player?.clearMediaItems() }
        context.stopService(Intent(context, PlayerService::class.java))
        created.forEach { context.contentResolver.delete(it, null, null) }
        context.getSharedPreferences("bliss", Context.MODE_PRIVATE).edit()
            .putBoolean("background_prompt_declined", oldDeclined).putString("focus_mode", oldFocus).commit()
        CrashReporter.clear(context)
    }

    private fun sample(): Song {
        val name = "bliss_regression_${System.nanoTime()}.m4a"
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.TITLE, "\"Regression Song\"")
                put(MediaStore.Audio.Media.ARTIST, "\"Regression Artist\"")
                put(MediaStore.Audio.Media.ALBUM, "Regression Album")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/BlissPlayerTests/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }))
        created.add(uri)
        instrumentation.context.assets.open("regression-tone.m4a").use { input ->
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { input.copyTo(it) }
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return Song(ContentUris.parseId(uri), uri.toString(), "/storage/emulated/0/Music/BlissPlayerTests/$name",
            "\"Regression Song\"", "\"Regression Artist\"", "Regression Album", 3000, 10000, "audio/mp4")
    }

    private fun bind(): PlayerService {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val binder = serviceRule.bindService(Intent(context, PlayerService::class.java).setAction("com.blissplayer.music.PLAYER_UI"))
        return (binder as PlayerService.LocalBinder).service().also { service = it }
    }

    private fun awaitState(message: String, expected: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            main { done = expected() }
            if (done) return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    @Test fun analyzerAndOrganizerProgressCanTouchRealViews() = runBlocking(Dispatchers.Main) {
        val song = sample()
        val label = TextView(context)
        val proposals = SongAnalyzer.analyze(listOf(song), { "Rock" }, { "Regression Album" }, false) { done, _, _ ->
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            label.text = done.toString()
        }
        assertEquals("Regression Song", proposals.single().newTitle)
        val moved = SongAnalyzer.organize(context, listOf(song)) { done, _, _ ->
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            label.text = done.toString()
        }
        assertEquals(moved.notes.toString(), 1, moved.moved)
        assertEquals(1, SongAnalyzer.organize(context, listOf(song)) { _, _, _ -> }.alreadyOrganized)
        val bad = song.copy(contentUri = "content://media/external_primary/audio/media/999999999")
        val failed = SongAnalyzer.organize(context, listOf(bad)) { _, _, _ -> }
        assertEquals(1, failed.skipped)
        assertTrue("Failure has no explanation", failed.notes.isNotEmpty())
    }

    @Test fun m4aFixesReallyPersistAndAppearInAndroidLibrary() = runBlocking {
        val song = sample()
        val result = TagTools.writeTags(context, song, TagTools.Edit("Regression Song", "Regression Artist", "Regression Album", "Rock", null, null))
        assertEquals(result.toString(), TagTools.WriteResult.Ok, result)
        val tags = TagTools.readTags(context, song)
        assertEquals("Regression Song", tags[0])
        assertEquals("Regression Artist", tags[1])
        val scanned = MusicScanner.scanAll(context).first { it.mediaId == song.mediaId }
        assertEquals("Regression Song", scanned.title)
        assertEquals("Regression Artist", scanned.artist)
    }

    @Test fun outputSnapshotsRestoreBoostWithUiDestroyed() {
        val svc = bind()
        scenario!!.close()
        scenario = null
        val car = AudioOutput("regression-car", "BT: Regression Car", true)
        val wired = AudioOutput("regression-wired", "Wired: Regression Headphones")
        val db = BlissDb(context)
        var original = 1f
        main {
            svc.volumeManager.refreshRoute()
            original = svc.volumeManager.boost
            svc.volumeManager.setBoost(1f)
            svc.volumeManager.reconcileOutputs(listOf(OutputRouting.speaker, car), listOf(car))
            assertEquals(1f, svc.volumeManager.boost, 0f) // automatic new preset
            svc.volumeManager.setBoost(2.4f)
            svc.volumeManager.reconcileOutputs(listOf(OutputRouting.speaker, wired), listOf(wired))
            svc.volumeManager.setBoost(0.8f)
            repeat(20) {
                svc.volumeManager.reconcileOutputs(listOf(OutputRouting.speaker, car), listOf(car))
                assertEquals(2.4f, svc.boost, 0f)
                // Car turns off but an old Bluetooth route snapshot still arrives.
                svc.volumeManager.reconcileOutputs(listOf(OutputRouting.speaker), listOf(car))
                assertEquals(OutputRouting.SPEAKER, svc.volumeManager.currentDevice)
                assertEquals(1f, svc.boost, 0f)
                svc.volumeManager.reconcileOutputs(listOf(OutputRouting.speaker, wired), listOf(wired))
                assertEquals(0.8f, svc.boost, 0f)
            }
            svc.volumeManager.refreshRoute()
            svc.volumeManager.setBoost(original)
        }
        db.writableDatabase.delete("boost", "device_name IN (?,?)", arrayOf(car.name, wired.name))
        db.close()
    }

    @Test fun backgroundScreenOffDozeAndTransientFocusDoNotRequireReopeningUi() {
        val song = sample()
        val svc = bind()
        val transitions = AtomicInteger()
        main {
            svc.player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { transitions.incrementAndGet() }
            })
            svc.player.setMediaItem(MediaItem.Builder().setMediaId(song.mediaId.toString()).setUri(song.contentUri).build())
            svc.player.repeatMode = Player.REPEAT_MODE_ONE
            svc.player.prepare()
            svc.player.play()
        }
        awaitState("Playback never started") { svc.player.isPlaying }
        awaitState("Audio session was never created") { svc.player.audioSessionId > 0 }
        main {
            svc.tone.reset()
            assertFalse(svc.tone.enabled)
            assertEquals(1f, svc.tone.gain(), 0f)
            val bands = svc.tone.bands()
            if (bands.isNotEmpty()) {
                val bass = bands.first()
                svc.tone.setEnabled(true)
                svc.tone.setBand(bass.index, bass.max)
                assertEquals(bass.max, svc.tone.bands().first().level)
                assertTrue("Headroom did not lower gain", svc.tone.gain() < 1f)
                svc.tone.setHeadroom(false)
                svc.tone.setPreamp(12)
                assertTrue("Preamp did not increase gain", svc.tone.gain() > 3.9f)
                svc.tone.reset()
                svc.applyGainFor(svc.player.currentMediaItem)
            } else assertNotNull("Unsupported mixer had no explanation", svc.tone.problem)
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper())).build()
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(focus))
        awaitState("Temporary focus loss did not pause/suppress music") { !svc.player.isPlaying }
        audio.abandonAudioFocusRequest(focus)
        awaitState("Music did not resume after temporary focus loss") { svc.player.isPlaying }
        scenario!!.close()
        scenario = null
        device.pressHome()
        device.sleep()
        shell("dumpsys battery unplug")
        shell("dumpsys deviceidle force-idle")
        val before = transitions.get()
        SystemClock.sleep(10000)
        main { assertTrue("Playback stopped with no UI", svc.player.isPlaying) }
        assertTrue("Song advancement stalled in doze", transitions.get() > before + 1)
        main { svc.player.pause() }
        SystemClock.sleep(1500)
        main { assertFalse("User pause was overridden", svc.player.isPlaying) }
        context.startService(Intent(context, PlayerService::class.java).setAction(PlayerService.ACTION_TOGGLE))
        awaitState("Notification play required reopening the UI") { svc.player.isPlaying }
        main { svc.player.pause() }
    }

    @Test fun backgroundRestrictionIsOfferedAtStartupAndDeclineIsRemembered() {
        org.junit.Assume.assumeFalse("Already exempt from battery optimization",
            context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
        context.getSharedPreferences("bliss", Context.MODE_PRIVATE).edit()
            .putBoolean("background_prompt_declined", false).commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(device.wait(Until.hasObject(By.text("Allow background playback?")), 5000))
        checkNotNull(device.findObject(By.text("Not now"))).click()
        scenario!!.recreate()
        instrumentation.waitForIdleSync()
        assertFalse(device.hasObject(By.text("Allow background playback?")))
    }

    @Test fun crashAlertDismissedWithBackDoesNotRepeatOrExport() {
        fun downloadCount(): Int = context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("BlissPlayer-crash%"), null)!!.use { it.count }
        val count = downloadCount()
        context.openFileOutput("crash_last.txt", Context.MODE_PRIVATE).use { it.write("Old 0.6.0 regression crash".toByteArray()) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(device.wait(Until.hasObject(By.text("Previous crash report")), 5000))
        device.pressBack()
        scenario!!.recreate()
        instrumentation.waitForIdleSync()
        assertFalse(device.hasObject(By.text("Previous crash report")))
        assertEquals(count, downloadCount())
        assertEquals("Old 0.6.0 regression crash", CrashReporter.read(context))
    }
}
