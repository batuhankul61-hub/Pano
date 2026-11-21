package app.pano.launcher.navigation

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigasyon uygulamasının bildiriminden okunan sıradaki manevra.
 * Alanlar bildirimden "en iyi tahminle" çıkarılır; her biri boş/null olabilir.
 */
data class NavInstruction(
    /** Manevra metni, örn. "Sağa dönün" / "Turn right". */
    val maneuver: String,
    /** Manevraya kalan mesafe, örn. "300 m" / "1,2 km". */
    val distance: String?,
    /** Dönülecek / ilerlenecek sokak adı. */
    val street: String?,
    /** Varış saati ve/veya kalan süre, örn. "14:32 varış · 12 dk". */
    val eta: String?,
    /** Manevra oku (bildirimin largeIcon'u). */
    val icon: Bitmap?,
    /** Bildirimi gönderen paket, örn. "com.google.android.apps.maps". */
    val packageName: String,
    /** Bildirimin son güncellenme zamanı (ms). */
    val postedAt: Long,
)

/**
 * Google Maps, Yandex Navigasyon ve Waze'in navigasyon sırasında gösterdiği sürekli
 * bildirimi okuyup [state] üzerinden yayınlar. Veriyi [app.pano.launcher.media.MediaListenerService]
 * besler; ek izin gerekmez (bildirim erişimi yeterli).
 *
 * Kullanım:
 * ```
 * val nav by NavHub.state.collectAsState()
 * nav?.let { Text("${it.distance.orEmpty()} ${it.maneuver}") }
 * ```
 */
object NavHub {
    /** Desteklenen navigasyon uygulamaları. */
    val NAV_PACKAGES = setOf(
        "com.google.android.apps.maps",      // Google Maps
        "com.google.android.apps.navlite",   // Google Maps Go / Navigation for Go
        "ru.yandex.yandexnavi",              // Yandex Navigasyon
        "ru.yandex.yandexmaps",              // Yandex Haritalar (navigasyon modu)
        "com.waze",                          // Waze
    )

    private val _state = MutableStateFlow<NavInstruction?>(null)
    val state: StateFlow<NavInstruction?> = _state.asStateFlow()

    /** Şu an gösterilen bildirimin anahtarı; başka bildirimin kaldırılması durumu silmesin diye. */
    @Volatile
    private var currentKey: String? = null

    fun isNavPackage(packageName: String): Boolean = packageName in NAV_PACKAGES

    /** Bildirim geldi/güncellendi. Navigasyon bildirimi değilse yok sayılır. */
    fun onPosted(context: Context, sbn: StatusBarNotification) {
        if (!isNavPackage(sbn.packageName) || !looksLikeNavigation(sbn)) return
        val parsed = parse(context, sbn) ?: return
        currentKey = sbn.key
        _state.value = parsed
    }

