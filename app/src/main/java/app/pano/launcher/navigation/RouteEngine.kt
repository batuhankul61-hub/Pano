package app.pano.launcher.navigation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Aranan / gidilecek yer. */
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

/**
 * Rotadaki bir manevra (Valhalla). [type] Valhalla manevra türü (10 = sağa dön, 15 = sola dön…),
 * [beginIndex] manevranın rota çizgisinde başladığı nokta.
 */
data class RouteManeuver(
    val type: Int,
    val instruction: String,
    /** Manevradan hemen önce okunacak sesli komut ("Sağa dönün."). */
    val verbalPre: String?,
    /** Manevradan önceden haber veren kısa sesli komut. */
    val verbalAlert: String?,
    val street: String?,
    val beginIndex: Int,
)

/** Rotanın [startM]..[endM] (rota başından metre) arasındaki kesiminde hız sınırı. */
data class SpeedLimitSpan(val startM: Double, val endM: Double, val kmh: Int)

/** Şerit oku yönü (OSRM "indications" değerleri). */
enum class LaneTurn { Straight, SlightRight, Right, SharpRight, UTurn, SharpLeft, Left, SlightLeft }

/** Tek şerit: gösterilecek yön ve bu rotada kullanılıp kullanılmayacağı. */
data class Lane(val turn: LaneTurn, val active: Boolean)

/** Rotanın [alongM] metresindeki kavşakta şeritler (soldan sağa). */
data class LaneGroup(val alongM: Double, val lanes: List<Lane>)

/** Hesaplanmış rota: çizgi noktaları (enlem, boylam), manevralar, toplam uzunluk ve süre. */
class Route(
    val lats: DoubleArray,
    val lons: DoubleArray,
    val maneuvers: List<RouteManeuver>,
    val lengthM: Double,
    val timeS: Double,
    val destination: Place,
    /** Bilinen hız sınırları, rota boyunca sıralı. Boş = bilinmiyor. */
    val speedLimits: List<SpeedLimitSpan> = emptyList(),
    /** Şerit bilgisi olan kavşaklar, rota boyunca sıralı. Boş = bilinmiyor. */
    val laneGroups: List<LaneGroup> = emptyList(),
) {
    /** Her noktaya kadar rota boyunca birikmiş mesafe (m). */
    val cumulative: DoubleArray = DoubleArray(lats.size).also { c ->
        for (i in 1 until lats.size) c[i] = c[i - 1] + distanceM(lats[i - 1], lons[i - 1], lats[i], lons[i])
    }
}

/**
 * OpenStreetMap tabanlı ücretsiz servisler, anahtar gerekmez:
 *  - Arama: Photon (komoot) — https://photon.komoot.io
 *  - Rota: FOSSGIS'in Valhalla sunucusu — https://valhalla1.openstreetmap.de (Türkçe yönlendirme)
 * İkisi de "makul kullanım" ister: aramada yazarken bekleme süresi var, rota yalnızca gerektiğinde
 * (önizleme, rotadan çıkınca) istenir. Yoğun kullanımda kendi sunucumuza geçmek gerekir.
 */
object RouteEngine {
    private const val TAG = "RouteEngine"
    private const val USER_AGENT = "Pano-launcher/1.0 (Android)"
    private const val PHOTON = "https://photon.komoot.io/api/"
    private const val VALHALLA = "https://valhalla1.openstreetmap.de/route"
    private const val TRACE_ATTRIBUTES = "https://valhalla1.openstreetmap.de/trace_attributes"

