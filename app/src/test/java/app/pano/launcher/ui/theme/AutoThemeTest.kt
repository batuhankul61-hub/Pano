package app.pano.launcher.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AutoThemeTest {
    private val istanbul = 41.0082 to 28.9784
    private val trt = ZoneId.of("Europe/Istanbul")

    /** Beklenen saatle en çok 2 dakika fark olsun (yayınlanmış tablolar dakikaya yuvarlı). */
    private fun assertNear(expected: String, actual: java.time.Instant?, date: LocalDate) {
        val want = LocalDateTime.of(date, java.time.LocalTime.parse(expected)).atZone(trt).toInstant()
        val diff = Duration.between(want, actual!!).abs().toMinutes()
        assertTrue("beklenen $expected, bulunan ${actual.atZone(trt).toLocalTime()}", diff <= 2)
    }

    @Test fun istanbulYazGunDonumu() {
        val d = LocalDate.of(2026, 6, 21)
        val s = SunCalc.sunTimes(d, istanbul.first, istanbul.second)
        println("İstanbul 21 Haziran: doğum ${s.sunrise!!.atZone(trt).toLocalTime()} batım ${s.sunset!!.atZone(trt).toLocalTime()}")
        assertNear("05:32", s.sunrise, d)
        assertNear("20:39", s.sunset, d)
    }

    @Test fun istanbulKisGunDonumu() {
        val d = LocalDate.of(2026, 12, 21)
        val s = SunCalc.sunTimes(d, istanbul.first, istanbul.second)
        println("İstanbul 21 Aralık: doğum ${s.sunrise!!.atZone(trt).toLocalTime()} batım ${s.sunset!!.atZone(trt).toLocalTime()}")
        assertNear("08:23", s.sunrise, d)
        assertNear("17:39", s.sunset, d)
    }

    @Test fun konumaGoreKarar() {
        fun at(t: String) = LocalDateTime.parse(t).atZone(trt).toInstant()
        val noon = AutoTheme.decide(at("2026-12-21T12:00"), trt, istanbul, null, null, systemDark = true)
        assertFalse(noon.dark)
        assertTrue(noon.reason, noon.reason.startsWith("Gün batımı 17:3"))
        val evening = AutoTheme.decide(at("2026-12-21T19:00"), trt, istanbul, null, null, systemDark = false)
        assertTrue(evening.dark)
        assertTrue(evening.reason, evening.reason.startsWith("Gün doğumu 08:2"))
        val dawn = AutoTheme.decide(at("2026-12-21T06:00"), trt, istanbul, null, null, systemDark = false)
        assertTrue(dawn.dark)
    }

    @Test fun konumYoksaHavaSonraSistem() {
        val now = java.time.Instant.parse("2026-12-21T09:00:00Z")
        val fresh = AutoTheme.decide(now, trt, null, weatherIsDay = false, weatherFetchedAt = now.toEpochMilli() - 60_000, systemDark = false)
        assertTrue(fresh.dark)
        val stale = AutoTheme.decide(now, trt, null, weatherIsDay = false, weatherFetchedAt = now.toEpochMilli() - 3_600_000, systemDark = false)
        assertFalse(stale.dark)
        assertEquals("Sistemin gece moduna göre", stale.reason)
    }

    @Test fun kutupGecesi() {
        val s = SunCalc.sunTimes(LocalDate.of(2026, 12, 21), 78.22, 15.65) // Longyearbyen
        assertEquals(false, s.alwaysUp)
        val m = SunCalc.sunTimes(LocalDate.of(2026, 6, 21), 78.22, 15.65)
        assertEquals(true, m.alwaysUp)
    }
}
