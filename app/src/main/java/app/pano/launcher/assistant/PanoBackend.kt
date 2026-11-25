package app.pano.launcher.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import app.pano.launcher.Launch
import app.pano.launcher.ui.hasLocationPermission
import java.time.LocalDate
import java.time.format.TextStyle
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

    private companion object {
        /** Müzik uygulaması açıldıktan sonra medya oturumunun belirmesi için beklenen süre. */
        const val MUSIC_WAIT_MS = 5_000L
        /** "Radyoyu aç" gibi isteklerde söylenen ad (normalize edilmiş). */
        val RADIO_NAMES = setOf("radyo", "radio", "fm", "fmradyo", "am", "dab")
    }

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

    override suspend fun media(action: MediaAction): JSONObject {
        if (MediaHub.state.value == null && action == MediaAction.Play) return startMusic()
        return withContext(Dispatchers.Main) { controlMedia(action) }
    }

    /**
     * Hiç müzik açık değilken "çal": müzik uygulamasını açar, oturum belirirse çalmayı başlatır.
     * Bazı uygulamalar açılınca kendiliğinden çalmaz; o zaman kullanıcıya seçmesi söylenir.
     */
    private suspend fun startMusic(): JSONObject {
        val launched = withContext(Dispatchers.Main) { Launch.music(context, null) }
        if (!launched) return JSONObject().put("error", "Araçta açılacak bir müzik uygulaması bulunamadı.")
        if (!MediaHub.hasAccess(context)) {
            return JSONObject().put("opened_app", true).put("playing", false)
                .put("note", "Müzik uygulaması açıldı. Pano'nun bildirim erişimi kapalı olduğu için çalmayı başlatamıyor; kullanıcı uygulamadan seçmeli.")
        }
        val session = withTimeoutOrNull(MUSIC_WAIT_MS) { MediaHub.state.first { it != null } }
        if (session == null) {
            return JSONObject().put("opened_app", true).put("playing", false)
                .put("note", "Müzik uygulaması açıldı ama henüz bir şey çalmıyor; kullanıcı uygulamadan bir şarkı ya da çalma listesi seçmeli.")
        }
        if (!session.isPlaying) withContext(Dispatchers.Main) { MediaHub.playPause() }
        return JSONObject().put("opened_app", true).put("playing", true)
            .put("title", session.title).put("artist", session.artist)
    }

    private fun controlMedia(action: MediaAction): JSONObject {
        val now = MediaHub.state.value
            ?: return JSONObject().put(
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
        return JSONObject()
            .put("title", now.displayTitle)
            .put("artist", now.displaySubtitle)
            .apply { now.radio?.let { put("source", "radio").put("band", it.band); it.frequency?.let { f -> put("frequency", f) } } }
            .put("playing", if (action == MediaAction.Status) now.isPlaying else action != MediaAction.Pause)
            .apply { if (action == MediaAction.Next || action == MediaAction.Previous) put("note", "Parça değişti; yeni parçanın adı birazdan güncellenir.") }
    }

    override suspend fun openApp(name: String): JSONObject {
        if (Settings.state.value.drivingLock && VehicleHub.isDriving.value) {
            return JSONObject().put("error", "Sürüş sırasında uygulama açma kilitli.")
        }
        // Aracın radyo ekranının başlatıcı simgesi yok; adıyla istenirse doğrudan açılır.
        if (AppMatcher.normalize(name) in RADIO_NAMES && Launch.hasRadio(context)) {
            withContext(Dispatchers.Main) { Launch.radio(context) }
            return JSONObject().put("opened", "Radyo")
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
        val today = LocalDate.now()
        val days = JSONArray()
        w.days.forEach { d ->
            val date = runCatching { LocalDate.parse(d.date) }.getOrNull() ?: return@forEach
            if (date.isBefore(today)) return@forEach
            val label = when (date) {
                today -> "bugün"
                today.plusDays(1) -> "yarın"
                else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, turkish)
            }
            days.put(
                JSONObject()
                    .put("day", label)
                    .put("date", d.date)
                    .put("description", d.description)
                    .put("high_c", d.highC.roundToInt())
                    .put("low_c", d.lowC.roundToInt())
                    .apply { d.precipitationChance?.let { put("precipitation_chance_percent", it) } },
            )
        }
        return JSONObject()
            .put("temperature_c", w.temperatureC.roundToInt())
            .put("description", w.description)
            .put("high_c", w.highC.roundToInt())
            .put("low_c", w.lowC.roundToInt())
            .put("updated_minutes_ago", ageMin)
            .apply { if (days.length() > 0) put("forecast", days) else put("note", "Günlük tahmin henüz alınamadı; yalnızca bugünün değerleri var.") }
    }

    override suspend fun volume(action: VolumeAction, percent: Int?, steps: Int): JSONObject = withContext(Dispatchers.Main) {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return@withContext JSONObject().put("error", "Ses denetimine ulaşılamıyor.")
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        // Sistem ses paneli açılmaz (bayrak 0); Pano'nun kendi göstergeleri sesi zaten gösteriyor.
        val ok = runCatching {
            when (action) {
                VolumeAction.Up -> repeat(steps) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0) }
                VolumeAction.Down -> repeat(steps) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0) }
                VolumeAction.Set -> {
                    val target = ((percent ?: 0) * max / 100f).roundToInt().coerceIn(0, max)
                    if (target > 0 && am.isStreamMute(stream)) am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                    am.setStreamVolume(stream, target, 0)
                }
                VolumeAction.Mute -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                VolumeAction.Unmute -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                VolumeAction.Status -> Unit
            }
        }
        // Rahatsız Etmeyin gibi durumlarda sistem değiştirmeye izin vermeyebilir.
        ok.exceptionOrNull()?.let { return@withContext JSONObject().put("error", "Ses değiştirilemedi (${it.javaClass.simpleName}).") }
        val level = am.getStreamVolume(stream)
        JSONObject()
            .put("level_percent", (level * 100f / max).roundToInt())
            .put("muted", am.isStreamMute(stream))
            .apply { if (action == VolumeAction.Up && level == max) put("note", "Ses zaten en yüksekte.") }
            .apply { if (action == VolumeAction.Down && level == 0) put("note", "Ses en düşükte.") }
    }

    override suspend fun location(): JSONObject {
        PanoNav.init(context)
        val loc = PanoNav.lastLocation ?: PanoNav.bestLastKnown(context)
            ?: return JSONObject().put(
                "error",
                if (!hasLocationPermission(context)) "Pano'nun konum izni yok." else "Konum henüz bilinmiyor (GPS sinyali yok).",
            )
        val ageMin = ((System.currentTimeMillis() - loc.time) / 60_000).coerceAtLeast(0)
        val o = JSONObject().put("location_age_minutes", ageMin)
        if (loc.hasAccuracy()) o.put("accuracy_m", loc.accuracy.roundToInt())
        val place = RouteEngine.reverse(loc.latitude, loc.longitude)
        if (place == null) {
            o.put("note", "Yer adı alınamadı (ağ yok olabilir); yalnızca koordinat biliniyor.")
                .put("lat", "%.4f".format(Locale.US, loc.latitude)).put("lon", "%.4f".format(Locale.US, loc.longitude))
        } else {
            o.put("near", place.name)
            if (place.detail.isNotBlank()) o.put("area", place.detail)
        }
        if (ageMin > 10) o.put("warning", "Konum $ageMin dakika önceye ait; araç o zamandan beri yer değiştirmiş olabilir.")
        return o
    }

    // ---- Telefon -------------------------------------------------------------

    /** Aranacak kişi: [number] Pano'da kalır, modele yalnızca ad ve son iki hane gider. */
    private data class Callee(val name: String?, val number: String)

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(turkish), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace('ı', 'i')

    /** Söylenen numara ya da ad için adaylar: rehber + son aramalardaki adlar. */
    private suspend fun findCallees(target: String): List<Callee> {
        val digits = target.filter { it.isDigit() }
        if (digits.length >= 3 && digits.length >= target.count { it.isLetter() }) {
            return listOf(Callee(null, (if (target.trim().startsWith("+")) "+" else "") + digits))
        }
        val q = fold(target.trim())
        if (q.isEmpty()) return emptyList()
        val contacts = app.pano.launcher.phone.PhoneBook.contacts(context).map { Callee(it.name, it.number) }
        val recents = app.pano.launcher.phone.PhoneBook.recents(context, 200)
            .mapNotNull { r -> r.name?.let { Callee(it, r.number) } }
        val all = (contacts + recents).distinctBy { app.pano.launcher.phone.PhoneBook.digits(it.number) }
        // Önce tam ad, sonra ad içinde geçen.
        val exact = all.filter { it.name != null && fold(it.name) == q }
        return exact.ifEmpty { all.filter { it.name != null && fold(it.name).contains(q) } }.take(5)
    }

    private fun Callee.describe(): JSONObject = JSONObject().apply {
        name?.let { put("name", it) }
        put("number_ends_with", number.filter { it.isDigit() }.takeLast(2))
    }

    override suspend fun phoneCall(target: String, confirm: Boolean): JSONObject {
        val found = findCallees(target)
        if (found.isEmpty()) {
            val canRead = app.pano.launcher.phone.PhoneBook.canReadContacts(context) &&
                app.pano.launcher.phone.PhoneBook.canReadCalls(context)
            return JSONObject().put(
                "error",
                if (!canRead) "Rehbere erişim izni yok; kullanıcıdan numarayı söylemesini iste."
                else "'$target' rehberde ya da son aramalarda bulunamadı; numarayı söylemesini iste.",
            )
        }
        if (found.size > 1) {
            return JSONObject().put("matches", JSONArray(found.map { it.describe() }))
                .put("note", "Birden çok kişi bulundu; hangisi olduğunu sor, sonra tam adıyla yeniden çağır.")
        }
        val callee = found.single()
        if (!confirm) {
            return JSONObject().put("callee", callee.describe()).put("needs_confirmation", true)
                .put("note", "Arama yapılmadı. Kimi arayacağını söyle ve onay iste.")
        }
        withContext(Dispatchers.Main) { app.pano.launcher.phone.PhoneCall.call(context, callee.number) }
        return JSONObject().put("calling", callee.describe())
            .put("direct", app.pano.launcher.phone.PhoneCall.canCallDirectly(context))
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
