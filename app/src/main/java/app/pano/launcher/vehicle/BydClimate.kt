package app.pano.launcher.vehicle

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * BYD klimasını [ClimateState] ile iki yönlü eşler: araçtaki değişiklik ekrana gelir,
 * ekrandaki değişiklik araca gider.
 *
 * Komutlar DiLink 3.0'daki `BYDAutoAcDevice`'in gönderdiği biçimle aynıdır (cihaz türü 1000,
 * her komutun yanında kontrol kaynağı = 0 "arayüz tuşu"). O sınıf imza izni istediği için
 * komutlar doğrudan genel veri yoluna ([BydBus.write]) yazılır.
 *
 * Yüz/ayak üfleme, koltuk ısıtma ve senkron şimdilik araca gitmiyor; yalnızca ekranda kalıyor.
 */
object BydClimate {
    private const val TAG = "BydClimate"
    private const val AC_DEVICE = 1000
    private const val SOURCE_UI = 0
    private const val UNIT_CELSIUS = 1
    private const val POLL_MS = 1000L

    /** Kullanıcı dokunduktan sonra aracın yeni değeri yansıtması için beklenen süre. */
    private const val SETTLE_MS = 2500L

    /** Aracın bildirdiği klima durumu. */
    private data class Car(
        val power: Boolean,
        val driver: Int,
        val passenger: Int,
        val wind: Int,
        val auto: Boolean,
        val ac: Boolean,
        val recirculation: Boolean,
        val defrost: Boolean,
        val rearDefrost: Boolean,
    )

    @Volatile private var last: Car? = null
    @Volatile private var lastWriteAt = 0L
    private var attached = false

