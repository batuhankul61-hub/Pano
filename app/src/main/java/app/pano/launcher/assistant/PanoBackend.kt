package app.pano.launcher.assistant

import android.content.Context
import android.content.Intent
import app.pano.launcher.Launch
import app.pano.launcher.media.MediaHub
import app.pano.launcher.navigation.NavPhase
import app.pano.launcher.navigation.PanoNav
import app.pano.launcher.navigation.RouteEngine
import app.pano.launcher.settings.Settings
import app.pano.launcher.vehicle.ClimateStore
import app.pano.launcher.vehicle.SpeedSource
import app.pano.launcher.vehicle.VehicleHub
import app.pano.launcher.weather.WeatherHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Asistan araçlarını Pano'nun mevcut katmanlarına bağlar. [context] dock'a basılan ekranın
 * Activity'sidir (uygulamalar sağ panelde açılabilsin diye).
 */
class PanoBackend(private val context: Context) : AssistantBackend {

    private val turkish = Locale.forLanguageTag("tr-TR")

    override suspend fun climate(change: ClimateChange): JSONObject = withContext(Dispatchers.Main) {
        val c = ClimateStore.load(context)
        change.power?.let { on ->
            if (!on) c.powerOff() else if (c.isOff) c.setFanLevel(1)
        }
        change.temperature?.let { c.setDriverTemp(it) }
        change.passengerTemperature?.let { c.setPassengerTemp(it) }
        change.fan?.let { if (it <= 0) c.powerOff() else c.setFanLevel(it) }
        change.auto?.let { c.auto = it }
        change.ac?.let { c.ac = it }
        change.recirculation?.let { c.recirculation = it }
        change.defrost?.let { c.defrost = it }
        climateJson()
    }

    private fun climateJson(): JSONObject {
        val c = ClimateStore.load(context)
        return JSONObject()
            .put("power", !c.isOff)
            .put("driver_temperature", c.driver.toDouble())
            .apply { if (c.dualZone && !c.sync) put("passenger_temperature", c.passenger.toDouble()) }
            .put("fan", c.fan)
            .put("auto", c.auto)
            .put("ac", c.ac)
            .put("recirculation", c.recirculation)
            .put("defrost", c.defrost)
            .put("linked_to_vehicle", c.linked)
            .apply { if (!c.linked) put("note", "Araca bağlı değil; ayar yalnızca Pano'da değişti.") }
    }

    override suspend fun navigateTo(query: String, start: Boolean): JSONObject {
        PanoNav.init(context)
        val here = PanoNav.lastLocation ?: PanoNav.bestLastKnown(context)
        val places = RouteEngine.search(query, here?.latitude, here?.longitude)
        val place = places.firstOrNull() ?: return JSONObject().put("error", "\"$query\" için yer bulunamadı.")
        withContext(Dispatchers.Main) { PanoNav.preview(context, place) }
        val session = withTimeoutOrNull(20_000) {
            PanoNav.state.first { it == null || !it.loading }
        } ?: return JSONObject().put("error", "Rota zamanında hesaplanamadı.")
        session.error?.let { return JSONObject().put("error", it) }
        val route = session.route ?: return JSONObject().put("error", "Rota bulunamadı.")
        if (start) withContext(Dispatchers.Main) { PanoNav.start(context) }
        return JSONObject()
            .put("destination", place.name)
            .put("detail", place.detail)
            .put("distance_km", (route.lengthM / 100).roundToInt() / 10.0)
            .put("duration_min", (route.timeS / 60).roundToInt())
            .put("started", start && PanoNav.state.value?.phase == NavPhase.Active)
            .put("other_matches", JSONArray(places.drop(1).take(2).map { listOf(it.name, it.detail).filter(String::isNotBlank).joinToString(", ") }))
    }

    override suspend fun stopNavigation(): JSONObject = withContext(Dispatchers.Main) {
        val had = PanoNav.state.value != null
        PanoNav.stop()
        JSONObject().put("stopped", had).apply { if (!had) put("note", "Zaten aktif navigasyon yoktu.") }
    }

