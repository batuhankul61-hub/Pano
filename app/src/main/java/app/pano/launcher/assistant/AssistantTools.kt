package app.pano.launcher.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Klima isteği; null alan = değiştirme. */
data class ClimateChange(
    val temperature: Float? = null,
    val passengerTemperature: Float? = null,
    val ac: Boolean? = null,
    val fan: Int? = null,
    val auto: Boolean? = null,
    val power: Boolean? = null,
    val recirculation: Boolean? = null,
    val defrost: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == ClimateChange()
}

enum class MediaAction { Play, Pause, Next, Previous, Status }

/** Medya ses düzeyi işlemi. [Set] için yüzde ayrıca verilir. */
enum class VolumeAction { Up, Down, Set, Mute, Unmute, Status }

/**
 * Araçların gerçekte iş yaptığı katman. Uygulamada [PanoBackend] Pano'nun hub'larına bağlanır;
 * testte sahte bir uygulama hangi çağrının nereye gittiğini kaydeder.
 */
interface AssistantBackend {
    suspend fun climate(change: ClimateChange): JSONObject
    suspend fun navigateTo(query: String, start: Boolean): JSONObject
    suspend fun stopNavigation(): JSONObject
    suspend fun media(action: MediaAction): JSONObject
    suspend fun openApp(name: String): JSONObject
    suspend fun vehicleStatus(): JSONObject
    suspend fun weather(): JSONObject
    /** [percent] yalnızca [VolumeAction.Set] için (0–100); [steps] yalnızca Up/Down için kademe sayısı. */
    suspend fun volume(action: VolumeAction, percent: Int?, steps: Int): JSONObject
    suspend fun location(): JSONObject
}

/**
 * Modelin çağırabileceği araçlar. Bilerek yalnızca okuma, klima, medya, navigasyon ve uygulama
 * açma var: kilit, bagaj, ayna, cam gibi fiziksel hareket eden hiçbir şey için araç tanımlanmaz.
 */
object AssistantTools {

    fun declarations(): JSONArray = JSONArray()
        .put(
            fn(
                "set_climate",
                "Klimayı ayarlar. Yalnızca kullanıcının istediği alanları gönder. Sonuçta klimanın yeni durumu döner.",
                props(
                    "temperature" to num("Sürücü tarafı hedef sıcaklık, °C (16–30, 0,5 adım)."),
                    "passenger_temperature" to num("Yolcu tarafı hedef sıcaklık, °C."),
                    "ac" to bool("A/C (soğutma kompresörü) açık mı."),
                    "fan" to int("Fan kademesi 1–7. Kapatmak için power=false kullan."),
                    "auto" to bool("Otomatik klima açık mı."),
                    "power" to bool("Klimayı tamamen aç (true) ya da kapat (false)."),
                    "recirculation" to bool("İç hava devridaimi."),
                    "defrost" to bool("Ön cam buğu çözme."),
                ),
            ),
        )
        .put(
            fn(
                "navigate_to",
                "Bir yere Pano'nun kendi navigasyonuyla rota oluşturur. Yer adı, adres ya da 'en yakın benzinlik' gibi bir arama olabilir.",
                props(
                    "destination" to str("Gidilecek yer, kullanıcının söylediği gibi."),
                    "start" to bool("true: rotayı hemen başlat (varsayılan). false: yalnızca önizle."),
                ),
                required = listOf("destination"),
            ),
        )
        .put(fn("stop_navigation", "Pano'nun navigasyonunu ya da rota önizlemesini bitirir.", props()))
        .put(
            fn(
                "media_control",
                "Çalan müziği kontrol eder ya da ne çaldığını söyler. Hiç müzik açık değilken 'play' müzik uygulamasını açar " +
                    "ve çalmayı dener; sonuçtaki 'playing' false ise kullanıcının uygulamadan bir şey seçmesi gerekir.",
                props("action" to enumStr("Yapılacak iş.", listOf("play", "pause", "next", "previous", "status"))),
                required = listOf("action"),
            ),
        )
        .put(
            fn(
                "open_app",
                "Araçta yüklü bir uygulamayı adıyla açar (ör. Spotify, YouTube Music, Waze, Radyo). Ekranın sağ panelinde açılır. " +
                    "Bulunamazsa Türkçe/İngilizce diğer adıyla bir kez daha dene (Radyo ↔ Radio, Müzik ↔ Music).",
                props("name" to str("Uygulamanın adı.")),
                required = listOf("name"),
            ),
        )
        .put(
            fn(
                "vehicle_status",
                "Araç durumunu okur: batarya yüzdesi, menzil, hız, vites, lastik basınçları, tüketim, iç/dış sıcaklık ve klima. " +
                    "Bilinmeyen alanlar gönderilmez; 'preview' true ise araç verisine ulaşılamıyor demektir.",
                props(),
            ),
        )
        .put(
            fn(
                "weather",
                "Bulunulan yerin anlık hava durumu ve bugünden başlayan birkaç günlük tahmini (bugün, yarın, sonraki günler: " +
                    "durum, en yüksek/en düşük sıcaklık, yağış olasılığı).",
                props(),
            ),
        )
        .put(
            fn(
                "set_volume",
                "Medya (müzik, radyo) ses düzeyini değiştirir ya da okur. Kullanıcı 'sesi aç/kıs/biraz artır' derse bunu kullan. " +
                    "Sonuçta yeni düzey yüzde olarak döner.",
                props(
                    "action" to enumStr("Yapılacak iş.", listOf("up", "down", "set", "mute", "unmute", "status")),
                    "percent" to int("Yalnızca 'set' için: hedef düzey, 0–100."),
                    "steps" to int("'up' / 'down' için kaç kademe (varsayılan 2). 'biraz' = 1–2, 'çok' = 4–5."),
                ),
                required = listOf("action"),
            ),
        )
        .put(
            fn(
                "current_location",
                "Aracın şu anki konumunu okur: yakındaki yer ya da sokak adı, semt ve şehir. 'Neredeyiz?' gibi sorular için.",
                props(),
            ),
        )

