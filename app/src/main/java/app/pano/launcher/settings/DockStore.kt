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
    Apps("Uygulamalar", "Gezinme"),
    CarSettings("Araç ayarları", "Ayarlar"),
    PanoSettings("Pano ayarları", "Ayarlar"),
}

/** Dock seçimini saklar. Uygulama yeniden açıldığında son seçim geri gelir. */
object DockStore {
    private const val PREFS = "pano_dock"
    private const val KEY = "controls"

    val DEFAULT: Set<DockControl> = setOf(
        DockControl.Home, DockControl.Temperature, DockControl.Auto, DockControl.AC, DockControl.Fan,
        DockControl.ClimateDetail, DockControl.PassengerTemperature, DockControl.CarSettings,
    )

    private var prefs: SharedPreferences? = null
    private val _state = mutableStateOf(DEFAULT)
    val state: State<Set<DockControl>> = _state

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString(KEY, null) ?: return
        _state.value = saved.split(',').mapNotNull { name -> DockControl.entries.firstOrNull { it.name == name } }.toSet()
    }

    fun toggle(control: DockControl) = save(if (control in _state.value) _state.value - control else _state.value + control)

    fun reset() = save(DEFAULT)

    private fun save(value: Set<DockControl>) {
        _state.value = value
        prefs?.edit()?.putString(KEY, value.joinToString(",") { it.name })?.apply()
    }
}
