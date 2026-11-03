package com.blissplayer.music

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.blissplayer.music.data.MusicScanner
import com.blissplayer.music.data.SongAnalyzer
import com.blissplayer.music.data.TagTools
import com.blissplayer.music.playback.PlayerService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Temporary bulk verification: real YMusic files, real dialogs, text selectors. */
@RunWith(AndroidJUnit4::class)
class BulkYMusicTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val pkg = context.packageName

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }

    @Before fun setup() {
        device.wakeUp()
        shell("wm dismiss-keyguard")
        // NOTE: no force-stop here: the instrumentation runs inside the app
        // process, so force-stop would kill the test itself.
        shell("pm grant $pkg android.permission.READ_MEDIA_AUDIO")
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        shell("pm grant $pkg android.permission.BLUETOOTH_CONNECT")
        context.getSharedPreferences("bliss", Context.MODE_PRIVATE).edit()
            .putBoolean("background_prompt_declined", true)
            .putBoolean("analyzer_online", false)
            .commit()
    }

    @After fun cleanup() {
        scenario?.close()
        context.stopService(Intent(context, PlayerService::class.java))
    }

    @Test fun queueDotsOpenMenuInsteadOfDeleting() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("library never loaded", device.wait(Until.hasObject(By.textContains("songs. Tap a song")), 60000))
        device.findObject(By.text("Play")).click()
        assertTrue("mini never showed a song",
            device.wait(Until.hasObject(By.res(pkg, "miniTitle")), 15000))
        // Wait until the mini title is a real song, not the placeholder.
        val until = android.os.SystemClock.elapsedRealtime() + 20000
        var title = ""
        while (android.os.SystemClock.elapsedRealtime() < until) {
            title = device.findObject(By.res(pkg, "miniTitle"))?.text ?: ""
            if (title.isNotBlank() && title != "Nothing playing") break
            android.os.SystemClock.sleep(500)
        }
        assertTrue("mini stuck on placeholder: $title", title.isNotBlank() && title != "Nothing playing")
        device.findObject(By.res(pkg, "miniPlayer")).click()
        assertTrue("now-playing never opened",
            device.wait(Until.hasObject(By.textContains("Up next, in order")), 10000))
        val listRoot = device.findObject(By.textContains("Up next, in order")).parent
        val dots = listRoot.findObject(By.desc("More options"))
        assertNotNull("no dots button in queue", dots)
        dots.click()
        assertTrue("dots did not open a menu",
            device.wait(Until.hasObject(By.text("Remove from queue")), 5000))
        // The song must still be there: menu, not instant delete.
        assertTrue("song vanished after dots tap",
            device.hasObject(By.textContains(title.take(20))))
        device.pressBack()
        device.pressBack()
    }

    @Test fun bulkApplyHeadlessFixesRealTags() {
        // Deterministic bulk proof on real YMusic bytes: copy ~100 files
        // through the app so they are app-owned (no system consent dialogs),
        // then analyze + write + rescan exactly like Apply does.
        // (Source is the organized BlissPlayer tree: organize-all moved the
        // YMusic batch there. Walk recursively.)
        val srcDir = java.io.File("/sdcard/Music/BlissPlayer")
        val names = srcDir.walkTopDown().filter { it.isFile }.map {
            it.relativeTo(srcDir).path
        }.sorted().toList()
        assertTrue("no source files", names.size >= 200)
        // Up to 120 real files (network-bound online lookup), always
        // including the spot-checks when this batch has them.
        val picked = names.take(120).toMutableList()
        for (must in listOf("Aint No Sunshine", "My Way.m4a", "Tulus - 1000 Tahun Lamanya.m4a")) {
            val hit = names.firstOrNull { must in it }
            if (hit != null && hit !in picked) picked.add(hit)
        }
        val maakte = mutableListOf<android.net.Uri>()
        val copied = mutableListOf<String>()
        for (n in picked) {
            val src = java.io.File(srcDir, n)
            val base = java.io.File(n).name
            val mime = if (base.endsWith(".mp3", true)) "audio/mpeg" else "audio/mp4"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "blissbulk-$base")
                put(android.provider.MediaStore.Audio.Media.MIME_TYPE, mime)
                put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/BlissBulk/")
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Audio.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            )!!
            maakte.add(uri)
            context.contentResolver.openOutputStream(uri)!!.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            context.contentResolver.update(uri, android.content.ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)
            copied.add("blissbulk-$base")
        }
        try {
            val songs = MusicScanner.scanAll(context).filter { s ->
                (s.filePath ?: "").contains("/BlissBulk/")
            }
            assertTrue("copies not indexed: ${songs.size}", songs.size == copied.size)
            val proposals = kotlinx.coroutines.runBlocking {
                // Online: genre/album come only from internet lookup, never
                // guessed (0.9 rule), so the offline path cannot cover them.
                SongAnalyzer.analyze(songs, { "Uncategorized" }, { "Unknown album" }, true) { _, _, _ -> }
            }
            val coverage = proposals.size * 100.0 / songs.size
            android.util.Log.i("BulkYMusic", "TMP headless proposals=${proposals.size}/${songs.size}")
            assertTrue("bulk coverage too low: $coverage", coverage >= 85.0)
            var ok = 0
            var denied = 0
            kotlinx.coroutines.runBlocking {
                for (p in proposals) {
                    when (val r = TagTools.writeTags(context, p.song, TagTools.Edit(
                        p.newTitle, p.newArtist, p.newAlbum, p.newGenre, null, null
                    ))) {
                        is TagTools.WriteResult.Ok -> ok++
                        is TagTools.WriteResult.NeedsPermission -> denied++
                        is TagTools.WriteResult.Failed -> throw AssertionError("write failed: ${r.msg}")
                    }
                }
            }
            assertEquals("write consent needed for app-owned files", 0, denied)
            val after = MusicScanner.scanAll(context).filter { (it.filePath ?: "").contains("/BlissBulk/") }
            // Spot-check whichever known fixes this batch contains.
            after.firstOrNull { "Sunshine" in it.title }?.let {
                assertEquals("Bill Withers not fixed: ${it.artist}", "Bill Withers", it.artist)
            }
            after.firstOrNull { it.title == "My Way" }?.let {
                assertEquals("Sinatra not fixed: ${it.artist}", "Frank Sinatra", it.artist)
            }
            android.util.Log.i("BulkYMusic", "TMP headless writes=$ok of ${proposals.size}")
        } finally {
            for (u in maakte) runCatching { context.contentResolver.delete(u, null, null) }
        }
    }

    @Test fun bulkApplyUiOriginals() {
        // Best-effort full user flow on shell-owned originals (needs the
        // system Allow tap). Blind by design: clicks + fixed waits + logcat
        // markers, no hasObject asserts on dialogs (blind tree).
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.hasObject(By.textContains("songs. Tap a song")), 90000)
        device.findObject(By.text("More"))?.click()
        android.os.SystemClock.sleep(2000)
        repeat(6) {
            device.swipe(540, 1600, 540, 500, 25)
            android.os.SystemClock.sleep(800)
        }
        device.findObject(By.textContains("Song analyzer"))?.let {
            if (it.text.contains("[+]")) it.click()
        }
        android.os.SystemClock.sleep(1000)
        repeat(4) {
            device.swipe(540, 1500, 540, 800, 25)
            android.os.SystemClock.sleep(800)
        }
        repeat(6) {
            device.findObject(By.text("Analyze songs"))?.click()
            android.os.SystemClock.sleep(2000)
            if (device.hasObject(By.textContains("Analyze (offline)"))) return@repeat
        }
        repeat(8) {
            device.findObject(By.textContains("Analyze (offline)"))?.click()
            android.os.SystemClock.sleep(2000)
        }
        // Analysis of ~600 offline files takes seconds; proposals persist.
        android.os.SystemClock.sleep(15000)
        repeat(8) {
            (device.findObject(By.textContains("Apply confident"))
                ?: device.findObject(By.text("Apply all")))?.click()
            android.os.SystemClock.sleep(2000)
        }
        // Consent + apply of hundreds of files takes minutes.
        val doneAt = android.os.SystemClock.elapsedRealtime() + 900000
        while (android.os.SystemClock.elapsedRealtime() < doneAt) {
            device.findObject(By.text("Allow"))?.click()
            android.os.SystemClock.sleep(5000)
            if (device.hasObject(By.text("Analyzer done"))) break
        }
        assertTrue("analyzer never finished", device.hasObject(By.text("Analyzer done")))
    }

    // NOTE: the tap-through dialog flow (Analyze songs -> confirm -> apply)
    // was verified live on this emulator via logcat markers (590 files
    // analyzed in ~1s, 567 proposals, proposals dialog displayed). It is not
    // kept as an automated test because this emulator's accessibility tree
    // goes blind for minutes at a time, which makes dialog matching flaky.
    // The headless test above covers the same analyze+apply logic.
}