    /** Yer arar; [nearLat]/[nearLon] verilirse yakındaki sonuçlar öne çıkar. Hata olursa boş liste. */
    suspend fun search(query: String, nearLat: Double?, nearLon: Double?): List<Place> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        runCatching {
            val near = if (nearLat != null && nearLon != null) "&lat=$nearLat&lon=$nearLon" else ""
            val json = JSONObject(get("$PHOTON?q=${URLEncoder.encode(q, "UTF-8")}&limit=8$near"))
            val features = json.getJSONArray("features")
            (0 until features.length()).mapNotNull { i -> parsePlace(features.getJSONObject(i)) }
                .distinctBy { "${it.name}|${it.detail}" }
        }.onFailure { Log.w(TAG, "arama: $it") }.getOrDefault(emptyList())
    }

    private fun parsePlace(f: JSONObject): Place? {
        val p = f.getJSONObject("properties")
        val c = f.getJSONObject("geometry").getJSONArray("coordinates")
        val street = listOfNotNull(p.optStringOrNull("street"), p.optStringOrNull("housenumber")).joinToString(" ").ifBlank { null }
        val name = p.optStringOrNull("name") ?: street ?: return null
        val detail = listOfNotNull(
            street.takeIf { it != name },
            p.optStringOrNull("district") ?: p.optStringOrNull("locality"),
            p.optStringOrNull("city") ?: p.optStringOrNull("county"),
        ).distinct().joinToString(", ")
        return Place(name, detail, c.getDouble(1), c.getDouble(0))
    }

    /** Arabayla rota. Ağ ya da sunucu hatasında istisna fırlatır; çağıran kullanıcıya gösterir. */
    suspend fun route(fromLat: Double, fromLon: Double, heading: Float?, to: Place): Route = withContext(Dispatchers.IO) {
        val start = JSONObject().put("lat", fromLat).put("lon", fromLon)
        // Gidiş yönünü bildirmek, rotanın arkaya dönerek başlamasını önler.
        if (heading != null) start.put("heading", heading.toInt()).put("heading_tolerance", 60)
        val body = JSONObject()
            .put("locations", JSONArray().put(start).put(JSONObject().put("lat", to.lat).put("lon", to.lon)))
            .put("costing", "auto")
            .put("directions_options", JSONObject().put("language", "tr-TR").put("units", "kilometers"))
        // Valhalla'nın kendi biçimi şerit vermiyor; OSRM biçimindeki aynı istek kavşak şeritlerini veriyor.
        // Ek bilgidir: başarısız olursa rota şeritsiz kalır.
        val lanesJob = async {
            runCatching { lanes(JSONObject(body.toString()).put("format", "osrm").toString()) }
                .onFailure { Log.w(TAG, "şeritler: $it") }.getOrDefault(emptyList())
        }
        val base = baseRoute(body.toString(), to)
        // Hız sınırı rota yanıtında yok; rota çizgisi trace_attributes ile yollara eşlenip okunur.
        val limits = runCatching { speedLimits(base.shape) }
            .onFailure { Log.w(TAG, "hız sınırı: $it") }.getOrDefault(emptyList())
        val laneGroups = lanesJob.await()
        Log.i(
            TAG,
            "rota ${base.route.lengthM.toInt()} m, ${base.route.maneuvers.size} manevra; hız sınırı ${limits.size} kesim " +
                "(${limits.sumOf { it.endM - it.startM }.toInt()} m, ${limits.map { it.kmh }.distinct()}); " +
                "şerit ${laneGroups.size} kavşak",
        )
        base.route.run { Route(lats, lons, maneuvers, lengthM, timeS, destination, limits, laneGroups) }
    }

    private class BaseRoute(val route: Route, val shape: String)

    private fun baseRoute(body: String, to: Place): BaseRoute {
        val trip = JSONObject(post(VALHALLA, body)).getJSONObject("trip")
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val (lats, lons) = decodePolyline6(leg.getString("shape"))
        val ms = leg.getJSONArray("maneuvers")
        val maneuvers = (0 until ms.length()).map { i ->
            val m = ms.getJSONObject(i)
            RouteManeuver(
                type = m.getInt("type"),
                instruction = m.getString("instruction"),
                verbalPre = m.optStringOrNull("verbal_pre_transition_instruction"),
                verbalAlert = m.optStringOrNull("verbal_transition_alert_instruction"),
                street = m.optJSONArray("street_names")?.optString(0)?.ifBlank { null },
                beginIndex = m.getInt("begin_shape_index").coerceIn(0, lats.size - 1),
            )
        }
        val summary = trip.getJSONObject("summary")
        return BaseRoute(Route(lats, lons, maneuvers, summary.getDouble("length") * 1000, summary.getDouble("time"), to), leg.getString("shape"))
    }

    /**
     * Rota çizgisini yollara eşleyip (edge_walk: çizgi zaten Valhalla'dan, birebir yol üstünde)
     * her yol parçasının OSM hız sınırını okur. Sınırı olmayan parçalar atlanır (bilinmiyor).
     */
    private fun speedLimits(shape: String): List<SpeedLimitSpan> {
        val body = JSONObject()
            .put("encoded_polyline", shape)
            .put("shape_match", "edge_walk")
            .put("costing", "auto")
            .put(
                "filters",
                JSONObject().put("action", "include").put(
                    "attributes",
                    JSONArray().put("edge.speed_limit").put("edge.begin_shape_index").put("edge.end_shape_index").put("shape"),
                ),
            )
        val json = JSONObject(post(TRACE_ATTRIBUTES, body.toString()))
        // Kesimler eşlenen çizgi üzerinde ölçülür; aynı yolu izlediği için rota uzunluğuyla örtüşür.
        val (lats, lons) = decodePolyline6(json.getString("shape"))
        val cum = cumulative(lats, lons)
        val edges = json.getJSONArray("edges")
        val out = ArrayList<SpeedLimitSpan>()
        for (i in 0 until edges.length()) {
            val e = edges.getJSONObject(i)
            // Sayı değilse ("unlimited" gibi) ya da 0 ise bilinmiyor sayılır.
            val kmh = (e.opt("speed_limit") as? Number)?.toInt()?.takeIf { it in 1..200 } ?: continue
            val a = cum[e.optInt("begin_shape_index").coerceIn(0, cum.lastIndex)]
            val b = cum[e.optInt("end_shape_index").coerceIn(0, cum.lastIndex)]
            val last = out.lastOrNull()
            // Aynı sınırlı bitişik parçaları birleştir.
            if (last != null && last.kmh == kmh && a - last.endM < 1.0) out[out.lastIndex] = last.copy(endM = b)
            else out += SpeedLimitSpan(a, b, kmh)
        }
        return out
    }

    /** OSRM biçimli rota yanıtındaki kavşak şeritleri; konumları rota başından metre olarak. */
    private fun lanes(osrmBody: String): List<LaneGroup> {
        val r = JSONObject(post(VALHALLA, osrmBody)).getJSONArray("routes").getJSONObject(0)
        val (lats, lons) = decodePolyline6(r.getString("geometry"))
        val cum = cumulative(lats, lons)
        val out = ArrayList<LaneGroup>()
        val legs = r.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val steps = legs.getJSONObject(l).getJSONArray("steps")
            for (s in 0 until steps.length()) {
                val xs = steps.getJSONObject(s).optJSONArray("intersections") ?: continue
                for (x in 0 until xs.length()) {
                    val inter = xs.getJSONObject(x)
                    val arr = inter.optJSONArray("lanes") ?: continue
                    val lanes = (0 until arr.length()).map { k ->
                        val lane = arr.getJSONObject(k)
                        val ind = lane.optStringOrNull("valid_indication")
                            ?: lane.optJSONArray("indications")?.optString(0)
                        Lane(laneTurn(ind), lane.optBoolean("valid"))
                    }
                    if (lanes.size < 2) continue
                    out += LaneGroup(cum[inter.optInt("geometry_index").coerceIn(0, cum.lastIndex)], lanes)
                }
            }
        }
        return out.sortedBy { it.alongM }
    }

    private fun laneTurn(s: String?): LaneTurn = when (s) {
        "slight right" -> LaneTurn.SlightRight
        "right" -> LaneTurn.Right
        "sharp right" -> LaneTurn.SharpRight
        "uturn" -> LaneTurn.UTurn
        "sharp left" -> LaneTurn.SharpLeft
        "left" -> LaneTurn.Left
        "slight left" -> LaneTurn.SlightLeft
        else -> LaneTurn.Straight  // "straight", "none"
    }

    private fun cumulative(lats: DoubleArray, lons: DoubleArray): DoubleArray = DoubleArray(lats.size).also { c ->
        for (i in 1 until lats.size) c[i] = c[i - 1] + distanceM(lats[i - 1], lons[i - 1], lats[i], lons[i])
    }

    private fun get(url: String): String = request(url, null)
    private fun post(url: String, json: String): String = request(url, json)

    private fun request(url: String, json: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", "tr")
            if (json != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Valhalla'nın 6 basamaklı polyline kodlaması. */
    internal fun decodePolyline6(s: String): Pair<DoubleArray, DoubleArray> {
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        var i = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = s[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < s.length) {
            lat += next()
            lon += next()
            lats += lat / 1e6
            lons += lon / 1e6
        }
        return lats.toDoubleArray() to lons.toDoubleArray()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

/** İki nokta arası yaklaşık mesafe (m); şehir içi ölçeklerde eşdikdörtgen yaklaşımı yeterli. */
internal fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2))
    val y = Math.toRadians(lat2 - lat1)
    return Math.sqrt(x * x + y * y) * 6_371_000.0
}
