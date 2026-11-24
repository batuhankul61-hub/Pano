package app.pano.launcher.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import app.pano.launcher.ui.hasLocationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Arayüzün hangi hava ikonunu çizeceğini belirleyen kaba sınıf. */
enum class WeatherIcon { Clear, Cloudy, Rain, Snow, Fog, Storm }

/** Anlık hava durumu. */
data class Weather(
    /** Anlık sıcaklık (°C). */
    val temperatureC: Double,
    /** WMO hava kodu (Open-Meteo `weather_code`). */
    val code: Int,
    /** Türkçe kısa açıklama, örn. "Parçalı bulutlu". */
    val description: String,
    /** Basit ikon anahtarı. */
    val icon: WeatherIcon,
    /** Gündüz mü (false ise gece: güneş yerine ay çizilebilir). */
    val isDay: Boolean,
    /** Günün en yüksek sıcaklığı (°C). */
    val highC: Double,
    /** Günün en düşük sıcaklığı (°C). */
    val lowC: Double,
    /** Verinin alındığı an (System.currentTimeMillis). */
    val fetchedAt: Long,
    /** Bugünden başlayarak günlük tahmin (bugün, yarın…); alınamadıysa boş. */
    val days: List<DayForecast> = emptyList(),
)

/** Bir günün tahmini (Open-Meteo `daily`). */
data class DayForecast(
    /** Yerel tarih, "2026-11-24" biçiminde. */
    val date: String,
    val code: Int,
    val description: String,
    val highC: Double,
    val lowC: Double,
    /** Günün en yüksek yağış olasılığı (%); bilinmiyorsa null. */
    val precipitationChance: Int?,
)

/** Open-Meteo'dan (API anahtarı gerekmez) anlık hava durumunu çeker ve 15 dakikada bir yeniler. */
object WeatherHub {
    private const val TAG = "WeatherHub"
    private const val REFRESH_MS = 15 * 60 * 1000L
    private const val RETRY_MS = 60 * 1000L
    /** Bugün dahil kaç günlük tahmin alınır (asistan "yarın", "hafta sonu" sorularına cevap verebilsin). */
    private const val FORECAST_DAYS = 4

    /** Konum bilinmiyorsa ya da izin yoksa kullanılacak yer: İstanbul. */
    private const val FALLBACK_LAT = 41.0082
    private const val FALLBACK_LON = 28.9784

    private val _state = MutableStateFlow<Weather?>(null)
    val state: StateFlow<Weather?> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Yenileme döngüsünü başlatır; zaten çalışıyorsa bir şey yapmaz. Ana iş parçacığından çağrılmalı. */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            while (isActive) {
                val ok = refreshOnce(app)
                delay(if (ok) REFRESH_MS else RETRY_MS)
            }
        }
    }

    /** Döngüyü durdurur; son değer korunur. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Hemen bir kez yeniler (örn. konum izni yeni verildiyse). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { refreshOnce(app) }
    }

    /** Başarılıysa true döner; hata durumunda son başarılı değer olduğu gibi kalır. */
    private fun refreshOnce(context: Context): Boolean = try {
        val (lat, lon) = lastLocation(context)?.let { it.latitude to it.longitude }
            ?: (FALLBACK_LAT to FALLBACK_LON)
        val weather = parse(fetch(lat, lon))
        _state.value = weather
        Log.i(TAG, "Hava alındı ($lat, $lon): $weather")
        true
    } catch (e: Exception) {
        Log.w(TAG, "Hava durumu alınamadı", e)
        false
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun fetch(lat: Double, lon: Double): String {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=$FORECAST_DAYS",
            lat, lon,
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(json: String): Weather {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val code = current.getInt("weather_code")
        return Weather(
            temperatureC = current.getDouble("temperature_2m"),
            code = code,
            description = describe(code),
            icon = iconFor(code),
            isDay = current.optInt("is_day", 1) == 1,
            highC = daily.getJSONArray("temperature_2m_max").getDouble(0),
            lowC = daily.getJSONArray("temperature_2m_min").getDouble(0),
            fetchedAt = System.currentTimeMillis(),
            days = runCatching { parseDays(daily) }.getOrDefault(emptyList()),
        )
    }

    /** Günlük tahmin dizileri; bir günde eksik alan varsa o gün atlanır. */
    private fun parseDays(daily: JSONObject): List<DayForecast> {
        val dates = daily.optJSONArray("time") ?: return emptyList()
        val codes = daily.optJSONArray("weather_code") ?: return emptyList()
        val max = daily.getJSONArray("temperature_2m_max")
        val min = daily.getJSONArray("temperature_2m_min")
        val rain = daily.optJSONArray("precipitation_probability_max")
        return (0 until dates.length()).mapNotNull { i ->
            if (codes.isNull(i) || max.isNull(i) || min.isNull(i)) return@mapNotNull null
            val code = codes.getInt(i)
            DayForecast(
                date = dates.getString(i),
                code = code,
                description = describe(code),
                highC = max.getDouble(i),
                lowC = min.getDouble(i),
                precipitationChance = rain?.takeIf { i < it.length() && !it.isNull(i) }?.getInt(i),
            )
        }
    }

    /** WMO hava kodunu Türkçe kısa açıklamaya çevirir. */
    fun describe(code: Int): String = when (code) {
        0 -> "Açık"
        1 -> "Çoğunlukla açık"
        2 -> "Parçalı bulutlu"
        3 -> "Kapalı"
        45 -> "Sisli"
        48 -> "Kırağılı sis"
        51 -> "Hafif çisenti"
        53 -> "Çisenti"
        55 -> "Yoğun çisenti"
        56, 57 -> "Dondurucu çisenti"
        61 -> "Hafif yağmur"
        63 -> "Yağmurlu"
        65 -> "Şiddetli yağmur"
        66, 67 -> "Dondurucu yağmur"
        71 -> "Hafif kar"
        73 -> "Karlı"
        75 -> "Yoğun kar"
        77 -> "Kar taneleri"
        80 -> "Hafif sağanak"
        81 -> "Sağanak yağış"
        82 -> "Şiddetli sağanak"
        85 -> "Hafif kar sağanağı"
        86 -> "Yoğun kar sağanağı"
        95 -> "Gök gürültülü fırtına"
        96, 99 -> "Dolulu fırtına"
        else -> "Bilinmiyor"
    }

    /** WMO hava kodunu basit bir ikon anahtarına çevirir. */
    fun iconFor(code: Int): WeatherIcon = when (code) {
        0, 1 -> WeatherIcon.Clear
        2, 3 -> WeatherIcon.Cloudy
        45, 48 -> WeatherIcon.Fog
        in 51..67, in 80..82 -> WeatherIcon.Rain
        in 71..77, 85, 86 -> WeatherIcon.Snow
        in 95..99 -> WeatherIcon.Storm
        else -> WeatherIcon.Cloudy
    }
}
