package com.blissplayer.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DesktopMainTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun secondLaunchRefusesWhileFirstHoldsLock() {
        val lock = temporaryFolder.root.resolve("app.lock")
        assertTrue("first instance takes the lock", DesktopMain.singleInstance(lock))
        assertFalse("second instance must exit, not add a tray icon", DesktopMain.singleInstance(lock))
    }
}
