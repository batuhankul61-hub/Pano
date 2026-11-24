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

/** Üç çıkış tuşuyla (yüz, ayak, cam) mod seçimi. */
class AirModeToggleTest {

    @Test fun yediModluAractaTumKombinasyonlar() {
        assertEquals(AirMode.FaceFeet, AirMode.Feet.toggle(AirMode.Outlet.Face, 7))
        assertEquals(AirMode.Face, AirMode.FaceFeet.toggle(AirMode.Outlet.Feet, 7))
        assertEquals(AirMode.FaceWindshield, AirMode.Face.toggle(AirMode.Outlet.Windshield, 7))
        assertEquals(AirMode.FaceFootWindshield, AirMode.FaceFeet.toggle(AirMode.Outlet.Windshield, 7))
        assertEquals(AirMode.FeetWindshield, AirMode.Feet.toggle(AirMode.Outlet.Windshield, 7))
    }

    @Test fun sonAcikCikisKapanmaz() {
        assertEquals(AirMode.Feet, AirMode.Feet.toggle(AirMode.Outlet.Feet, 7))
        assertEquals(AirMode.Windshield, AirMode.Windshield.toggle(AirMode.Outlet.Windshield, 5))
    }

    @Test fun besModluAractaYuzVeCamBirlikteOlmaz() {
        // Yüz açıkken cam açılınca yüz kapanır; cam açıkken yüz açılınca cam kapanır.
        assertEquals(AirMode.Windshield, AirMode.Face.toggle(AirMode.Outlet.Windshield, 5))
        assertEquals(AirMode.FeetWindshield, AirMode.FaceFeet.toggle(AirMode.Outlet.Windshield, 5))
        assertEquals(AirMode.FaceFeet, AirMode.FeetWindshield.toggle(AirMode.Outlet.Face, 5))
        // Sonuç her zaman 5 modlu araçta geçerli bir mod.
        for (m in AirMode.available(5)) for (o in AirMode.Outlet.entries) {
            val r = m.toggle(o, 5)
            assert(r.code in 1..5) { "$m + $o → $r" }
        }
    }
}
