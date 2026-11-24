package app.pano.launcher.assistant

import app.pano.launcher.assistant.TurnDetector.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Yerel konuşma algılama: başlangıç, bitiş, gürültüye uyum. Her parça 40 ms. */
class TurnDetectorTest {
    private var t = 0L

    /** [levels] parçalarını besler, dönen başlangıç/bitiş olaylarını parça sırasıyla verir. */
    private fun TurnDetector.run(vararg levels: Pair<Float, Int>): List<Pair<Action, Long>> {
        val out = mutableListOf<Pair<Action, Long>>()
        for ((level, count) in levels) repeat(count) {
            t += 40
            val a = feed(level, t)
            if (a != Action.None) out += a to t
        }
        return out
    }

    @Test fun sessizOdadaKonusmaBaslarVeBiter() {
        val d = TurnDetector()
        val events = d.run(0.002f to 50, 0.15f to 50, 0.002f to 40)
        assertEquals(listOf(Action.Start, Action.End), events.map { it.first })
        // Başlangıç ikinci yüksek parçada, bitiş 800 ms sessizlikten sonra.
        assertEquals(50 * 40L + 80, events[0].second)
        assertEquals((50 + 50) * 40L + 800, events[1].second)
        assertFalse(d.speaking)
    }

    @Test fun tekTikirtiKonusmaSayilmaz() {
        val d = TurnDetector()
        val events = d.run(0.002f to 20, 0.3f to 1, 0.002f to 20, 0.3f to 1, 0.002f to 20)
        assertTrue(events.isEmpty())
    }

    @Test fun kelimeArasiKisaDuraklamaTuruBitirmez() {
        val d = TurnDetector()
        // "Yarın ... hava nasıl": 400 ms duraklama.
        val events = d.run(0.002f to 30, 0.12f to 20, 0.002f to 10, 0.12f to 20, 0.002f to 30)
        assertEquals(listOf(Action.Start, Action.End), events.map { it.first })
    }

    @Test fun gurultuluAractaEsikYukselir() {
        val d = TurnDetector()
        // Yol gürültüsü 0,04: sessiz oda eşiği (0,02) aşılır ama konuşma sayılmaz.
        assertTrue(d.run(0.04f to 100).isEmpty())
        assertTrue(d.startThreshold() > 0.1f)
        // Sürücünün sesi gürültünün çok üstünde: tur başlar; konuşma bitince gürültü sürse de tur kapanır.
        val events = d.run(0.25f to 40, 0.04f to 30)
        assertEquals(listOf(Action.Start, Action.End), events.map { it.first })
    }

    @Test fun hicSusmayanGurultudeTurEnGec15SnSonraKapanir() {
        val d = TurnDetector()
        val events = d.run(0.002f to 10, 0.2f to 500)
        assertEquals(listOf(Action.Start, Action.End), events.map { it.first })
        assertEquals(15_000L, events[1].second - events[0].second)
    }

    @Test fun resetYarimKalanKonusmayiUnutur() {
        val d = TurnDetector()
        d.run(0.002f to 10, 0.2f to 5)
        assertTrue(d.speaking)
        d.reset()
        assertFalse(d.speaking)
        assertEquals(listOf(Action.Start), d.run(0.2f to 2).map { it.first })
    }
}
