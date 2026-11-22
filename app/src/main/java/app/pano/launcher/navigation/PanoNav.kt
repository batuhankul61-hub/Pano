package app.pano.launcher.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Rotada nerede olduğumuz. */
data class NavProgress(
    /** Sıradaki manevranın [Route.maneuvers] içindeki yeri. */
    val nextIndex: Int,
    val distanceToNextM: Double,
    val remainingM: Double,
    val remainingS: Double,
)

enum class NavPhase { Preview, Active, Arrived }

/** Pano'nun kendi navigasyonunun durumu; null = navigasyon yok. */
data class NavSession(
    val destination: Place,
    val phase: NavPhase,
    val route: Route? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val progress: NavProgress? = null,
    /** Rotadan çıkıldı, yeni rota hesaplanıyor. */
    val rerouting: Boolean = false,
)

/**
 * Pano'nun kendi navigasyonu: yer seçilir → rota önizlenir → "Başlat" ile dönüş dönüş yönlendirme.
 * Konumu doğrudan Android'in GPS'inden okur (Google servisleri gerekmez), rotadan çıkınca yeniden
 * rota ister, manevralardan önce Türkçe sesli uyarı verir (araçta Türkçe ses motoru varsa).
 */
object PanoNav {
    private const val TAG = "PanoNav"
    /** Rotaya bundan uzak konum "rotadan çıktı" sayılır (m). */
    private const val OFF_ROUTE_M = 50.0
    private const val OFF_ROUTE_FIXES = 3
    private const val REROUTE_MIN_GAP_MS = 12_000L
    private const val ARRIVE_M = 30.0
    private const val MAX_RECENTS = 6

    private val _state = MutableStateFlow<NavSession?>(null)
    val state: StateFlow<NavSession?> = _state.asStateFlow()

    private val _recents = MutableStateFlow<List<Place>>(emptyList())
    val recents: StateFlow<List<Place>> = _recents.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var routeJob: Job? = null
    private var app: Context? = null
    private var prefs: SharedPreferences? = null

