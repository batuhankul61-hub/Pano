package app.pano.launcher.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Open-Meteo cevabından anlık hava ve günlük tahminin okunması. */
class WeatherParseTest {
    private val json = """
        {"current":{"temperature_2m":16.4,"weather_code":80,"is_day":1},
         "daily":{"time":["2026-11-23","2026-11-24","2026-11-25"],
                  "weather_code":[80,61,null],
                  "temperature_2m_max":[20.1,17.6,15.0],
                  "temperature_2m_min":[12.0,10.4,9.0],
                  "precipitation_probability_max":[70,null,10]}}
    """.trimIndent()

    @Test fun gunlukTahminOkunur() {
        val w = WeatherHub.parse(json)
        assertEquals(20.1, w.highC, 0.01)
        assertEquals("Hafif sağanak", w.description)
        // Üçüncü günün kodu eksik: o gün atlanır.
        assertEquals(listOf("2026-11-23", "2026-11-24"), w.days.map { it.date })
        assertEquals("Hafif yağmur", w.days[1].description)
        assertEquals(17.6, w.days[1].highC, 0.01)
        assertEquals(70, w.days[0].precipitationChance)
        assertNull(w.days[1].precipitationChance)
    }

    @Test fun eskiBicimdeTahminYoksaBosListe() {
        val old = """{"current":{"temperature_2m":5,"weather_code":0},"daily":{"temperature_2m_max":[8],"temperature_2m_min":[1]}}"""
        val w = WeatherHub.parse(old)
        assertEquals(8.0, w.highC, 0.01)
        assertTrue(w.days.isEmpty())
    }
}
