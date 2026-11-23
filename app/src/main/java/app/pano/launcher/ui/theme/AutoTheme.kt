package app.pano.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import app.pano.launcher.navigation.PanoNav
import app.pano.launcher.weather.WeatherHub
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/*
 * "Otomatik" temanın kararı. Sıra:
 *  1. Konum biliniyorsa gün doğumu / batımı cihazda hesaplanır (NOAA, ağ gerekmez).
 *  2. Yoksa Open-Meteo'nun `is_day` alanı (WeatherHub), yeterince tazeyse.
 *  3. O da yoksa sistemin gece modu.
 * BYD veri yolunda far / ışık sensörü sinyali bilinmiyor (BydBus ve ESMER-UI'de alan adı yok),
 * bu yüzden araç sinyali kullanılmıyor.
 */

/** Otomatik temanın kararı ve Ayarlar'da gösterilecek gerekçe. */
data class AutoThemeDecision(val dark: Boolean, val reason: String)

/** PanoTheme'in o an verdiği otomatik karar; Ayarlar açıklaması buradan okur. */
val LocalAutoTheme = staticCompositionLocalOf { AutoThemeDecision(dark = false, reason = "") }

/** Bir gün için gün doğumu ve batımı. Kutup gecesi/gündüzünde ikisi de null. */
data class SunTimes(
    val sunrise: Instant?,
    val sunset: Instant?,
    /** Güneş gün boyu batmıyorsa true, hiç doğmuyorsa false; normal günde null. */
    val alwaysUp: Boolean? = null,
)

/** NOAA güneş hesabı (Meeus'tan sadeleştirilmiş; dakika düzeyinde doğru). */
object SunCalc {
    /** Gün doğumu/batımı için güneş merkezinin ufkun altındaki açısı (kırılma + yarıçap). */
    private const val ZENITH = 90.833

    fun sunTimes(date: LocalDate, lat: Double, lon: Double): SunTimes {
        // Önce kabaca öğlen için, sonra bulunan öğlen ve olay anlarında yeniden hesapla.
        val dayJd = date.toEpochDay() + 2440587.5 // UTC gece yarısı
        val noon = solarNoonMinutes(dayJd + 0.5 - lon / 360.0, lon)
        val noonRefined = solarNoonMinutes(dayJd + noon / 1440.0, lon)
        val rise = eventMinutes(dayJd, noonRefined, lat, lon, rising = true)
        val set = eventMinutes(dayJd, noonRefined, lat, lon, rising = false)
        if (rise == null || set == null) {
            val ha = hourAngleArg(dayJd + noonRefined / 1440.0, lat)
            return SunTimes(null, null, alwaysUp = ha < -1.0)
        }
        fun toInstant(min: Double) = Instant.ofEpochMilli(((date.toEpochDay() * 1440.0 + min) * 60_000.0).toLong())
        return SunTimes(toInstant(rise), toInstant(set))
    }

    /** Olayın UTC gece yarısından itibaren dakikası; güneş doğmuyor/batmıyorsa null. */
    private fun eventMinutes(dayJd: Double, noon: Double, lat: Double, lon: Double, rising: Boolean): Double? {
        var minutes = noon
        repeat(2) {
            val jd = dayJd + minutes / 1440.0
            val arg = hourAngleArg(jd, lat)
            if (arg !in -1.0..1.0) return null
            val ha = Math.toDegrees(acos(arg))
            val eq = equationOfTime(jd)
            minutes = 720 - 4 * lon - eq + (if (rising) -4 * ha else 4 * ha)
        }
        return minutes
    }

    private fun solarNoonMinutes(jd: Double, lon: Double) = 720 - 4 * lon - equationOfTime(jd)

