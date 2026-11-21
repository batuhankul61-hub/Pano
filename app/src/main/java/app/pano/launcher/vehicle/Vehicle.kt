package app.pano.launcher.vehicle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Araç bilgileri. BYD'nin araç servislerine henüz bağlı değiliz; bu yüzden şimdilik
 * örnek değerler gösteriliyor ve ekranda "Önizleme" etiketi var. Gerçek bağlantı
 * yazıldığında yalnızca bu dosyanın değişmesi yeterli olacak.
 */
data class VehicleInfo(
    val name: String,
    val rangeKm: Int,
    val batteryPercent: Int,
    val locked: Boolean,
    val consumptionKwh100: Float,
    val isPreview: Boolean,
)

object VehicleSource {
    fun current(): VehicleInfo = VehicleInfo(
        name = "Aracım",
        rangeKm = 412,
        batteryPercent = 78,
        locked = true,
        consumptionKwh100 = 14.8f,
        isPreview = true,
    )
}

/** Klima ayarları. Şimdilik sadece ekranda tutuluyor, araca gönderilmiyor. */
class ClimateState {
    var driver by mutableFloatStateOf(22f)
    var passenger by mutableFloatStateOf(21f)
    var fan by mutableIntStateOf(3)
    var auto by mutableStateOf(true)
    var ac by mutableStateOf(true)
    var face by mutableStateOf(true)
    var foot by mutableStateOf(true)
    var defrost by mutableStateOf(false)
    var driverSeat by mutableIntStateOf(1)
    var passengerSeat by mutableIntStateOf(0)
    var sync by mutableStateOf(false)
    var recirculation by mutableStateOf(false)
    var rearDefrost by mutableStateOf(false)

    val isOff: Boolean get() = fan == 0

    fun changeDriver(delta: Float) {
        driver = clamp(driver + delta)
        if (sync) passenger = driver
    }

    fun changePassenger(delta: Float) {
        if (sync) changeDriver(delta) else passenger = clamp(passenger + delta)
    }

    fun setSyncEnabled(on: Boolean) {
        sync = on
        if (on) passenger = driver
    }

    fun setFanLevel(level: Int) {
        fan = level.coerceIn(0, 7)
        auto = false
    }

    fun powerOff() {
        fan = 0
        ac = false
        auto = false
    }

    private fun clamp(t: Float) = ((t * 2).roundToInt() / 2f).coerceIn(16f, 30f)
}

private val turkish = Locale.forLanguageTag("tr-TR")

/** 22 → "22°", 22.5 → "22,5°" */
fun shortTemp(t: Float): String =
    if (t % 1f == 0f) "${t.toInt()}°" else String.format(turkish, "%.1f°", t)

/** Her zaman bir ondalık: "22,0°" */
fun longTemp(t: Float): String = String.format(turkish, "%.1f°", t)

fun decimal(value: Float): String = String.format(turkish, "%.1f", value)