    /** BYD araçtaysa eşlemeyi başlatır; değilse hiçbir şey yapmaz. Ana iş parçacığından çağrılmalı. */
    fun attach(context: Context, s: ClimateState) {
        if (attached) return
        attached = true
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val first = withContext(Dispatchers.IO) { if (BydBus.init(app)) read() else null }
            if (first == null) {
                Log.i(TAG, "BYD klima okunamadı; klima yalnızca ekranda")
                return@launch
            }
            Log.i(TAG, "BYD klima bağlandı: $first")
            withContext(Dispatchers.IO) { VehicleFeatures.readByd() }
            s.step = 1f
            s.minTemp = 17f
            s.maxTemp = 33f
            last = first
            apply(first, s)
            s.linked = true

            // Ekrandaki değişiklikler: son bilinen araç durumundan farklı olan alanlar gönderilir.
            launch {
                snapshotFlow { want(s) }.collect { w ->
                    val car = last ?: return@collect
                    if (w == car) return@collect
                    lastWriteAt = SystemClock.uptimeMillis()
                    last = withContext(Dispatchers.IO) { push(car, w) }
                }
            }

            // Araçtaki değişiklikler (fiziksel tuşlar, sesli komut, BYD'nin kendi ekranı).
            while (isActive) {
                delay(POLL_MS)
                val car = withContext(Dispatchers.IO) { read() } ?: continue
                if (SystemClock.uptimeMillis() - lastWriteAt < SETTLE_MS) continue
                if (car != last) {
                    last = car
                    apply(car, s)
                }
            }
        }
    }

    private fun read(): Car? {
        fun int(field: String) = BydBus.readInt("Ac", field)
        val power = int("AC_POWER_STATE") ?: return null
        return Car(
            power = power == 1,
            driver = temp(int("AC_TEMP_MAIN")) ?: last?.driver ?: 22,
            passenger = temp(int("AC_TEMP_DEPUTY")) ?: last?.passenger ?: 22,
            wind = (int("AC_WIND_LEVEL") ?: 0).coerceIn(0, 7),
            auto = int("AC_CTRL_MODE") == 0,
            ac = int("AC_COMPRESSOR_MODE") == 1,
            recirculation = int("AC_CYCLE_MODE") == 1,
            defrost = int("AC_DEFROST_FRONT_STATE") == 1,
            rearDefrost = int("AC_DEFROST_REAR_STATE") == 1,
        )
    }

    /** Ham sıcaklık: 17–33 tam derece, 34–66 yarım derece (×2). Aralık dışı = bilinmiyor. */
    private fun temp(raw: Int?): Int? = when (raw) {
        null -> null
        in 17..33 -> raw
        in 34..66 -> (raw / 2f).roundToInt()
        else -> null
    }

    /** Ekran durumu, araç durumu biçiminde. Fan 0 = klima kapalı. */
    private fun want(s: ClimateState) = Car(
        power = s.fan > 0,
        driver = s.driver.roundToInt(),
        passenger = s.passenger.roundToInt(),
        wind = s.fan,
        auto = s.auto,
        ac = s.ac,
        recirculation = s.recirculation,
        defrost = s.defrost,
        rearDefrost = s.rearDefrost,
    )

    private fun apply(car: Car, s: ClimateState) {
        s.driver = car.driver.toFloat()
        s.passenger = car.passenger.toFloat()
        // Kapalıyken fan 0 gösterilir; Pano'da "kapalı" fan seviyesiyle ifade ediliyor.
        s.fan = if (car.power) car.wind.coerceAtLeast(1) else 0
        s.auto = car.auto
        s.ac = car.ac
        s.recirculation = car.recirculation
        s.defrost = car.defrost
        s.rearDefrost = car.rearDefrost
        if (s.sync && s.passenger != s.driver) s.sync = false
    }

    /** Farkları araca yazar; gönderilenlerle güncellenmiş "son bilinen" durumu döndürür. */
    private fun push(car: Car, w: Car): Car {
        fun send(fields: List<String>, vararg values: Int) =
            BydBus.write(AC_DEVICE, "Ac", fields + "AC_CTRL_SOURCE_SET", values + SOURCE_UI)

        if (!w.power) {
            if (car.power) send(listOf("AC_POWER_STATE_SET"), 0)
            // Kapanırken diğer düğmeler (A/C, otomatik) araca ayrıca gönderilmez.
            return car.copy(power = false, defrost = w.defrost, rearDefrost = w.rearDefrost).also {
                if (w.defrost != car.defrost) send(listOf("AC_DEFROST_FRONT_STATE_SET"), w.defrost.bit)
                if (w.rearDefrost != car.rearDefrost) send(listOf("AC_DEFROST_REAR_STATE_SET"), w.rearDefrost.bit)
            }
        }
        if (!car.power) send(listOf("AC_POWER_STATE_SET"), 1)
        if (w.driver != car.driver) {
            send(listOf("AC_TEMPERATURE_UNIT_SET", "AC_TEMP_MAIN_SET", "AC_TEMP_DEPUTY_SET", "AC_TEMP_REAR_SET"), UNIT_CELSIUS, w.driver, 0, 0)
        }
        if (w.passenger != car.passenger) {
            send(listOf("AC_TEMPERATURE_UNIT_SET", "AC_TEMP_MAIN_SET", "AC_TEMP_DEPUTY_SET", "AC_TEMP_REAR_SET"), UNIT_CELSIUS, 0, w.passenger, 0)
        }
        if (w.auto != car.auto) send(listOf("AC_CTRL_MODE_SET"), if (w.auto) 0 else 1)
        // Otomatik moda geçerken fanı araç seçer; elle seviye yalnızca manuelde gönderilir.
        if (!w.auto && w.wind != car.wind) send(listOf("AC_WIND_LEVEL_SET"), w.wind)
        if (w.ac != car.ac) send(listOf("AC_COMPRESSOR_MODE_SET"), w.ac.bit)
        if (w.recirculation != car.recirculation) send(listOf("AC_CYCLE_MODE_SET"), w.recirculation.bit)
        if (w.defrost != car.defrost) send(listOf("AC_DEFROST_FRONT_STATE_SET"), w.defrost.bit)
        if (w.rearDefrost != car.rearDefrost) send(listOf("AC_DEFROST_REAR_STATE_SET"), w.rearDefrost.bit)
        return w
    }

    private val Boolean.bit get() = if (this) 1 else 0
}
