package app.pano.launcher.vehicle

import app.pano.launcher.system.QuickControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Koltuk / direksiyon ısıtma ölçekleri (firmware: BYDAutoSettingDevice.SEAT_HEATING_*, STEERING_WHEEL_HEATING_*). */
class BydComfortTest {
    @Test fun koltukKademesi() {
        // 1 kapalı, 2 düşük, 3 yüksek, 4 üç kademeli koltukta en yüksek.
        assertEquals(0, BydComfort.seatLevel(1))
        assertEquals(1, BydComfort.seatLevel(2))
        assertEquals(2, BydComfort.seatLevel(3))
        assertEquals(3, BydComfort.seatLevel(4))
        assertNull(BydComfort.seatLevel(0))
        assertNull(BydComfort.seatLevel(255))
        assertNull(BydComfort.seatLevel(null))
        for (level in 0..3) assertEquals(level, BydComfort.seatLevel(BydComfort.seatRaw(level)))
    }

    @Test fun direksiyon() {
        assertEquals(true, BydComfort.steeringOn(2))
        assertEquals(false, BydComfort.steeringOn(1))
        assertNull(BydComfort.steeringOn(0))
        assertNull(BydComfort.steeringOn(65535))
    }

    @Test fun varMi() {
        assertEquals(true, BydComfort.present(1))
        assertEquals(true, BydComfort.present(3))
        assertEquals(false, BydComfort.present(0))
        assertNull(BydComfort.present(null))
        // "Yok" diyen sinyal okunabilen durumu da gizler; bilinmiyorsa durum yeter.
        assertNull(BydComfort.visible(1, false))
        assertEquals(1, BydComfort.visible(1, null))
        assertNull(BydComfort.visible(null, true))
    }

    @Test fun bosDurum() {
        assertTrue(BydComfort.State().isEmpty)
    }

    @Test fun parlaklikYuzdesi() {
        assertEquals(0, QuickControls.percentOf(10, 10, 255))
        assertEquals(100, QuickControls.percentOf(255, 10, 255))
        assertEquals(10, QuickControls.valueOf(0, 10, 255))
        assertEquals(255, QuickControls.valueOf(100, 10, 255))
        for (p in 0..100) assertEquals(p, QuickControls.percentOf(QuickControls.valueOf(p, 10, 255), 10, 255))
    }

    @Test fun ssid() {
        assertEquals("Ev", QuickControls.cleanSsid("\"Ev\""))
        assertNull(QuickControls.cleanSsid("<unknown ssid>"))
        assertNull(QuickControls.cleanSsid(""))
        assertNull(QuickControls.cleanSsid(null))
    }
}
