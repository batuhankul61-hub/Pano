package app.pano.launcher.vehicle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Eski, sabit araç bilgisi. Gerçek veri artık [VehicleHub.state]'ten geliyor; bu sınıf
 * yalnızca henüz hub'a bağlanmamış CarPlay düzeni için duruyor (hep örnek değer,
 * `isPreview = true`). CarPlay bağlandığında silinebilir.
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
    fun current(): VehicleInfo = VehicleHub.PREVIEW.let {
        VehicleInfo(
            name = it.name,
            rangeKm = it.rangeKm ?: 0,
            batteryPercent = it.batteryPercent ?: 0,
            locked = it.locked ?: true,
            consumptionKwh100 = it.consumptionKwh100 ?: 0f,
            isPreview = true,
        )
    }
}

/**
 * Klima ayarları. BYD araçta [BydClimate] bunları araçla iki yönlü eşler ([linked] = true);
 * başka cihazlarda yalnızca ekranda tutulur.
 */
class ClimateState {
    /** Araca bağlı mı; değilse ayarlar yalnızca Pano'da kalır. */
    var linked by mutableStateOf(false)

    /** Sıcaklık adımı ve aralığı; BYD tam derece ve 17–33 °C kabul ediyor. */
    var step = 0.5f
    var minTemp = 16f
    var maxTemp = 30f

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

    /**
     * Araçta ne var: tek bölgeli klimada tek sıcaklık gösterilir, koltuk ısıtma yoksa gizlenir.
     * Bilinmeyen cihazlarda (önizleme) her şey açık.
     */
    var dualZone by mutableStateOf(true)
    var seatHeating by mutableStateOf(true)

    /** Araca bağlıyken yalnızca araca gerçekten giden ayarlar gösterilir (yüz/ayak henüz gitmiyor). */
    val airDirectionSupported: Boolean get() = !linked

    val isOff: Boolean get() = fan == 0

    /** [delta] yalnızca yön bildirir; adım [step] kadardır. */
    fun changeDriver(delta: Float) = setDriverTemp(driver + if (delta > 0) step else -step)

    /** Sürgüden gelen sıcaklık; adıma yuvarlanır. Tek bölgede ya da senkronda yolcu da izler. */
    fun setDriverTemp(t: Float) {
        driver = clamp(t)
        if (sync || !dualZone) passenger = driver
    }

    fun setPassengerTemp(t: Float) {
        if (sync || !dualZone) setDriverTemp(t) else passenger = clamp(t)
    }

    fun changePassenger(delta: Float) {
        if (sync) changeDriver(delta) else passenger = clamp(passenger + if (delta > 0) step else -step)
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

    private fun clamp(t: Float) = ((t / step).roundToInt() * step).coerceIn(minTemp, maxTemp)
}

private val turkish = Locale.forLanguageTag("tr-TR")

/** 22 → "22°", 22.5 → "22,5°" */
fun shortTemp(t: Float): String =
    if (t % 1f == 0f) "${t.toInt()}°" else String.format(turkish, "%.1f°", t)

/** Her zaman bir ondalık: "22,0°" */
fun longTemp(t: Float): String = String.format(turkish, "%.1f°", t)

fun decimal(value: Float): String = String.format(turkish, "%.1f", value)
