package app.pano.launcher.navigation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** "Nereye?" ekranındaki kayıtlı kısayollar. */
enum class Shortcut(val key: String, val label: String) { Home("home", "Ev"), Work("work", "İş") }

/** Ev / İş kısayolları; `pano_nav` tercihlerinde son gidilenlerin yanında saklanır. */
object SavedPlaces {
    private var prefs: SharedPreferences? = null
    private val _places = MutableStateFlow<Map<Shortcut, Place>>(emptyMap())
    val places: StateFlow<Map<Shortcut, Place>> = _places.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("pano_nav", Context.MODE_PRIVATE)
        prefs = p
        _places.value = Shortcut.entries.mapNotNull { s -> load(p, s)?.let { s to it } }.toMap()
    }

    fun set(shortcut: Shortcut, place: Place) {
        _places.value = _places.value + (shortcut to place)
        val json = JSONObject().put("n", place.name).put("d", place.detail).put("lat", place.lat).put("lon", place.lon)
        prefs?.edit()?.putString("shortcut_${shortcut.key}", json.toString())?.apply()
    }

    fun remove(shortcut: Shortcut) {
        _places.value = _places.value - shortcut
        prefs?.edit()?.remove("shortcut_${shortcut.key}")?.apply()
    }

    private fun load(p: SharedPreferences, s: Shortcut): Place? = runCatching {
        val o = JSONObject(p.getString("shortcut_${s.key}", null) ?: return null)
        Place(o.getString("n"), o.optString("d"), o.getDouble("lat"), o.getDouble("lon"))
    }.getOrNull()
}

/**
 * Yakındaki elektrikli araç şarj istasyonları: OpenStreetMap `amenity=charging_station`.
 * Önce Overpass API (yarıçapla tam liste), olmazsa Photon (kutu içinde "şarj" araması).
 * Yalnızca OSM'deki ad, işletmeci ve adres kullanılır; hız ya da müsaitlik bilgisi yok.
 */
object ChargerSearch {
    private const val TAG = "ChargerSearch"
    private const val USER_AGENT = "Pano-launcher/1.0 (Android)"
    /** Ortak Overpass sunucuları sık sık aşırı yüklenir (502/504, zaman aşımı); sırayla denenir. */
    private val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    private const val PHOTON = "https://photon.komoot.io/api/"
    private const val MAX = 25

    /** [lat]/[lon] çevresindeki istasyonlar, yakından uzağa. Hata olursa null (boş liste = bulunamadı). */
    suspend fun near(lat: Double, lon: Double): List<Place>? = withContext(Dispatchers.IO) {
        val overpass = runCatching {
            val first = overpass(lat, lon, 10_000)
            if (first.size >= 3) first else runCatching { overpass(lat, lon, 30_000) }.getOrDefault(first)
        }.onFailure { Log.w(TAG, "overpass: $it") }.getOrNull()
        val list = if (overpass.isNullOrEmpty()) {
            runCatching { photon(lat, lon) }.onFailure { Log.w(TAG, "photon: $it") }.getOrNull() ?: overpass
        } else overpass
        list?.sortedBy { distanceM(lat, lon, it.lat, it.lon) }?.take(MAX)
    }

    private fun overpass(lat: Double, lon: Double, radiusM: Int): List<Place> {
        val q = "[out:json][timeout:10];nwr[\"amenity\"=\"charging_station\"](around:$radiusM,$lat,$lon);out center tags 60;"
        val body = "data=" + URLEncoder.encode(q, "UTF-8")
        var last: Throwable? = null
        val text = OVERPASS.firstNotNullOfOrNull { url ->
            runCatching { post(url, body) }.onFailure { last = it; Log.w(TAG, "overpass $url: $it") }.getOrNull()
        } ?: throw (last ?: IllegalStateException("overpass"))
        val json = JSONObject(text)
        val els = json.getJSONArray("elements")
        return (0 until els.length()).mapNotNull { i ->
            val e = els.getJSONObject(i)
            val t = e.optJSONObject("tags") ?: JSONObject()
            val center = e.optJSONObject("center")
            val la = center?.optDouble("lat") ?: e.optDouble("lat")
            val lo = center?.optDouble("lon") ?: e.optDouble("lon")
            if (la.isNaN() || lo.isNaN()) return@mapNotNull null
            val operator = t.str("operator") ?: t.str("brand") ?: t.str("network")
            val name = t.str("name") ?: operator ?: "Şarj istasyonu"
            val street = listOfNotNull(t.str("addr:street"), t.str("addr:housenumber")).joinToString(" ").ifBlank { null }
            val detail = listOfNotNull(
                operator.takeIf { it != name },
                street,
                t.str("addr:district") ?: t.str("addr:city"),
            ).distinct().joinToString(", ")
            Place(name, detail, la, lo)
        }
    }

    private fun photon(lat: Double, lon: Double): List<Place> {
        val d = 0.15 // ~15 km'lik kutu
        val bbox = "${lon - d},${lat - d},${lon + d},${lat + d}"
        return listOf("şarj", "charging").flatMap { q ->
            val json = JSONObject(get("$PHOTON?q=${URLEncoder.encode(q, "UTF-8")}&osm_tag=amenity:charging_station&bbox=$bbox&limit=$MAX"))
            val f = json.getJSONArray("features")
            (0 until f.length()).mapNotNull { i ->
                val o = f.getJSONObject(i)
                val p = o.getJSONObject("properties")
                val c = o.getJSONObject("geometry").getJSONArray("coordinates")
                val street = listOfNotNull(p.str("street"), p.str("housenumber")).joinToString(" ").ifBlank { null }
                val detail = listOfNotNull(street, p.str("district"), p.str("city")).distinct().joinToString(", ")
                Place(p.str("name") ?: "Şarj istasyonu", detail, c.getDouble(1), c.getDouble(0))
            }
        }.distinctBy { "${it.name}|${it.lat}|${it.lon}" }
    }

    private fun JSONObject.str(k: String): String? = optString(k, "").trim().ifEmpty { null }

    private fun get(url: String): String = request(url, null)
    private fun post(url: String, body: String): String = request(url, body)

    private fun request(url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