    /** Bildirim kaldırıldı; şu an gösterdiğimiz bildirimse durumu temizle. */
    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.key == currentKey) clear()
    }

    /** Dinleyici bağlantısı koptuğunda vb. durumu sıfırlar. */
    fun clear() {
        currentKey = null
        _state.value = null
    }

    /**
     * Navigasyon uygulamaları başka bildirimler de gösterir (konum paylaşımı, trafik
     * uyarısı, indirme…). Sadece "devam eden" ya da navigasyon kategorisindekileri al.
     */
    private fun looksLikeNavigation(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_NAVIGATION) return true
        // Kategori belirtmeyen sürümler için: sürekli (ongoing) bildirim. Konum paylaşımı
        // gibi ongoing ama manevra içermeyen bildirimler parse() içinde elenir.
        return sbn.isOngoing
    }

    // ---------------------------------------------------------------------------------
    // Ayrıştırma
    //
    // Paketlere göre gözlemlenen tipik alan yerleşimi (sürüme ve dile göre değişir):
    //  • Google Maps: title = mesafe ("300 m"), text = manevra + sokak
    //    ("Atatürk Cd. yönüne sağa dönün"), subText = ETA ("12 dk · 5 km · 14:32 varış"),
    //    largeIcon = manevra oku. Bazı sürümlerde title = "300 m · Sağa dönün".
    //  • Yandex Navigasyon: title = manevra/sokak, text = mesafe ya da tersi; bazen
    //    bigText'te birleşik metin. largeIcon = manevra oku (her sürümde yok).
    //  • Waze: title = sokak ya da "Sağa dönün", text = "300 m içinde…"; subText ETA
    //    içerebilir. Çoğu sürümde largeIcon = manevra oku.
    // Bu yüzden konumuna değil içeriğine bakıyoruz: tüm metin alanlarını toplayıp
    // mesafe, ETA ve manevra kalıplarına göre sınıflandırıyoruz.
    // ---------------------------------------------------------------------------------

    /** "300 m", "1,2 km", "0.3 mi", "500 ft" vb. */
    private val distanceRegex = Regex(
        """(?<![\w.,])(\d+(?:[.,]\d+)?)\s?(km|m|mi|ft|yd|metre|meter|mil|км|м)(?![\p{L}])""",
        RegexOption.IGNORE_CASE,
    )

    /** Saat ("14:32") veya süre ("12 dk", "1 sa 5 dk", "12 min", "1 h"). */
    private val etaRegex = Regex(
        """\b\d{1,2}:\d{2}\b|\b\d+\s?(dk|dakika|sa|saat|min|mins|minute|minutes|h|hr|hrs|hour|hours|мин|ч)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Manevra anahtar kelimeleri (TR/EN/RU). */
    private val maneuverRegex = Regex(
        """dön|sağa|sola|devam|düz|çık|kavşak|ilerle|varış|hedef|u dönüşü|katıl|""" +
            """turn|keep|exit|roundabout|continue|head|merge|u-turn|take|arrive|destination|bear|slight|""" +
            """поверн|направо|налево|прямо|съезд|разворот|кольц""",
        RegexOption.IGNORE_CASE,
    )

    /** Sokak adını manevra metninden ayırmak için bağlaçlar. */
    private val streetSplitRegex = Regex(
        """\s+(?:onto|on to|toward|towards|to|on)\s+|\s+(?:yönüne|üzerine|caddesine)\b|\s*[→›»]\s*""",
        RegexOption.IGNORE_CASE,
    )

    private fun parse(context: Context, sbn: StatusBarNotification): NavInstruction? {
        val n = sbn.notification
        val extras = n.extras ?: Bundle.EMPTY

        val title = extras.text(Notification.EXTRA_TITLE_BIG) ?: extras.text(Notification.EXTRA_TITLE)
        val text = extras.text(Notification.EXTRA_TEXT)
        val bigText = extras.text(Notification.EXTRA_BIG_TEXT)
        val subText = extras.text(Notification.EXTRA_SUB_TEXT)
        val info = extras.text(Notification.EXTRA_INFO_TEXT)
        val summary = extras.text(Notification.EXTRA_SUMMARY_TEXT)
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.ifBlank { null } }.orEmpty()

        // Ana alanlar (manevra/mesafe/sokak bunlarda aranır). bigText genelde text'in
        // uzun hali; text yoksa onu kullan.
        val primary = listOfNotNull(title, text ?: bigText) + lines
        // Yan alanlar: ETA çoğunlukla burada.
        val secondary = listOfNotNull(subText, info, summary)

        if (primary.isEmpty()) return null

        // 1) Mesafe: ilk eşleşen kalıp. Sadece mesafeden oluşan alan "tüketilir".
        var distance: String? = null
        val remaining = mutableListOf<String>()
        for (field in primary) {
            val m = if (distance == null) distanceRegex.find(field) else null
            if (m != null && !etaRegex.containsMatchIn(field.removeRange(m.range))) {
                distance = m.value.trim()
                val rest = field.removeRange(m.range).trim(' ', '·', '-', '–', ',', '•')
                    .removePrefix("içinde").removePrefix("in ").trim()
                if (rest.isNotEmpty()) remaining += rest
            } else {
                remaining += field
            }
        }

        // 2) ETA: önce yan alanlarda, yoksa ana alanlarda saat/süre içeren satır.
        var eta = secondary.firstOrNull { etaRegex.containsMatchIn(it) }
        if (eta == null) {
            val etaField = remaining.firstOrNull { etaRegex.containsMatchIn(it) && !maneuverRegex.containsMatchIn(it) }
            if (etaField != null) {
                eta = etaField
                remaining.remove(etaField)
            }
        }

        // 3) Manevra: anahtar kelime içeren ilk alan; yoksa ilk kalan alan.
        val maneuverField = remaining.firstOrNull { maneuverRegex.containsMatchIn(it) } ?: remaining.firstOrNull()
        var maneuver = maneuverField.orEmpty()
        var street: String? = null

        // 4) Sokak: manevra alanında "onto X" / "X yönüne" gibi bir ayrım varsa böl,
        //    yoksa manevra olmayan diğer alanı sokak say.
        if (maneuverField != null) {
            val split = streetSplitRegex.find(maneuverField)
            if (split != null) {
                val before = maneuverField.substring(0, split.range.first).trim()
                val after = maneuverField.substring(split.range.last + 1).trim()
                if (split.value.contains(Regex("yönüne|üzerine|caddesine", RegexOption.IGNORE_CASE))) {
                    // Türkçe: "Atatürk Cd. yönüne sağa dönün" → sokak önce gelir.
                    street = before.ifBlank { null }
                    maneuver = after.ifBlank { maneuverField }
                } else {
                    maneuver = before.ifBlank { maneuverField }
                    street = after.ifBlank { null }
                }
            }
        }
        if (street == null) {
            street = remaining.firstOrNull { it != maneuverField && !maneuverRegex.containsMatchIn(it) }
        }

        // Ne mesafe ne de manevra kelimesi varsa bu bir yönlendirme bildirimi değil
        // (örn. "Konumunuz paylaşılıyor", "Waze arka planda çalışıyor").
        if (distance == null && maneuverField?.let { maneuverRegex.containsMatchIn(it) } != true) return null

        if (maneuver.isBlank()) {
            // Yalnızca sokak varsa onu manevra satırı olarak göster.
            maneuver = street ?: return null
            street = null
        }

        return NavInstruction(
            maneuver = maneuver,
            distance = distance,
            street = street,
            eta = eta,
            icon = largeIcon(context, n, extras),
            packageName = sbn.packageName,
            postedAt = sbn.postTime,
        )
    }

    /** largeIcon; Bitmap ya da Icon olarak gelebilir. Yüklenemezse null. */
    private fun largeIcon(context: Context, n: Notification, extras: Bundle): Bitmap? = try {
        @Suppress("DEPRECATION")
        (extras.get(Notification.EXTRA_LARGE_ICON_BIG) as? Bitmap)
            ?: (extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap)
            ?: n.getLargeIcon()?.toBitmap(context)
            ?: (extras.get(Notification.EXTRA_LARGE_ICON) as? Icon)?.toBitmap(context)
    } catch (_: Exception) {
        null
    }

    private fun Icon.toBitmap(context: Context): Bitmap? =
        loadDrawable(context)?.let { d ->
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
            d.toBitmap(w, h)
        }

    private fun Bundle.text(key: String): String? =
        getCharSequence(key)?.toString()?.trim()?.ifBlank { null }
}
