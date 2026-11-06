package com.blissplayer.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopMainTest {
    @Test fun secondLaunchRefusesWhileFirstHoldsLock() {
        assertTrue("first instance takes the lock", DesktopMain.singleInstance())
        assertFalse("second instance must exit, not add a tray icon", DesktopMain.singleInstance())
    }
}
