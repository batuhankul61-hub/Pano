package app.pano.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BYD üfleme yönü kodları (firmware: BYDAutoAcDevice.AC_WINDMODE_*). */
class AirModeTest {
    @Test fun kodlar() {
        assertEquals(AirMode.Face, AirMode.of(1))
        assertEquals(AirMode.Feet, AirMode.of(3))
        assertEquals(AirMode.Windshield, AirMode.of(5))
        assertEquals(AirMode.FaceWindshield, AirMode.of(7))
        assertNull(AirMode.of(0))
        assertNull(AirMode.of(8))
        assertNull(AirMode.of(null))
    }

    @Test fun modSayisi() {
        // BYD: 1 = 5 mod, 2 = 7 mod, başka = bilinmiyor (5 varsayılır).
        assertEquals(5, AirMode.countFromByd(1))
        assertEquals(7, AirMode.countFromByd(2))
        assertEquals(5, AirMode.countFromByd(0))
        assertEquals(5, AirMode.countFromByd(null))
        assertEquals(listOf(1, 2, 3, 4, 5), AirMode.available(5).map { it.code })
        assertEquals(7, AirMode.available(7).size)
    }
}
