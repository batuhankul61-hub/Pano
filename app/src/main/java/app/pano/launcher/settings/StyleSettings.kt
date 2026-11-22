package app.pano.launcher.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val label: String) { Auto("Otomatik"), Light("Gündüz"), Dark("Gece") }

enum class DockStyle(val label: String) { Mono("Tek renk"), Colored("Renkli") }

/** null renk = "tek renk": etkin öğeler siyah/beyaz olur. */
enum class AccentChoice(val label: String, val color: Color?) {
    Mono("Tek renk", null),
    Blue("Mavi", Color(0xFF0A84FF)),
    Green("Yeşil", Color(0xFF30D158)),
    Orange("Turuncu", Color(0xFFFF9F0A)),
    Red("Kırmızı", Color(0xFFFF453A)),
    Purple("Mor", Color(0xFFBF5AF2)),
}

enum class FontChoice(val label: String) { Inter("Inter"), System("Sistem") }

enum class MapStyle(val label: String, val dayUrl: String) {
    Clean("Sade", "https://tiles.openfreemap.org/styles/positron"),
    Colorful("Renkli", "https://tiles.openfreemap.org/styles/liberty"),
    Bright("Parlak", "https://tiles.openfreemap.org/styles/bright"),
}

/** Ana ekran düzeni: klasik Pano kartları ya da iOS / CarPlay havasında cam arayüz. */
enum class LayoutStyle(val label: String) { Pano("Klasik"), CarPlay("CarPlay") }

data class StyleSettings(
    val developerMode: Boolean = false,
    /** Araç ekranında WhatsApp / SMS mesajları için kısa banner. */
    val showMessages: Boolean = true,
    /** Aracın alt sistem çubuğunu (BYD: geri, ev, klima) gizler; alttan kaydırınca kısa süre görünür. */
    val hideSystemBar: Boolean = false,
    /**
     * Sürüşte kısıtlama: araç hareket ederken kartları gizler, Uygulamalar/Ayarlar ve web
     * içeriğini kilitler. Geliştirme sırasında her şeyin test edilebilmesi için varsayılan kapalı;
     * son kullanıcı sürümünde açık olması düşünülecek.
     */
    val drivingLock: Boolean = false,
    val layout: LayoutStyle = LayoutStyle.Pano,
    val theme: ThemeMode = ThemeMode.Auto,
    val dock: DockStyle = DockStyle.Mono,
    val accent: AccentChoice = AccentChoice.Mono,
    val font: FontChoice = FontChoice.Inter,
    val map: MapStyle = MapStyle.Clean,
)

val LocalStyle = compositionLocalOf { StyleSettings() }

/**
 * Stil ayarlarını cihazda saklar. Uygulama yeniden açıldığında son seçim geri gelir.
 * "Varsayılanlara dön" her şeyi ilk haline getirir.
 */
object Settings {
    private const val PREFS = "pano_style"
    private var prefs: SharedPreferences? = null
    private val _state = mutableStateOf(StyleSettings())
    val state: State<StyleSettings> = _state

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val d = StyleSettings()
        _state.value = StyleSettings(
            developerMode = p.getBoolean("developerMode", d.developerMode),
            showMessages = p.getBoolean("showMessages", d.showMessages),
            hideSystemBar = p.getBoolean("hideSystemBar", d.hideSystemBar),
            drivingLock = p.getBoolean("drivingLock", d.drivingLock),
            layout = enumOr(p.getString("layout", null), d.layout),
            theme = enumOr(p.getString("theme", null), d.theme),
            dock = enumOr(p.getString("dock", null), d.dock),
            accent = enumOr(p.getString("accent", null), d.accent),
            font = enumOr(p.getString("font", null), d.font),
            map = enumOr(p.getString("map", null), d.map),
        )
    }

    fun update(transform: (StyleSettings) -> StyleSettings) {
        val s = transform(_state.value)
        _state.value = s
        prefs?.edit()
            ?.putBoolean("developerMode", s.developerMode)
            ?.putBoolean("showMessages", s.showMessages)
            ?.putBoolean("hideSystemBar", s.hideSystemBar)
            ?.putBoolean("drivingLock", s.drivingLock)
            ?.putString("layout", s.layout.name)
            ?.putString("theme", s.theme.name)
            ?.putString("dock", s.dock.name)
            ?.putString("accent", s.accent.name)
            ?.putString("font", s.font.name)
            ?.putString("map", s.map.name)
            ?.apply()
    }

    /** Geliştirici modu ve mesaj ayarı korunur, sadece stiller sıfırlanır. */
    fun resetStyles() = update {
        StyleSettings(developerMode = it.developerMode, showMessages = it.showMessages, hideSystemBar = it.hideSystemBar, drivingLock = it.drivingLock)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
