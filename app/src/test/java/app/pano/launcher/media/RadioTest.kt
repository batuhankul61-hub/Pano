package app.pano.launcher.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BYD medya merkezinin radyo bilgisinden istasyon çıkarma. */
class RadioTest {
    private val mc = Radio.MEDIA_CENTER

    @Test fun fmFrekans() {
        val r = Radio.parse(mc, 0, "FM 98.4 MHz", "FM 98.4 MHz")!!
        assertEquals("FM", r.band)
        assertNull(r.name)
        assertEquals("98.4 MHz", r.frequency)
        assertEquals("FM 98.4", r.title)
        assertEquals("98.4 MHz", r.subtitle)
    }

    @Test fun rdsIstasyonAdi() {
        val r = Radio.parse(mc, 0, "RDS Power FM", "100.0 MHz")!!
        assertEquals("Power FM", r.name)
        assertEquals("100.0 MHz", r.frequency)
        assertEquals("Power FM", r.title)
        assertEquals("FM · 100.0 MHz", r.subtitle)
    }

    @Test fun amVeVirgul() {
        val r = Radio.parse(mc, 0, "AM 1008 kHz", "")!!
        assertEquals("AM", r.band)
        assertEquals("1008 kHz", r.frequency)
        assertEquals("87.5 MHz", Radio.parse(mc, 0, "FM 87,5", "")!!.frequency)
    }

    @Test fun dab() {
        val r = Radio.parse(mc, 9, "TRT FM", "TRT Ensemble")!!
        assertEquals("DAB", r.band)
        assertEquals("TRT FM", r.title)
        assertEquals("DAB", r.subtitle)
    }

    @Test fun radyoDegil() {
        // Medya merkezi USB müzik çalıyor (başka mod) ya da mod anahtarı yok.
        assertNull(Radio.parse(mc, 1, "Şarkı", "Sanatçı"))
        assertNull(Radio.parse(mc, null, "FM 98.4 MHz", ""))
        // Başka uygulama "FM" ile başlayan bir şarkı çalıyor.
        assertNull(Radio.parse("com.spotify.music", 0, "FM 98.4", ""))
    }
}