    override suspend fun media(action: MediaAction): JSONObject = withContext(Dispatchers.Main) {
        val now = MediaHub.state.value
            ?: return@withContext JSONObject().put(
                "error",
                if (MediaHub.hasAccess(context)) "Şu an açık bir medya oturumu yok." else "Pano'nun bildirim erişimi kapalı; müzik kontrol edilemiyor.",
            )
        when (action) {
            MediaAction.Play -> if (!now.isPlaying) MediaHub.playPause()
            MediaAction.Pause -> if (now.isPlaying) MediaHub.playPause()
            MediaAction.Next -> MediaHub.next()
            MediaAction.Previous -> MediaHub.previous()
            MediaAction.Status -> Unit
        }
        JSONObject()
            .put("title", now.title)
            .put("artist", now.artist)
            .put("playing", if (action == MediaAction.Status) now.isPlaying else action != MediaAction.Pause)
            .apply { if (action == MediaAction.Next || action == MediaAction.Previous) put("note", "Parça değişti; yeni parçanın adı birazdan güncellenir.") }
    }

    override suspend fun openApp(name: String): JSONObject {
        if (Settings.state.value.drivingLock && VehicleHub.isDriving.value) {
            return JSONObject().put("error", "Sürüş sırasında uygulama açma kilitli.")
        }
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { Triple(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
        val best = AppMatcher.best(name, apps.map { it.first to it.second })
            ?: return JSONObject().put("error", "\"$name\" adlı uygulama bulunamadı.")
        val app = apps.first { it.first == best.first && it.second == best.second }
        withContext(Dispatchers.Main) { Launch.app(context, app.second, app.third) }
        return JSONObject().put("opened", app.first)
    }

    override suspend fun vehicleStatus(): JSONObject {
        val s = VehicleHub.state.value
        val o = JSONObject().put("preview", s.isPreview)
        if (s.isPreview) {
            // Önizleme değerleri örnektir; modele hiç verilmez.
            o.put("note", "Araç verisine ulaşılamıyor (önizleme). Batarya, menzil, lastik ve tüketim bilinmiyor; uydurma.")
            if (s.speedSource == SpeedSource.Gps) s.speedKmh?.let { o.put("speed_kmh_gps", it) }
        } else {
            s.batteryPercent?.let { o.put("battery_percent", it) }
            s.rangeKm?.let { o.put("range_km", it) }
            s.speedKmh?.let { o.put("speed_kmh", it) }
            s.gear?.let { o.put("gear", it.label) }
            s.outsideTempC?.let { o.put("outside_temp_c", it) }
            s.insideTempC?.let { o.put("inside_temp_c", it) }
            s.consumption?.let {
                o.put("consumption_kwh_100km", String.format(turkish, "%.1f", it.kwh100))
                o.put("consumption_window", if (it.recentWindow) "son 50 km" else "toplam ortalama")
            }
            s.tyresKpa?.let { t ->
                val tyres = JSONObject()
                fun put(k: String, v: Int?) { if (v != null) tyres.put(k, String.format(turkish, "%.1f bar", v / 100f)) }
                put("front_left", t.frontLeft); put("front_right", t.frontRight)
                put("rear_left", t.rearLeft); put("rear_right", t.rearRight)
                if (tyres.length() > 0) o.put("tyres", tyres)
            }
        }
        o.put("climate", withContext(Dispatchers.Main) { climateJson() })
        return o
    }

    override suspend fun weather(): JSONObject {
        val w = WeatherHub.state.value ?: return JSONObject().put("error", "Hava durumu henüz alınamadı.")
        val ageMin = ((System.currentTimeMillis() - w.fetchedAt) / 60_000).toInt()
        return JSONObject()
            .put("temperature_c", w.temperatureC.roundToInt())
            .put("description", w.description)
            .put("high_c", w.highC.roundToInt())
            .put("low_c", w.lowC.roundToInt())
            .put("updated_minutes_ago", ageMin)
    }
}

/** Söylenen adı yüklü uygulama etiketleriyle eşler ("spotify'ı aç" → "Spotify"). */
object AppMatcher {
    private val turkish = Locale.forLanguageTag("tr-TR")

    fun normalize(s: String): String = s.lowercase(turkish)
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g').replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .filter { it.isLetterOrDigit() }

    /** [apps]: (etiket, paket). En iyi eşleşme ya da null. */
    fun best(query: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        return apps.mapNotNull { app ->
            val l = normalize(app.first)
            if (l.isEmpty()) return@mapNotNull null
            val score = when {
                l == q -> 100
                l.startsWith(q) -> 80
                q.startsWith(l) && l.length >= 3 -> 70
                l.contains(q) && q.length >= 3 -> 60
                q.contains(l) && l.length >= 4 -> 50
                normalize(app.second).contains(q) && q.length >= 4 -> 40
                else -> 0
            }
            if (score > 0) app to (score * 100 - l.length) else null
        }.maxByOrNull { it.second }?.first
    }
}
