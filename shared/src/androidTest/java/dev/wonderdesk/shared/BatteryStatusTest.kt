package dev.wonderdesk.shared

import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatteryStatusTest {
    @Test fun labelsReportUnavailableAndActualStates() {
        assertEquals("Charging", batteryStatusLabel(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals("Full", batteryStatusLabel(BatteryManager.BATTERY_STATUS_FULL))
        assertEquals("Unavailable", batteryStatusLabel(-1))
        assertEquals("Not plugged in", batteryPowerSourceLabel(0))
        assertEquals("Unavailable", batteryPowerSourceLabel(-1))
        assertEquals("USB", batteryPowerSourceLabel(BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals("AC / USB", batteryPowerSourceLabel(BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB))
    }
    @Test fun uptimeFormatsWithoutClockDependence() {
        assertEquals("0h 0m", formatUptime(0L))
        assertEquals("0h 1m", formatUptime(90_000L))
        assertEquals("1h 0m", formatUptime(3_600_000L))
        assertEquals("1d 0h 0m", formatUptime(86_400_000L))
        assertEquals("2d 3h 4m", formatUptime(((2 * 24 + 3) * 60 + 4) * 60_000L))
    }
}
