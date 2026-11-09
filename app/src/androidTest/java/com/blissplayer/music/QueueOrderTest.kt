package com.blissplayer.music

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live proof that "Play next" inserts directly after the current song.
 * Taps a later row's dots, chooses Play next, opens the queue and asserts
 * the tapped song sits at index 1 behind the playing song.
 */
@RunWith(AndroidJUnit4::class)
class QueueOrderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val pkg: String get() = context.packageName
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setup() {
        device.wakeUp()
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
        instrumentation.uiAutomation.executeShellCommand("pm grant $pkg $permission").close()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS").close()
        if (android.os.Build.VERSION.SDK_INT >= 31) instrumentation.uiAutomation.executeShellCommand("pm grant $pkg android.permission.BLUETOOTH_CONNECT").close()
        context.getSharedPreferences("bliss", Context.MODE_PRIVATE).edit().putBoolean("background_prompt_declined", true).commit()
    }

    @After fun cleanup() {
        scenario?.close()
    }

    @Test fun playNextLandsDirectlyAfterCurrentSong() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("library never loaded", device.wait(Until.hasObject(By.textContains("songs. Tap a song")), 60000))
        device.findObject(By.text("Play")).click()
        val mini = device.wait(Until.findObject(By.res(pkg, "miniTitle")), 15000)
        assertNotNull("no mini player", mini)
        val until = android.os.SystemClock.elapsedRealtime() + 20000
        var current = ""
        while (android.os.SystemClock.elapsedRealtime() < until) {
            current = device.findObject(By.res(pkg, "miniTitle"))?.text ?: ""
            if (current.isNotBlank() && current != "Nothing playing") break
            android.os.SystemClock.sleep(500)
        }
        assertTrue("mini stuck on placeholder", current.isNotBlank() && current != "Nothing playing")

        // Dots of a song row: read the title from the tapped row itself,
        // not from a tree-wide guess (background clocks look like titles).
        val dots = device.wait(Until.findObjects(By.desc("More options")), 10000)
        assertTrue("no dots buttons", dots.size >= 3)
        val row = dots[2].parent
        assertNotNull("dots have no row", row)
        val targetTitle = row!!.findObjects(By.clazz("android.widget.TextView"))
            .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
            .firstOrNull()
        assertNotNull("could not read tapped song title", targetTitle)
        dots[2].click()
        // The menu dialog opens for that song.
        val target = device.wait(Until.findObject(By.text("Play next")), 5000)
        assertNotNull("no Play next item", target)
        target.click()
        // The menu closes and the song lands right after the current one.
        // The queue list itself is the proof (toasts are not reliably visible
        // to the test harness), so open Now Playing and read the true order.
        android.os.SystemClock.sleep(1500)

        // Open Now Playing and read the true queue order.
        device.findObject(By.res(pkg, "miniPlayer")).click()
        val upNext = device.wait(Until.hasObject(By.textContains("Up next, in order")), 10000)
        assertTrue("now playing never opened", upNext)
        val listRoot = device.findObject(By.textContains("Up next, in order")).parent
        // Wait for the tapped song to appear inside the queue list.
        assertTrue("tapped song never reached the queue", device.wait(Until.hasObject(By.textContains(targetTitle!!.take(24))), 10000))
        val rows = listRoot.findObjects(By.clazz("androidx.recyclerview.widget.RecyclerView"))
        assertTrue("no queue list", rows.isNotEmpty())
        val cells = rows[0].children.mapNotNull { cell ->
            cell.findObject(By.clazz("android.widget.TextView"))?.text
        }
        android.util.Log.i("QueueOrder", "queue head=${cells.take(3)} target=$targetTitle")
        assertTrue("queue too short: $cells", cells.size >= 2)
        assertTrue("current song not first: ${cells[0]} vs $current", cells[0].contains(current.take(20)))
        assertTrue("Play next did not land second: ${cells[1]} vs $targetTitle", cells[1].contains(targetTitle.take(20)))
        device.pressBack()
        device.pressBack()
    }
}
