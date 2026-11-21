package app.pano.launcher.vehicle

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.pano.launcher.ui.hasLocationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Vites kolu konumu. M/S gibi ileri modlar D sayılır. */
enum class Gear(val label: String) { P("Park"), R("Geri"), N("Boş"), D("Sürüş") }

/** Lastik basınçları (kPa); her köşe ayrı ayrı bilinmiyor olabilir. */
data class TyrePressures(val frontLeft: Int?, val frontRight: Int?, val rearLeft: Int?, val rearRight: Int?)

/**
 * Ortalama tüketim (kWh/100 km). [recentWindow] true ise son 50 km'nin ortalaması,
 * false ise aracın tuttuğu toplam ortalama.
 */
data class Consumption(val kwh100: Float, val recentWindow: Boolean)

/** Hız verisinin nereden geldiği. */
enum class SpeedSource { Vehicle, Gps, None }

/**
 * Araç durumu. Her alan null olabilir: null = "bilinmiyor", arayüz boş/"—" göstermeli.
 *
 * [isPreview] true ise BYD veri yoluna ulaşılamıyor demektir; batarya, menzil, tüketim ve
 * kilit alanları o zaman [VehicleHub.PREVIEW] örnek değerleridir ve arayüz "Önizleme"
 * etiketini göstermek zorundadır. Hız ise önizlemede de gerçektir (GPS) ya da null.
 */
data class VehicleState(
    val name: String = "Aracım",
    val isPreview: Boolean = true,
    val speedKmh: Int? = null,
    val speedSource: SpeedSource = SpeedSource.None,
    val gear: Gear? = null,
    val batteryPercent: Int? = null,
    val rangeKm: Int? = null,
    val outsideTempC: Int? = null,
    val insideTempC: Int? = null,
    val tyresKpa: TyrePressures? = null,
    val locked: Boolean? = null,
    val consumption: Consumption? = null,
) {
    val consumptionKwh100: Float? get() = consumption?.kwh100
}

/**
 * Araç verilerinin tek kaynağı ([NavHub]/[WeatherHub] örüntüsü).
 *
 * Kaynak sırası:
 *  1. BYD genel özellik veri yolu ([BydBus]) — okunabiliyorsa gerçek veri.
 *  2. Hız için `LocationManager` GPS'i (konum izni varsa).
 *  3. İkisi de yoksa örnek değerler ve `isPreview = true`.
 */
object VehicleHub {
    private const val TAG = "VehicleHub"
    private const val POLL_MS = 1_000L
    /** Lastik ve sıcaklık yavaş değişir; her turda okumaya gerek yok. */
    private const val SLOW_EVERY = 10
    /** Bundan eski GPS konumunun hızı geçersiz sayılır. */
    private const val GPS_STALE_MS = 5_000L
    /** Sürüş sayılacak en düşük hız (km/s). */
    private const val DRIVING_KMH = 5

    /** BYD yokken gösterilen örnek değerler (eski `VehicleSource`). */
    val PREVIEW = VehicleState(
        isPreview = true,
        batteryPercent = 78,
        rangeKm = 412,
        locked = true,
        consumption = Consumption(14.8f, recentWindow = true),
    )

    private val _state = MutableStateFlow(PREVIEW)
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Araç sürülüyor mu: vites D/R ya da hız > ~5 km/s. PARK → SÜRÜŞ geçişi için. */
    val isDriving: StateFlow<Boolean> = state
        .map { s -> s.gear == Gear.D || s.gear == Gear.R || (s.speedKmh ?: 0) > DRIVING_KMH }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var job: Job? = null

    /** Okuma döngüsünü başlatır; çalışıyorsa bir şey yapmaz. Ana iş parçacığından çağrılmalı. */
    fun start(context: Context) {
        val app = context.applicationContext
        // İzin sonradan verildiyse GPS dinleyicisi burada bağlanır.
        startGps(app)
        if (job?.isActive == true) return
        scope.launch { isDriving.collect { Log.i(TAG, if (it) "SÜRÜŞ" else "PARK") } }
        job = scope.launch {
            val byd = BydBus.init(app)
            Log.i(TAG, if (byd) "BYD veri yolu bulundu" else "BYD veri yolu yok; önizleme")
            var tick = 0
            var slow = SlowReadings()
            while (isActive) {
                if (byd && tick % SLOW_EVERY == 0) slow = readSlow()
                _state.value = compose(byd, slow)
                tick++
                delay(POLL_MS)
            }
        }
    }

    private data class SlowReadings(
        val outsideTempC: Int? = null,
        val insideTempC: Int? = null,
        val tyresKpa: TyrePressures? = null,
        val consumption: Consumption? = null,
    )

    private fun readSlow() = runCatching {
        SlowReadings(
            BydSignals.outsideTempC(), BydSignals.insideTempC(), BydSignals.tyresKpa(),
            runCatching { BydSignals.consumption() }.getOrNull(),
        )
    }.getOrDefault(SlowReadings())

    private fun compose(byd: Boolean, slow: SlowReadings): VehicleState {
        val gps = gpsSpeedKmh()
        if (!byd) {
            return PREVIEW.copy(
                speedKmh = gps,
                speedSource = if (gps != null) SpeedSource.Gps else SpeedSource.None,
            )
        }
        val busSpeed = runCatching { BydSignals.speedKmh() }.getOrNull()
        val speed = busSpeed ?: gps
        return VehicleState(
            isPreview = false,
            speedKmh = speed,
            speedSource = when {
                busSpeed != null -> SpeedSource.Vehicle
                gps != null -> SpeedSource.Gps
                else -> SpeedSource.None
            },
            gear = runCatching { BydSignals.gear() }.getOrNull(),
            batteryPercent = runCatching { BydSignals.batteryPercent() }.getOrNull(),
            rangeKm = runCatching { BydSignals.rangeKm() }.getOrNull(),
            outsideTempC = slow.outsideTempC,
            insideTempC = slow.insideTempC,
            tyresKpa = slow.tyresKpa,
            consumption = slow.consumption,
        )
    }

    // ---- GPS hızı ----------------------------------------------------------

    @Volatile private var lastFix: Location? = null
    @Volatile private var lastFixAt = 0L
    @Volatile private var gpsKmh: Int? = null
    private var gpsListening = false

    private fun gpsSpeedKmh(): Int? =
        gpsKmh.takeIf { SystemClock.elapsedRealtime() - lastFixAt < GPS_STALE_MS }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = SystemClock.elapsedRealtime()
            val prev = lastFix
            gpsKmh = when {
                location.hasSpeed() -> Math.round(location.speed * 3.6f)
                // Hız taşımayan konumlarda (örn. emülatör `geo fix`) iki konumdan hesapla.
                prev != null && location.time > prev.time -> {
                    val sec = (location.time - prev.time) / 1000f
                    Math.round(prev.distanceTo(location) / sec * 3.6f)
                }
                else -> null
            }?.coerceIn(0, 300)
            lastFix = location
            lastFixAt = now
            Log.d(TAG, "GPS hızı: ${gpsKmh ?: "?"} km/s")
        }

        // API 30 öncesinde bu üçü soyut; boş da olsa yazılmalı.
        @Deprecated("API 29") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { gpsKmh = null }
    }

    @SuppressLint("MissingPermission")
    private fun startGps(context: Context) {
        if (gpsListening || !hasLocationPermission(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (LocationManager.GPS_PROVIDER !in lm.allProviders) return
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            gpsListening = true
        }.onFailure { Log.w(TAG, "GPS dinlenemedi: $it") }
    }
}
