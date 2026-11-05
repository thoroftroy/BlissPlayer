package com.blissplayer.music

import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.blissplayer.music.ui.theme.MobileTheme
import com.blissplayer.music.ui.theme.MobileThemeController
import com.blissplayer.music.ui.theme.ThemeFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeTypographyTest {
    @Test fun themesHaveDistinctCachedFacesAndPreserveRetroTextStyling() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val faces = MobileTheme.entries.map { ThemeFonts.face(context, it) }
            assertEquals("each theme should have its own normal face", 4, faces.toSet().size)
            MobileTheme.entries.forEach { theme ->
                val normal = ThemeFonts.face(context, theme)
                val bold = ThemeFonts.face(context, theme, true)
                assertEquals(normal, ThemeFonts.face(context, theme))
                assertNotEquals("bold must differ in $theme", normal, bold)
                assertTrue("bold must survive a second styling pass in $theme", bold.isBold)
                val text = TextView(context).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    setShadowLayer(2f, 1f, 1f, Color.BLACK)
                }
                ThemeFonts.apply(text, theme, true)
                assertEquals(bold, text.typeface)
                if (!theme.modern) assertEquals(2f, text.shadowRadius, 0f)
            }
        }
    }

    @Test fun retroClockSleepsAfterClickAndRestartsForTheNextClick() {
        assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("bliss", android.content.Context.MODE_PRIVATE)
        val oldTheme = prefs.getString("theme", null)
        val oldMotion = prefs.getString("theme_motion", null)
        val oldClick = prefs.getString("click_fx", null)
        val oldDeclined = prefs.getBoolean("background_prompt_declined", false)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        UiDevice.getInstance(instrumentation).wakeUp()
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
            }
        }
        shell("wm dismiss-keyguard")
        shell("pm grant ${context.packageName} ${if (android.os.Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"}")
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        prefs.edit().putString("theme", "bliss").putString("theme_motion", "full")
            .putString("click_fx", "full").putBoolean("background_prompt_declined", true).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // Exercise a dedicated controller so startup permission windows and
                // ActivityScenario lifecycle changes cannot pause the clock under test.
                lateinit var renderer: MobileThemeController
                scenario.onActivity { activity ->
                    renderer = MobileThemeController(activity, MobileTheme.BLISS)
                    renderer.resume()
                }
                fun tapAndCheck() {
                    scenario.onActivity { activity ->
                        assertFalse("retro clock should be idle before a tap", renderer.clockRunning)
                        val time = SystemClock.uptimeMillis()
                        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
                        val up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, 20f, 20f, 0)
                        activity.window.callback.dispatchTouchEvent(down)
                        activity.window.callback.dispatchTouchEvent(up)
                        down.recycle()
                        up.recycle()
                        assertTrue("tap should wake the retro clock: interactions=${renderer.interactionCount}, " +
                            "motion=${renderer.motionSetting}, focused=${activity.hasWindowFocus()}, " +
                            "callback=${activity.window.callback.javaClass.name}", renderer.clockRunning)
                    }
                    SystemClock.sleep(2000)
                    scenario.onActivity {
                        assertFalse("clock should stop after the burst", renderer.clockRunning)
                    }
                }
                try {
                    tapAndCheck()
                    tapAndCheck()
                } finally {
                    scenario.onActivity { renderer.close() }
                }
            }
        } finally {
            prefs.edit().putString("theme", oldTheme).putString("theme_motion", oldMotion)
                .putString("click_fx", oldClick).putBoolean("background_prompt_declined", oldDeclined).commit()
        }
    }
}
