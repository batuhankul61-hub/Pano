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
        // ~MUTE_LIMIT_MS boyunca parçalar akmaya devam eder, sonra kesilir.
        val expected = (LiveMic.MUTE_LIMIT_MS / 40).toInt()
        assertTrue("parça sayısı: $chunks", chunks in expected - 5..expected + 5)
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

/** Çok kanallı mikrofondan en güçlü kanalın seçilmesi (araçta 5 kanallı dizi). */
class PickLoudestTest {
    /** [n] kanal, [frames] kare; kanal [ch]'nin örneği [value(ch, f)]. */
    private fun pcm(n: Int, frames: Int, value: (Int, Int) -> Int): ByteArray {
        val b = ByteArray(n * frames * 2)
        for (f in 0 until frames) for (ch in 0 until n) {
            val s = value(ch, f)
            b[(f * n + ch) * 2] = s.toByte(); b[(f * n + ch) * 2 + 1] = (s shr 8).toByte()
        }
        return b
    }

    @Test fun sifirKanalYerineSesliKanal() {
        val n = 5; val frames = 640
        val energy = FloatArray(n)
        val out = ByteArray(frames * 2)
        // 0. kanal sıfır (araçta tek kanal istenince gelen buydu), 2. kanalda ses var.
        val multi = pcm(n, frames) { ch, f -> if (ch == 2) (if (f % 2 == 0) 3000 else -3000) else 0 }
        var chosen = -1
        repeat(5) { chosen = LiveMic.pickLoudest(multi, n, frames, out, energy) }
        assertEquals(2, chosen)
        assertEquals(3000, (out[0].toInt() and 0xff) or (out[1].toInt() shl 8))
    }

    @Test fun tekParcalikGurultuKanalDegistirmez() {
        val n = 3; val frames = 640
        val energy = FloatArray(n)
        val out = ByteArray(frames * 2)
        val speech = pcm(n, frames) { ch, f -> if (ch == 1) (if (f % 2 == 0) 2000 else -2000) else 100 }
        repeat(50) { LiveMic.pickLoudest(speech, n, frames, out, energy) } // 2 sn konuşma
        // Bir parçalık yüksek tıkırtı 0. kanalda: ortalama nedeniyle seçim 1. kanalda kalır.
        val click = pcm(n, frames) { ch, f -> if (ch == 0) (if (f % 2 == 0) 7000 else -7000) else 100 }
        assertEquals(1, LiveMic.pickLoudest(click, n, frames, out, energy))
    }
}
