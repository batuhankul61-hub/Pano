package app.pano.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Alt çubuktaki sıcaklık: klima kartıyla aynı biçim (tam derecede ondalık yok). */
class BarTempTest {
    @Test fun yarimDereceAdimi() {
        assertEquals("22,5°", barTempText(22.5f, 0.5f))
        assertEquals("21°", barTempText(21f, 0.5f))
    }

    @Test fun tamDereceAdimi() {
        assertEquals("22°", barTempText(22f, 1f))
        assertEquals("17°", barTempText(17f, 1f))
    }
}
