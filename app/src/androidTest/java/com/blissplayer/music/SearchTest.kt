package com.blissplayer.music

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.blissplayer.music.playback.PlayerService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search must actually search: typing filters the library, tapping a
 * result plays it, clearing restores the full list.
 */
@RunWith(AndroidJUnit4::class)
class SearchTest {
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
        try { service?.player?.pause() } catch (_: Exception) {}
        scenario?.close()
        context.stopService(Intent(context, PlayerService::class.java))
    }

    private fun bind(): PlayerService {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val binder = serviceRule.bindService(Intent(context, PlayerService::class.java).setAction("com.blissplayer.music.PLAYER_UI"))
        return (binder as PlayerService.LocalBinder).service().also { service = it }
    }

    /** Fresh installs can show system permission sheets first: allow them
     * while waiting instead of timing out behind them. */
    private fun waitForLibrary(): Boolean {
        val until = android.os.SystemClock.elapsedRealtime() + 90000
        while (android.os.SystemClock.elapsedRealtime() < until) {
            runCatching { device.findObject(By.text("Allow"))?.click() }
            if (device.hasObject(By.textContains("songs. Tap a song"))) return true
            android.os.SystemClock.sleep(2000)
        }
        return device.hasObject(By.textContains("songs. Tap a song"))
    }

    private fun setQuery(q: String) {
        scenario!!.onActivity { a ->
            a.findViewById<LinearLayout>(R.id.searchRow)?.visibility = android.view.View.VISIBLE
            a.findViewById<EditText>(R.id.searchBox)?.setText(q)
        }
    }

    private fun rowCount(): Int {
        var n = -1
        scenario!!.onActivity { a ->
            n = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)?.adapter?.itemCount ?: -1
        }
        return n
    }

    private fun firstTitle(): String {
        var t = ""
        scenario!!.onActivity { a ->
            val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
            val h = rv?.findViewHolderForAdapterPosition(0) as? com.blissplayer.music.ui.LibraryAdapter.Holder
            t = h?.title?.text?.toString() ?: ""
        }
        return t
    }

    @Test fun typingFiltersTheLibrary() {
        bind()
        assertTrue("library never loaded", waitForLibrary())
        val full = rowCount()
        assertTrue("library is empty: $full", full > 100)
        setQuery("kryptonite")
        var filtered = -1
        val until = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < until) {
            filtered = rowCount()
            if (filtered in 1 until full) break
            SystemClock.sleep(500)
        }
        android.util.Log.i("SearchTest", "full=$full filtered=$filtered first=${firstTitle()}")
        assertTrue("search did not filter: full=$full filtered=$filtered", filtered in 1 until full)
        assertTrue("top hit is not Kryptonite: ${firstTitle()}",
            firstTitle().contains("kryptonite", ignoreCase = true))
        // Clearing restores everything.
        scenario!!.onActivity { a ->
            a.findViewById<EditText>(R.id.searchBox)?.setText("")
        }
        var restored = -1
        val until2 = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < until2) {
            restored = rowCount()
            if (restored == full) break
            SystemClock.sleep(500)
        }
        assertEquals("clear did not restore the library: $restored vs $full", full, restored)
    }

    @Test fun magnifierOpensAndClosesSearch() {
        bind()
        assertTrue("library never loaded", waitForLibrary())
        var visible = false
        scenario!!.onActivity { a ->
            a.findViewById<android.widget.ImageButton>(R.id.searchToggle)?.performClick()
        }
        Thread.sleep(1000)
        scenario!!.onActivity { a ->
            visible = a.findViewById<LinearLayout>(R.id.searchRow)?.visibility == android.view.View.VISIBLE
        }
        assertTrue("magnifier did not open search", visible)
    }
}
