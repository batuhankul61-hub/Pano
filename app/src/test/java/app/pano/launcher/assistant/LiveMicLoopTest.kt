package app.pano.launcher.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mikrofon okuma döngüsünün hata davranışı: işlemciyi meşgul etmemeli, takılı kalmamalı. */
class LiveMicLoopTest {
    private val buf = ByteArray(1280)

    /** Sahte saat: her bekleme zamanı ilerletir. */
    private class Clock {
        var t = 1_000L
        val sleeps = mutableListOf<Long>()
        fun sleep(ms: Long) { sleeps += ms; t += ms }
    }

    @Test
    fun surekliHatadaBeklerVeBiter() {
        val clock = Clock()
        var reads = 0
        val end = LiveMic.readLoop(buf, { reads++; -3 /* ERROR_INVALID_OPERATION */ }, { true }, { _, _ -> error("parça gelmemeli") }, { clock.t }, clock::sleep)
        assertEquals(LiveMic.MicEnd.ReadErrors, end)
        assertEquals(10, reads)
        // Her hatalı okumadan sonra (sonuncusu hariç) bekleniyor: boş döngü yok.
        assertEquals(9, clock.sleeps.size)
        assertTrue(clock.sleeps.all { it > 0 })
    }

    @Test
    fun aradaGelenSesHataSayacınıSifirlar() {
        val clock = Clock()
        var i = 0
        var chunks = 0
        // 9 hata, 1 sesli okuma, tekrar… asla 10 üst üste olmaz; 50 turdan sonra durdurulur.
        val end = LiveMic.readLoop(
            buf,
            { b -> if (i++ % 10 == 9) { b.fill(0x40); b.size } else -1 },
            { i < 50 },
            { _, _ -> chunks++ },
            { clock.t },
            clock::sleep,
        )
        assertEquals(LiveMic.MicEnd.Stopped, end)
        assertEquals(5, chunks)
    }

    @Test
    fun uzunTamSessizlikteBiter() {
        val clock = Clock()
        var chunks = 0
        // Her okuma 40 ms sürsün ve sıfır döndürsün.
        val end = LiveMic.readLoop(buf, { b -> clock.t += 40; b.fill(0); b.size }, { true }, { _, _ -> chunks++ }, { clock.t }, clock::sleep)
        assertEquals(LiveMic.MicEnd.Muted, end)
        // ~3 sn boyunca parçalar akmaya devam eder, sonra kesilir.
        assertTrue("parça sayısı: $chunks", chunks in 70..80)
    }

    @Test
    fun gercekSesteKapanmaz() {
        val clock = Clock()
        var n = 0
        val end = LiveMic.readLoop(
            buf,
            { b -> clock.t += 40; b.fill(if (n % 50 == 0) 0x20 else 0); b.size }, // ara sıra ses
            { n++ < 500 }, // 20 sn
            { _, _ -> },
            { clock.t },
            clock::sleep,
        )
        assertEquals(LiveMic.MicEnd.Stopped, end)
    }

    @Test
    fun durdurulincaHemenCikar() {
        val clock = Clock()
        var reads = 0
        val end = LiveMic.readLoop(buf, { reads++; -3 }, { false }, { _, _ -> }, { clock.t }, clock::sleep)
        assertEquals(LiveMic.MicEnd.Stopped, end)
        assertEquals(0, reads)
    }
}