    @Volatile var lastLocation: Location? = null
        private set

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences("pano_nav", Context.MODE_PRIVATE)
        _recents.value = loadRecents()
        lastLocation = bestLastKnown(context)
    }

    // ---- Akış --------------------------------------------------------------

    /** Hedef seçildi: rotayı hesapla ve önizle (henüz yönlendirme yok). */
    fun preview(context: Context, place: Place) {
        init(context)
        addRecent(place)
        _state.value = NavSession(place, NavPhase.Preview, loading = true)
        requestRoute(place, keepPhase = NavPhase.Preview)
    }

    /** Önizlenen rotada yönlendirmeyi başlatır. */
    fun start(context: Context) {
        val s = _state.value ?: return
        if (s.route == null) return
        init(context)
        listen(context)
        speaker.open(context)
        _state.value = s.copy(phase = NavPhase.Active)
        lastSpoken = -1
        lastAlong = 0.0
        lastAlerted = -1
        lastLocation?.let(::onLocation)
        s.route.maneuvers.firstOrNull()?.let { speaker.say(it.verbalPre ?: it.instruction) }
    }

    /** Navigasyonu bitirir (önizleme dahil). */
    fun stop() {
        routeJob?.cancel()
        _state.value = null
        stopListening()
        speaker.close()
        offRouteCount = 0
    }

    private fun requestRoute(place: Place, keepPhase: NavPhase) {
        val from = lastLocation
        if (from == null) {
            _state.value = _state.value?.copy(loading = false, rerouting = false, error = "Konum henüz bilinmiyor. GPS açık mı?")
            return
        }
        routeJob?.cancel()
        routeJob = scope.launch {
            val heading = if (from.hasBearing() && from.hasSpeed() && from.speed > 2f) from.bearing else null
            val result = runCatching { RouteEngine.route(from.latitude, from.longitude, heading, place) }
            val current = _state.value ?: return@launch
            if (current.destination != place) return@launch
            result.onSuccess { route ->
                lastRouteAt = SystemClock.elapsedRealtime()
                offRouteCount = 0
                lastSpoken = -1
                lastAlong = 0.0
                lastAlerted = -1
                _state.value = current.copy(route = route, loading = false, rerouting = false, error = null, phase = keepPhase)
                lastLocation?.let { if (keepPhase == NavPhase.Active) onLocation(it) }
            }.onFailure {
                Log.w(TAG, "rota: $it")
                _state.value = current.copy(loading = false, rerouting = false, error = "Rota alınamadı. İnternet bağlantısını kontrol et.")
            }
        }
    }

    // ---- Konum ve ilerleme -------------------------------------------------

    private var offRouteCount = 0
    private var lastRouteAt = 0L
    /** Rota boyunca en son bilinen konum (m); yeni rotada sıfırlanır. */
    private var lastAlong = 0.0
    /** Bir konumdan sonrakine rota boyunca en fazla bu kadar ilerlenebilir (GPS kesintisi payıyla). */
    private const val LOOKAHEAD_M = 700.0
    private var lastSpoken = -1
    private var lastAlerted = -1

    private fun onLocation(loc: Location) {
        lastLocation = loc
        val s = _state.value ?: return
        val route = s.route ?: return
        if (s.phase != NavPhase.Active) return

        val (along, off) = project(route, loc.latitude, loc.longitude)
        val remaining = (route.lengthM - along).coerceAtLeast(0.0)

        if (remaining < ARRIVE_M) {
            _state.value = s.copy(phase = NavPhase.Arrived, progress = NavProgress(route.maneuvers.lastIndex, 0.0, 0.0, 0.0))
            speaker.say("Varış noktanıza ulaştınız.")
            stopListening()
            scope.launch { delay(20_000); if (_state.value?.phase == NavPhase.Arrived) stop() }
            return
        }

        // Rotadan çıkma: birkaç ardışık konum uzaktaysa yeniden rota iste.
        offRouteCount = if (off > OFF_ROUTE_M + loc.accuracy.coerceAtMost(30f)) offRouteCount + 1 else 0
        if (offRouteCount >= OFF_ROUTE_FIXES && !s.rerouting &&
            SystemClock.elapsedRealtime() - lastRouteAt > REROUTE_MIN_GAP_MS
        ) {
            Log.i(TAG, "rotadan çıkıldı (${off.toInt()} m), yeniden hesaplanıyor")
            _state.value = s.copy(rerouting = true)
            speaker.say("Rota yeniden hesaplanıyor.")
            requestRoute(s.destination, keepPhase = NavPhase.Active)
            return
        }

        val next = route.maneuvers.indexOfFirst { it.type != 1 && route.cumulative[it.beginIndex] > along + 5 }
            .let { if (it < 0) route.maneuvers.lastIndex else it }
        val toNext = (route.cumulative[route.maneuvers[next].beginIndex] - along).coerceAtLeast(0.0)
        val remainingS = route.timeS * remaining / route.lengthM.coerceAtLeast(1.0)
        _state.value = s.copy(progress = NavProgress(next, toNext, remaining, remainingS))

        // Sesli uyarılar: önce ~400 m'de haber ver, sonra hıza göre manevradan hemen önce söyle.
        val m = route.maneuvers[next]
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 10.0
        if (next != lastAlerted && toNext in 250.0..450.0) {
            lastAlerted = next
            m.verbalAlert?.let { speaker.say("${formatDistanceSpoken(toNext)} sonra $it") }
        }
        if (next != lastSpoken && toNext <= (speed * 7).coerceIn(40.0, 160.0)) {
            lastSpoken = next
            lastAlerted = next
            speaker.say(m.verbalPre ?: m.instruction)
        }
    }

    /**
     * Konumu rota çizgisine izdüşürür: (rota boyunca katedilen m, rotaya uzaklık m).
     * Yalnızca son konumun çevresine (biraz geri, [LOOKAHEAD_M] ileri) bakılır. Tüm rotayı taramak
     * tehlikeli: rota kendine yaklaşıyorsa (dönüp gelen yollar) konum ileriki bir parçaya "sıçrar"
     * ve yönlendirme yanlış manevrayı söyler. Pencerede yakın parça yoksa araç rotadan çıkmıştır.
     */
    private fun project(route: Route, lat: Double, lon: Double): Pair<Double, Double> {
        val n = route.lats.size
        if (n < 2) return 0.0 to 0.0
        val c = route.cumulative
        var bestI = -1
        var bestD = Double.MAX_VALUE
        var bestT = 0.0
        for (i in 0 until n - 1) {
            if (c[i + 1] < lastAlong - 80 || c[i] > lastAlong + LOOKAHEAD_M) continue
            val (d, t) = segmentDistance(route, i, lat, lon)
            if (d < bestD) { bestI = i; bestD = d; bestT = t }
        }
        if (bestI < 0) return lastAlong to Double.MAX_VALUE
        val along = c[bestI] + bestT * (c[bestI + 1] - c[bestI])
        // Rotadan uzaktaysa ilerleme ilerletilmez; araç dönünce kaldığı yerden devam eder.
        if (bestD <= OFF_ROUTE_M) lastAlong = along
        return (if (bestD <= OFF_ROUTE_M) along else lastAlong) to bestD
    }

    /** Noktanın i. parçaya uzaklığı (m) ve parça üzerindeki konumu (0..1). */
    private fun segmentDistance(route: Route, i: Int, lat: Double, lon: Double): Pair<Double, Double> {
        val k = Math.cos(Math.toRadians(lat)) * 111_320.0
        val ax = route.lons[i] * k; val ay = route.lats[i] * 110_540.0
        val bx = route.lons[i + 1] * k; val by = route.lats[i + 1] * 110_540.0
        val px = lon * k; val py = lat * 110_540.0
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return Math.hypot(px - cx, py - cy) to t
    }

    // ---- GPS ----------------------------------------------------------------

    private var listening: LocationManager? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Ağ konumu GPS'ten kötüdür; GPS taze ise onu kullan.
            val prev = lastLocation
            if (location.provider == LocationManager.NETWORK_PROVIDER && prev?.provider == LocationManager.GPS_PROVIDER &&
                location.time - prev.time < 5_000
            ) return
            onLocation(location)
        }
        @Deprecated("API 29") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun listen(context: Context) {
        if (listening != null || !hasLocationPermission(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in lm.allProviders }
            .forEach { runCatching { lm.requestLocationUpdates(it, 1_000L, 0f, listener, Looper.getMainLooper()) } }
        listening = lm
    }

    private fun stopListening() {
        listening?.removeUpdates(listener)
        listening = null
    }

    /** Konum dinlemesi yokken (önizleme) başlangıç noktası için. */
    @SuppressLint("MissingPermission")
    fun bestLastKnown(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in lm.allProviders }
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.also { lastLocation = it }
    }

    // ---- Son gidilenler -----------------------------------------------------

    private fun addRecent(place: Place) {
        val list = (listOf(place) + _recents.value.filterNot { it.name == place.name && it.detail == place.detail }).take(MAX_RECENTS)
        _recents.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("d", it.detail).put("lat", it.lat).put("lon", it.lon)) }
        prefs?.edit()?.putString("recents", arr.toString())?.apply()
    }

    private fun loadRecents(): List<Place> = runCatching {
        val arr = JSONArray(prefs?.getString("recents", null) ?: return emptyList())
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).run { Place(getString("n"), optString("d"), getDouble("lat"), getDouble("lon")) }
        }
    }.getOrDefault(emptyList())

    // ---- Sesli yönlendirme --------------------------------------------------

    private val _voice = MutableStateFlow<Boolean?>(null)

    /**
     * Sesli yönlendirme durumu: true = Türkçe konuşabiliyor, false = Türkçe ses motoru yok
     * (BYD'de Google servisleri ve TTS motoru gelmiyor), null = henüz denenmedi.
     */
    val voice: StateFlow<Boolean?> = _voice.asStateFlow()

    /**
     * Türkçe ses motoru varsa konuşur; yoksa sessiz kalır (ekran yine yönlendirir).
     * Sistem varsayılanı Türkçe bilmiyorsa kurulu diğer motorlar sırayla denenir
     * (ör. sonradan kurulan SherpaTTS ya da eSpeak NG); ayar değiştirmek gerekmez.
     */
    private val speaker = object {
        private var tts: TextToSpeech? = null
        private var ready = false
        private var audio: AudioManager? = null
        private var focus: AudioFocusRequest? = null
        private val main = android.os.Handler(Looper.getMainLooper())

        fun open(context: Context) {
            if (tts != null) return
            audio = context.getSystemService(AudioManager::class.java)
            tryEngine(context.applicationContext, null, mutableSetOf())
        }

        /** [engine] null = sistem varsayılanı. Türkçe yoksa denenmemiş bir sonraki motora geçer. */
        private fun tryEngine(app: Context, engine: String?, tried: MutableSet<String?>) {
            tried += engine
            var created: TextToSpeech? = null
            // Motor hiç yoksa geri çağrı yapıcının içinden gelir; atama bitsin diye sıraya alınır.
            created = TextToSpeech(app, { status -> main.post { onInit(app, created, status, tried) } }, engine)
            tts = created
        }

        private fun onInit(app: Context, t: TextToSpeech?, status: Int, tried: MutableSet<String?>) {
            if (t == null || tts !== t) { t?.shutdown(); return }  // bu arada kapatıldı
            val lang = if (status == TextToSpeech.SUCCESS) t.setLanguage(Locale("tr", "TR")) else TextToSpeech.LANG_NOT_SUPPORTED
            ready = lang >= TextToSpeech.LANG_AVAILABLE
            if (!ready) {
                val next = runCatching { t.engines.map { it.name } }.getOrDefault(emptyList()).firstOrNull { it !in tried }
                Log.i(TAG, "sesli yönlendirme: ${tried.last() ?: "varsayılan"} motorunda Türkçe yok ($status/$lang); sıradaki: $next")
                t.shutdown()
                tts = null
                if (next != null) tryEngine(app, next, tried) else _voice.value = false
                return
            }
            Log.i(TAG, "sesli yönlendirme: Türkçe hazır (${tried.last() ?: t.defaultEngine})")
            _voice.value = true
            t.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = duck(true)
                override fun onDone(id: String?) = duck(false)
                @Deprecated("API 21") override fun onError(id: String?) = duck(false)
            })
        }

        /** Konuşurken müziğin sesini kıs. */
        private fun duck(on: Boolean) {
            val am = audio ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            if (on) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    ).build()
                focus = req
                am.requestAudioFocus(req)
            } else {
                focus?.let { am.abandonAudioFocusRequest(it) }
                focus = null
            }
        }

        fun say(text: String) {
            if (!ready) return
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pano-nav-${SystemClock.uptimeMillis()}")
        }

        fun close() {
            main.removeCallbacksAndMessages(null)
            tts?.stop()
            tts?.shutdown()
            tts = null
            ready = false
            duck(false)
        }
    }
}

// ---- Biçimlendirme ------------------------------------------------------------

fun formatDistance(m: Double): String = when {
    m < 100 -> "${(Math.round(m / 10) * 10).coerceAtLeast(10)} m"
    m < 1000 -> "${Math.round(m / 50) * 50} m"
    m < 10_000 -> String.format(Locale("tr", "TR"), "%.1f km", m / 1000)
    else -> "${Math.round(m / 1000)} km"
}

private fun formatDistanceSpoken(m: Double): String =
    if (m < 1000) "${Math.round(m / 50) * 50} metre" else String.format(Locale("tr", "TR"), "%.1f kilometre", m / 1000)

fun formatDuration(s: Double): String {
    val min = Math.round(s / 60).toInt().coerceAtLeast(1)
    return if (min < 60) "$min dk" else "${min / 60} sa ${min % 60} dk"
}
