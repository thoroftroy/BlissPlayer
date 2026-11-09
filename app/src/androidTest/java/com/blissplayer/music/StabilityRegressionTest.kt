package com.blissplayer.music

import android.app.NotificationManager
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
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.blissplayer.core.AnalyzerRules
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
import org.junit.Assume.assumeTrue
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

    @Test fun reviewSelectAllAppliesChecked() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val fake = Song(424242L, "content://fake/424242", null, "Fake Song", "Fake Artist",
            "Fake Album", 1000L, 100L, "audio/mp4", 1L)
        val proposal = AnalyzerRules.Proposal(
            fake, "Real Song", "Real Artist", "Real Album", "Rock", "Check", "test")
        scenario!!.onActivity { a ->
            val m = MainActivity::class.java.getDeclaredMethod("showConfirmList", List::class.java)
            m.isAccessible = true
            m.invoke(a, listOf(proposal))
        }
        assertTrue(device.wait(Until.hasObject(By.textContains("Confirm each fix")), 10000))
        device.findObject(By.text("Select all")).click()
        device.findObject(By.text("Apply selected")).click()
        // Bogus file: nothing to save, but the chain must complete, not crash.
        assertTrue(device.wait(Until.hasObject(By.text("Analyzer done")), 60000))
        device.findObject(By.text("OK")).click()
    }

    @Test fun swipeAwayKeepsNotificationAndPositionAdvances() {
        val song = sample()
        val svc = bind()
        main {
            svc.player.setMediaItem(MediaItem.Builder().setMediaId(song.mediaId.toString()).setUri(song.contentUri).build())
            svc.player.repeatMode = Player.REPEAT_MODE_ONE
            svc.player.prepare()
            svc.player.play()
        }
        awaitState("Playback never started") { svc.player.isPlaying }
        val nm = context.getSystemService(NotificationManager::class.java)
        fun blissNotification() = nm.activeNotifications.any { it.packageName == context.packageName }
        assertTrue("no notification while playing", blissNotification())
        // Swiping the app away: UI gone, process (and service) lives.
        scenario!!.close()
        scenario = null
        SystemClock.sleep(3000)
        main { assertTrue("playback died with the UI", svc.player.isPlaying) }
        assertTrue("notification died with the UI", blissNotification())
        var p0 = -1L
        main { p0 = svc.player.currentPosition }
        SystemClock.sleep(2000)
        var p1 = -1L
        main { p1 = svc.player.currentPosition }
        assertTrue("position bar frozen while showing playing ($p0 -> $p1)", p1 != p0)
        main { svc.player.pause() }
    }

    @Test fun nineZeroMorePaneFixes() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("library never loaded", device.wait(Until.hasObject(By.textContains("songs. Tap a song")), 60000))
        device.findObject(By.text("More")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Control Panel")), 10000))
        // Sections open deterministically (header taps are flaky on the
        // emulator): set the open-set pref and rebuild the pane.
        fun openSections(vararg names: String) {
            scenario!!.onActivity { a ->
                a.getSharedPreferences("bliss", android.content.Context.MODE_PRIVATE)
                    .edit().putStringSet("more_panels", names.toSet()).apply()
                val m = MainActivity::class.java.getDeclaredMethod("buildMorePane")
                m.isAccessible = true
                m.invoke(a)
            }
        }
        // Folder tree: collapsible with bulk buttons; entries stay listed.
        openSections("Your music", "Playback controls", "Sound", "Library work")
        scenario!!.onActivity { a ->
            val f = MainActivity::class.java.getDeclaredField("moreBox")
            f.isAccessible = true
            val box = f.get(a) as android.view.ViewGroup
            val sb = StringBuilder("PANE9 panes=${box.childCount}")
            for (i in 0 until box.childCount) {
                val p = box.getChildAt(i) as? android.view.ViewGroup ?: continue
                var head = "?"
                var kids = 0
                var vis = "?"
                for (j in 0 until p.childCount) {
                    val c = p.getChildAt(j)
                    if (c is android.widget.TextView && c.getTag(com.blissplayer.music.R.id.tabMore)?.toString()?.startsWith("section:") == true) head = c.text.toString()
                    else if (c is android.view.ViewGroup) {
                        kids = c.childCount
                        vis = c.visibility.toString()
                        for (k in 0 until c.childCount) {
                            val g = c.getChildAt(k)
                            val t = (g as? android.widget.TextView)?.text?.toString()?.take(40)
                                ?: (g as? android.widget.Button)?.text?.toString()?.take(40)
                            if (t != null) sb.append("\n  $head/${g.javaClass.simpleName}: $t")
                        }
                    }
                }
                sb.append("\nPANE9 [$head] kids=$kids vis=$vis")
            }
            println(sb.toString())
        }
        assertTrue("no Expand all", device.wait(Until.hasObject(By.text("Expand all")), 5000))
        assertTrue("no Collapse all", device.wait(Until.hasObject(By.text("Collapse all")), 8000))
        // Boost preset gains an explicit Delete; deleting removes just it.
        // (Below-the-fold nodes are invisible to uiautomator: scroll first.)
        val scroll = UiScrollable(UiSelector().scrollable(true))
        assertTrue("cannot scroll to Add preset",
            scroll.scrollIntoView(UiSelector().text("Add preset")))
        assertTrue("no Add preset", device.wait(Until.hasObject(By.text("Add preset")), 5000))
        device.findObject(By.text("Add preset")).click()
        assertTrue(device.wait(Until.hasObject(By.text("New preset")), 5000))
        val edits = device.findObjects(By.clazz("android.widget.EditText"))
        assertTrue("preset name field missing", edits.isNotEmpty())
        edits[0].text = "DeleteMe9"
        device.findObject(By.text("Save")).click()
        assertTrue("preset row missing", device.wait(Until.hasObject(By.textContains("DeleteMe9")), 5000))
        assertTrue("preset Delete missing", device.wait(Until.hasObject(By.text("Delete")), 5000))
        device.findObject(By.text("Delete")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Delete preset DeleteMe9?")), 5000))
        // Dialog window sorts after the covered activity: its Delete is last.
        val deletes = device.findObjects(By.text("Delete"))
        assertTrue("no Delete anywhere", deletes.isNotEmpty())
        deletes.last().click()
        assertTrue("preset survived delete", device.wait(Until.gone(By.textContains("DeleteMe9")), 8000))
        // Mixer attaches while a song plays instead of demanding playback.
        // (Bind the service directly: no second activity needed.)
        val binder = serviceRule.bindService(
            Intent(context, PlayerService::class.java).setAction("com.blissplayer.music.PLAYER_UI"))
        val svc = (binder as PlayerService.LocalBinder).service().also { service = it }
        val song = sample()
        main {
            svc.player.setMediaItem(MediaItem.Builder().setMediaId(song.mediaId.toString()).setUri(song.contentUri).build())
            svc.player.repeatMode = Player.REPEAT_MODE_ONE
            svc.player.prepare()
            svc.player.play()
        }
        awaitState("Playback never started") { svc.player.isPlaying }
        awaitState("Audio session never opened") { svc.player.audioSessionId != 0 }
        SystemClock.sleep(2000) // let the visible activity finish binding its svc
        main {
            assertTrue("tone did not attach with a live session", svc.ensureToneAttached())
            assertTrue("attached mixer has no bands", svc.tone.bands().isNotEmpty())
        }
        scenario!!.onActivity { a ->
            val sf = MainActivity::class.java.getDeclaredField("svc")
            sf.isAccessible = true
            assertNotNull("activity never bound its service", sf.get(a))
            val m = MainActivity::class.java.getDeclaredMethod("mixerDialog")
            m.isAccessible = true
            m.invoke(a)
        }
        assertTrue("mixer never opened", device.wait(Until.hasObject(By.text("Live frequency mixer")), 10000))
        assertFalse("mixer still demands playback with a song playing",
            device.hasObject(By.textContains("Play a song first")))
        assertTrue("mixer toggle missing", device.wait(Until.hasObject(By.textContains("Mixer:")), 8000))
        device.pressBack()
        // Equalizer strength slider and organize-all entry point exist.
        assertTrue("cannot scroll to EQ slider",
            scroll.scrollIntoView(UiSelector().textContains("Equalize strength")))
        assertTrue("no equalize strength slider", device.wait(Until.hasObject(By.textContains("Equalize strength")), 5000))
        assertTrue("cannot scroll to organize",
            scroll.scrollIntoView(UiSelector().textContains("Organize into BlissPlayer folder")))
        assertTrue("no organize-all", device.wait(Until.hasObject(By.textContains("Organize into BlissPlayer folder")), 5000))
        main { svc.player.pause() }
    }

    @Test fun organizeMovesDownloadsLeftovers() {
        // App-foreign audio files sitting in Download/: organize-all must
        // move every library file into Music/BlissPlayer, not just enabled
        // folders. One system consent tap covers the batch.
        val stamp = System.nanoTime()
        val names = (1..3).map { "blissleftover${stamp}_$it.m4a" }
        for (n in names) {
            // Audio bytes via the Downloads table: lands in Download/ and the
            // system media scanner indexes it as foreign audio, exactly like
            // a real user download.
            val duri = checkNotNull(context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, n)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }))
            instrumentation.context.assets.open("regression-tone.m4a").use { input ->
                checkNotNull(context.contentResolver.openOutputStream(duri)).use { input.copyTo(it) }
            }
            context.contentResolver.update(duri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
        }
        // Wait for the system to index the downloads as audio rows.
        fun audioRow(n: String): android.database.Cursor? = context.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf(n), null)
        val scanUntil = SystemClock.elapsedRealtime() + 60000
        while (SystemClock.elapsedRealtime() < scanUntil) {
            android.media.MediaScannerConnection.scanFile(context,
                names.map { "/sdcard/Download/$it" }.toTypedArray(), null, null)
            var seen = 0
            for (n in names) audioRow(n)!!.use { if (it.count > 0) seen++ }
            if (seen == 3) break
            SystemClock.sleep(2000)
        }
        for (n in names) audioRow(n)!!.use {
            assertTrue("system never indexed Download leftover $n", it.count > 0)
        }
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            assertTrue("library never loaded", device.wait(Until.hasObject(By.textContains("songs. Tap a song")), 60000))
            // Scope this run to the 3 leftovers (null would mean the
            // whole library): build their Songs from the indexed rows.
            val leftovers = context.contentResolver.query(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("blissleftover${stamp}_%.m4a"), null
            )!!.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val ti = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val ar = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val al = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val da = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                generateSequence { if (c.moveToNext()) c else null }.map {
                    val mid = it.getLong(id)
                    com.blissplayer.music.model.Song(mid,
                        "content://media/external_primary/audio/media/$mid",
                        it.getString(da), it.getString(ti) ?: "?", it.getString(ar) ?: "Unknown artist",
                        it.getString(al) ?: "Unknown album", 3000, 10000, "audio/mp4")
                }.toList()
            }
            assertEquals("leftovers not all indexed", 3, leftovers.size)
            scenario!!.onActivity { a ->
                val m = MainActivity::class.java.getDeclaredMethod(
                    "runOrganize", List::class.java, Set::class.java)
                m.isAccessible = true
                m.invoke(a, leftovers, emptySet<String>())
            }
            val until = SystemClock.elapsedRealtime() + 120000
            var done = false
            while (SystemClock.elapsedRealtime() < until) {
                // System consent sheet ("Allow") appears for foreign files.
                runCatching { device.findObject(By.text("Allow"))?.click() }
                if (device.hasObject(By.textContains("Moved 3 songs into the BlissPlayer folder"))) {
                    done = true
                    break
                }
                SystemClock.sleep(1000)
            }
            runCatching { device.findObject(By.text("OK"))?.click() }
            assertTrue("organizer never reported moving all 3 leftovers", done)
            // Ground truth: every leftover now lives under BlissPlayer.
            context.contentResolver.query(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("blissleftover${stamp}_%.m4a"), null
            )!!.use { c ->
                val data = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                var under = 0
                var total = 0
                while (c.moveToNext()) {
                    total++
                    if ((c.getString(data) ?: "").contains("/BlissPlayer/")) under++
                }
                assertEquals("not all leftovers moved: $under/$total", 3, under)
            }
        } finally {
            // Shell rm: rows may still be foreign-owned after the move.
            shell("rm -f /sdcard/Download/blissleftover${stamp}_*.m4a /sdcard/Music/BlissPlayer/*/*blissleftover${stamp}_*.m4a")
            shell("rm -f /sdcard/Music/BlissPlayer/*/*/*blissleftover${stamp}_*.m4a")
            context.contentResolver.delete(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("blissleftover${stamp}_%.m4a"))
        }
    }

    @Test fun backButtonWalksTabsAndStatusStripOpensBoost() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("library never loaded", device.wait(Until.hasObject(By.textContains("songs. Tap a song")), 60000))
        // Songs -> More -> Back -> Songs (not out of the app).
        device.findObject(By.text("More")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Control Panel")), 10000))
        scenario!!.onActivity { a -> assertTrue("goBack refused", a.goBack()) }
        assertTrue(device.wait(Until.hasObject(By.textContains("songs. Tap a song")), 5000))
        // Status strip tap is a quick boost action.
        val strip = device.wait(Until.findObject(By.textContains("songs | Boost")), 5000)
        assertNotNull("no status strip", strip)
        strip!!.click()
        assertTrue("boost dialog missing",
            device.wait(Until.hasObject(By.text("Exact boost percent")), 5000))
        device.pressBack()
    }

    @Test fun pausedNotificationPersists() {
        val song = sample()
        val binder = serviceRule.bindService(
            Intent(context, PlayerService::class.java).setAction("com.blissplayer.music.PLAYER_UI"))
        val svc = (binder as PlayerService.LocalBinder).service().also { service = it }
        main {
            svc.player.setMediaItem(MediaItem.Builder().setMediaId(song.mediaId.toString()).setUri(song.contentUri).build())
            svc.player.repeatMode = Player.REPEAT_MODE_ONE
            svc.player.prepare()
            svc.player.play()
        }
        awaitState("Playback never started") { svc.player.isPlaying }
        main { svc.player.pause() }
        awaitState("Pause never landed") { !svc.player.isPlaying }
        val nm = context.getSystemService(NotificationManager::class.java)
        fun blissNotification() = nm.activeNotifications.any { it.packageName == context.packageName }
        assertTrue("no notification while paused", blissNotification())
        if (scenario == null) scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.close()
        scenario = null
        SystemClock.sleep(3000)
        assertTrue("paused notification disappeared on its own", blissNotification())
    }

    @Test fun pausedTaskRemovalKeepsControlsUsable() {
        val song = sample()
        val binder = serviceRule.bindService(
            Intent(context, PlayerService::class.java).setAction("com.blissplayer.music.PLAYER_UI"))
        val svc = (binder as PlayerService.LocalBinder).service().also { service = it }
        main {
            svc.player.setMediaItem(MediaItem.Builder().setMediaId(song.mediaId.toString()).setUri(song.contentUri).build())
            svc.player.repeatMode = Player.REPEAT_MODE_ONE
            svc.player.prepare()
            svc.player.play()
        }
        awaitState("playback never started") { svc.player.isPlaying }
        main { svc.player.pause() }
        awaitState("pause never landed") { !svc.player.isPlaying }
        main { svc.onTaskRemoved(null) }
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue("notification disappeared on task removal", nm.activeNotifications.any { it.packageName == context.packageName })
        main { svc.player.play() }
        awaitState("playback could not resume after task removal") { svc.player.isPlaying }
    }

    @Test fun notificationStylesDiffer() {
        val song = sample()
        val binder = serviceRule.bindService(
            Intent(context, PlayerService::class.java).setAction("com.blissplayer.music.PLAYER_UI"))
        val svc = (binder as PlayerService.LocalBinder).service().also { service = it }
        main {
            svc.player.setMediaItem(MediaItem.Builder().setMediaId(song.mediaId.toString()).setUri(song.contentUri).build())
            svc.player.repeatMode = Player.REPEAT_MODE_ONE
            svc.player.prepare()
            svc.player.play()
        }
        awaitState("Playback never started") { svc.player.isPlaying }
        val prefs = context.getSharedPreferences("bliss", Context.MODE_PRIVATE)
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue("no playback notification at all",
            nm.activeNotifications.any { it.packageName == context.packageName })
        val shotDir = context.getExternalFilesDir(null)!!
        for ((style, shot) in listOf("Full" to "notif_full.png", "Simple" to "notif_simple.png")) {
            prefs.edit().putString("notif_style", style).commit()
            main { svc.repostNotif() }
            SystemClock.sleep(1500)
            device.wakeUp()
            shell("wm dismiss-keyguard")
            shell("cmd statusbar expand-notifications")
            // Media notifications render in the media carousel: wait for the
            // song title (the app-name string is not exposed there).
            assertTrue("shade never opened for $style",
                device.wait(Until.hasObject(By.textContains("Regression")), 8000))
            device.takeScreenshot(java.io.File(shotDir, shot))
            device.pressBack()
            SystemClock.sleep(500)
        }
        main { svc.player.pause() }
    }

    @Test fun themeCycleAppliesAllFour() {
        // Animation frames can only differ when the system allows them.
        // (This box ships with all animation scales at 0; the theme test
        // then cannot prove motion and skips instead of failing.)
        assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("library never loaded", device.wait(Until.hasObject(By.textContains("songs. Tap a song")), 60000))
        val prefs = context.getSharedPreferences("bliss", Context.MODE_PRIVATE)
        // The Super clock never idles on purpose (ambient drift), so settle
        // with sleeps instead of waitForIdleSync, which would hang forever.
        for (id in listOf("bliss", "dark", "aqua", "super")) {
            prefs.edit().putString("theme", id).commit()
            scenario!!.recreate()
            SystemClock.sleep(2500)
            assertTrue("library gone under $id",
                device.wait(Until.hasObject(By.textContains("songs. Tap a song")), 15000))
            val shotDir = context.getExternalFilesDir(null)!!
            assertTrue("screenshot failed for $id",
                device.takeScreenshot(java.io.File(shotDir, "theme_$id.png")))
        }
        // Super Bliss must visibly animate: two frames 800ms apart differ.
        val shotDir = context.getExternalFilesDir(null)!!
        val a = java.io.File(shotDir, "theme_super_a.png")
        val b = java.io.File(shotDir, "theme_super_b.png")
        assertTrue(device.takeScreenshot(a))
        SystemClock.sleep(800)
        assertTrue(device.takeScreenshot(b))
        assertTrue("super theme looks frozen", !a.readBytes().contentEquals(b.readBytes()))
        prefs.edit().putString("theme", "bliss").commit()
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