    private fun hourAngleArg(jd: Double, lat: Double): Double {
        val decl = declination(jd)
        val latR = Math.toRadians(lat)
        return cos(Math.toRadians(ZENITH)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
    }

    private fun century(jd: Double) = (jd - 2451545.0) / 36525.0

    private fun meanLongitude(t: Double) = ((280.46646 + t * (36000.76983 + t * 0.0003032)) % 360 + 360) % 360
    private fun meanAnomaly(t: Double) = 357.52911 + t * (35999.05029 - 0.0001537 * t)
    private fun eccentricity(t: Double) = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    private fun obliquity(t: Double): Double {
        val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        return eps0 + 0.00256 * cos(Math.toRadians(125.04 - 1934.136 * t))
    }

    /** Güneşin dik açıklığı (radyan). */
    private fun declination(jd: Double): Double {
        val t = century(jd)
        val m = Math.toRadians(meanAnomaly(t))
        val center = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        val lambda = Math.toRadians(meanLongitude(t) + center - 0.00569 - 0.00478 * sin(omega))
        return asin(sin(Math.toRadians(obliquity(t))) * sin(lambda))
    }

    /** Zaman denklemi (dakika). */
    private fun equationOfTime(jd: Double): Double {
        val t = century(jd)
        val l0 = Math.toRadians(meanLongitude(t))
        val m = Math.toRadians(meanAnomaly(t))
        val e = eccentricity(t)
        val y = tan(Math.toRadians(obliquity(t)) / 2).let { it * it }
        val eq = y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) -
            0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m)
        return 4 * Math.toDegrees(eq)
    }
}

object AutoTheme {
    /** Hava verisindeki `isDay` bundan eskiyse güvenilmez (gün batımını kaçırmış olabilir). */
    private const val WEATHER_MAX_AGE_MS = 30 * 60 * 1000L
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    /** Saf karar fonksiyonu: test edilebilsin diye her girdi dışarıdan verilir. */
    fun decide(
        now: Instant,
        zone: ZoneId,
        location: Pair<Double, Double>?,
        weatherIsDay: Boolean?,
        weatherFetchedAt: Long?,
        systemDark: Boolean,
    ): AutoThemeDecision {
        if (location != null) {
            val (lat, lon) = location
            val today = now.atZone(zone).toLocalDate()
            val sun = SunCalc.sunTimes(today, lat, lon)
            fun clock(i: Instant) = i.atZone(zone).format(CLOCK)
            if (sun.sunrise == null || sun.sunset == null) {
                val up = sun.alwaysUp == true
                return AutoThemeDecision(!up, if (up) "Güneş bugün batmıyor" else "Güneş bugün doğmuyor")
            }
            return when {
                now < sun.sunrise -> AutoThemeDecision(true, "Gün doğumu ${clock(sun.sunrise)}")
                now < sun.sunset -> AutoThemeDecision(false, "Gün batımı ${clock(sun.sunset)}")
                else -> {
                    val next = SunCalc.sunTimes(today.plusDays(1), lat, lon).sunrise
                    AutoThemeDecision(true, next?.let { "Gün doğumu ${clock(it)}" } ?: "Gün battı ${clock(sun.sunset)}")
                }
            }
        }
        if (weatherIsDay != null && weatherFetchedAt != null &&
            now.toEpochMilli() - weatherFetchedAt in 0..WEATHER_MAX_AGE_MS
        ) {
            return AutoThemeDecision(!weatherIsDay, "Hava durumuna göre (${if (weatherIsDay) "gündüz" else "gece"})")
        }
        return AutoThemeDecision(systemDark, "Sistemin gece moduna göre")
    }
}

/** Otomatik temayı dakikada bir yeniden değerlendirir; hava verisi gelince hemen. */
@Composable
fun rememberAutoTheme(): AutoThemeDecision {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val weather by WeatherHub.state.collectAsState()
    fun evaluate(): AutoThemeDecision {
        val loc = (PanoNav.lastLocation ?: PanoNav.bestLastKnown(context))?.let { it.latitude to it.longitude }
        return AutoTheme.decide(Instant.now(), ZoneId.systemDefault(), loc, weather?.isDay, weather?.fetchedAt, systemDark)
    }
    val decision by produceState(remember { evaluate() }, systemDark, weather) {
        while (true) {
            value = evaluate()
            delay(60_000)
        }
    }
    return decision
}
