package app.pano.launcher.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * Alt şeritte (dock) gösterilebilecek kontroller. Sıra sabittir; kullanıcı yalnızca hangilerinin
 * görüneceğini seçer. Hepsi Pano'nun gerçekten yapabildiği işler (araca giden klima komutları ya da
 * uygulama/ekran açma); çalışmayan bir düğme listeye konmaz.
 */
enum class DockControl(val label: String, val group: String) {
    Back("Geri", "Gezinme"),
    Home("Ana pano", "Gezinme"),
    Temperature("Sıcaklık", "Klima"),
    Auto("Otomatik", "Klima"),
    AC("A/C", "Klima"),
    Fan("Fan", "Klima"),
    Power("Klima aç/kapa", "Klima"),
    Recirculation("Devridaim", "Klima"),
    Defrost("Ön cam buğu çözme", "Klima"),
    RearDefrost("Arka cam ısıtma", "Klima"),
    ClimateDetail("Klima detayı", "Klima"),
    PassengerTemperature("Yolcu sıcaklığı", "Klima"),
    Navigation("Navigasyon", "Gezinme"),
    Music("Müzik", "Gezinme"),
    Phone("Telefon", "Gezinme"),
    Voice("Sesli asistan", "Gezinme"),
    Split("Yarım ekran", "Gezinme"),
    Recents("Son uygulamalar", "Gezinme"),
    Rotate("Ekranı döndür", "Ayarlar"),
    Apps("Uygulamalar", "Gezinme"),
    CarSettings("Araç ayarları", "Ayarlar"),
    PanoSettings("Pano ayarları", "Ayarlar"),
}

/** Dock seçimini saklar. Uygulama yeniden açıldığında son seçim geri gelir. */
object DockStore {
    private const val PREFS = "pano_dock"
    private const val KEY = "controls"

    /** Varsayılan: aracın kendi alt çubuğundaki her şey (geri, ev, sıcaklık, yarım ekran, son uygulamalar, fan, döndürme, ayarlar, uygulamalar) + Pano'nunkiler. */
    val DEFAULT: Set<DockControl> = setOf(
        DockControl.Back, DockControl.Home, DockControl.Temperature, DockControl.Auto, DockControl.AC, DockControl.Fan,
        DockControl.ClimateDetail, DockControl.PassengerTemperature, DockControl.Voice, DockControl.Split,
        DockControl.Recents, DockControl.Rotate, DockControl.Apps, DockControl.CarSettings,
    )

    /** Sonradan eklenen kontroller; kayıtlı seçimi olan kullanıcıya da bir kez eklenir. */
    private val ADDED_LATER = listOf(
        DockControl.Voice, DockControl.Back, DockControl.Split, DockControl.Recents, DockControl.Rotate, DockControl.Apps,
    )
    private const val KEY_OFFERED = "offered"

    private var prefs: SharedPreferences? = null
    private val _state = mutableStateOf(DEFAULT)
    val state: State<Set<DockControl>> = _state

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString(KEY, null)
        if (saved != null) {
            _state.value = saved.split(',').mapNotNull { name -> DockControl.entries.firstOrNull { it.name == name } }.toSet()
        }
        // Yeni kontrolü bir kez göster; kullanıcı kaldırırsa bir daha eklenmez.
        val offered = p.getString(KEY_OFFERED, "").orEmpty().split(',').toSet()
        val fresh = ADDED_LATER.filter { it.name !in offered }
        if (fresh.isNotEmpty()) {
            if (saved != null) save(_state.value + fresh)
            p.edit().putString(KEY_OFFERED, (offered + fresh.map { it.name }).filter { it.isNotEmpty() }.joinToString(",")).apply()
        }
    }

    fun toggle(control: DockControl) = save(if (control in _state.value) _state.value - control else _state.value + control)

    fun reset() = save(DEFAULT)

    private fun save(value: Set<DockControl>) {
        _state.value = value
        prefs?.edit()?.putString(KEY, value.joinToString(",") { it.name })?.apply()
    }
}