    /** Tek bir araç çağrısını çalıştırır; hata olursa modelin anlayacağı bir `error` döner. */
    suspend fun run(call: ToolCall, backend: AssistantBackend): JSONObject = try {
        val a = call.args
        when (call.name) {
            "set_climate" -> {
                val change = ClimateChange(
                    temperature = a.optFloatOrNull("temperature"),
                    passengerTemperature = a.optFloatOrNull("passenger_temperature"),
                    ac = a.optBoolOrNull("ac"),
                    fan = a.optFloatOrNull("fan")?.let { Math.round(it) },
                    auto = a.optBoolOrNull("auto"),
                    power = a.optBoolOrNull("power"),
                    recirculation = a.optBoolOrNull("recirculation"),
                    defrost = a.optBoolOrNull("defrost"),
                )
                if (change.isEmpty) error("Değiştirilecek bir klima ayarı verilmedi.")
                backend.climate(change)
            }
            "navigate_to" -> {
                val dest = a.optString("destination").trim()
                if (dest.isEmpty()) error("Hedef belirtilmedi.")
                backend.navigateTo(dest, a.optBoolOrNull("start") ?: true)
            }
            "stop_navigation" -> backend.stopNavigation()
            "media_control" -> {
                val action = when (a.optString("action").lowercase()) {
                    "play" -> MediaAction.Play
                    "pause" -> MediaAction.Pause
                    "next" -> MediaAction.Next
                    "previous" -> MediaAction.Previous
                    "status" -> MediaAction.Status
                    else -> error("Bilinmeyen medya işlemi: ${a.optString("action")}")
                }
                backend.media(action)
            }
            "open_app" -> {
                val name = a.optString("name").trim()
                if (name.isEmpty()) error("Uygulama adı verilmedi.")
                backend.openApp(name)
            }
            "vehicle_status" -> backend.vehicleStatus()
            "weather" -> backend.weather()
            "set_volume" -> {
                val action = when (a.optString("action").lowercase()) {
                    "up" -> VolumeAction.Up
                    "down" -> VolumeAction.Down
                    "set" -> VolumeAction.Set
                    "mute" -> VolumeAction.Mute
                    "unmute" -> VolumeAction.Unmute
                    "status" -> VolumeAction.Status
                    else -> error("Bilinmeyen ses işlemi: ${a.optString("action")}")
                }
                val percent = a.optFloatOrNull("percent")?.let { Math.round(it).coerceIn(0, 100) }
                if (action == VolumeAction.Set && percent == null) error("'set' için percent (0–100) gerekli.")
                val steps = a.optFloatOrNull("steps")?.let { Math.round(it).coerceIn(1, 10) } ?: 2
                backend.volume(action, percent, steps)
            }
            "current_location" -> backend.location()
            else -> error("Böyle bir araç yok: ${call.name}")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        JSONObject().put("error", e.message ?: e.javaClass.simpleName)
    }

    // ---- Şema yardımcıları --------------------------------------------------

    private fun fn(name: String, description: String, parameters: JSONObject, required: List<String> = emptyList()): JSONObject {
        if (required.isNotEmpty()) parameters.put("required", JSONArray(required))
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters)
            // Araç bitene kadar model bekler; sonuçla birlikte konuşur.
            .put("behavior", "BLOCKING")
    }

    private fun props(vararg p: Pair<String, JSONObject>): JSONObject {
        val o = JSONObject()
        p.forEach { (k, v) -> o.put(k, v) }
        return JSONObject().put("type", "object").put("properties", o)
    }

    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun num(d: String) = JSONObject().put("type", "number").put("description", d)
    private fun int(d: String) = JSONObject().put("type", "integer").put("description", d)
    private fun bool(d: String) = JSONObject().put("type", "boolean").put("description", d)
    private fun enumStr(d: String, values: List<String>) = str(d).put("enum", JSONArray(values))

    private fun JSONObject.optFloatOrNull(k: String): Float? =
        if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() }?.toFloat() else null

    private fun JSONObject.optBoolOrNull(k: String): Boolean? = if (has(k) && !isNull(k)) {
        when (val v = opt(k)) {
            is Boolean -> v
            is String -> v.equals("true", true)
            else -> null
        }
    } else null
}
