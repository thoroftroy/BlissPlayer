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
 * Live proof for the delete-after-Allow bug: granting the system delete
 * consent must delete the song AND remove its row immediately, never claim
 * denial while leaving a stale row until the tab is revisited.
 */
@RunWith(AndroidJUnit4::class)
class DeleteFlowTest {
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

    @Test fun deleteAfterAllowRemovesRowImmediately() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("library never loaded", device.wait(Until.hasObject(By.textContains("songs. Tap a song")), 60000))
        // Target the first song row's dots.
        val dots = device.wait(Until.findObjects(By.desc("More options")), 10000)
        assertTrue("no dots buttons", dots.isNotEmpty())
        // Read the first row's title so we can prove it vanishes.
        val firstRowTitle = dots[0].parent?.parent
            ?.findObjects(By.clazz("android.widget.TextView"))
            ?.mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotBlank() } }
            ?.firstOrNull { !it.contains("|") && !it.contains("songs.") }
        assertNotNull("could not read first song title", firstRowTitle)
        val victim = firstRowTitle!!.take(24)
        android.util.Log.i("DeleteFlow", "victim=$firstRowTitle")
        dots[0].click()
        assertTrue("no menu", device.wait(Until.hasObject(By.text("Delete song and file")), 5000))
        device.findObject(By.text("Delete song and file")).click()
        // Confirm dialog inside the app.
        assertTrue("no Delete confirm", device.wait(Until.hasObject(By.text("Delete this song?")), 5000))
        device.findObject(By.text("Delete")).click()
        // System consent dialog (permissioncontroller): Allow it.
        // Either the delete succeeds outright (app-owned) or the system asks.
        val asked = device.wait(Until.hasObject(By.text("Allow")), 10000)
        if (asked) {
            device.findObject(By.text("Allow")).click()
        }
        // The row must vanish promptly: no stale row, no false denial.
        assertTrue(
            "deleted song still listed after Allow",
            device.wait(Until.gone(By.textContains(victim)), 15000)
        )
        assertFalse(
            "claimed denial after Allow",
            device.hasObject(By.textContains("Permission was not granted")) ||
                device.hasObject(By.textContains("Could not delete"))
        )
        android.util.Log.i("DeleteFlow", "deleted and row gone (asked=$asked): $victim")
    }
}
