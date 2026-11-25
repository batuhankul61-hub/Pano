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
import app.pano.launcher.BuildConfig
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

/**
 * Aracın kendi lastik basıncı izleme (TPMS) uyarısı; Pano eşik uydurmaz. [severe] true ise
 * kırmızı (hızlı kaçak ya da göstergede kırmızı), değilse turuncu.
 */
data class TyreAlert(val label: String, val severe: Boolean)

/** Köşe başına TPMS uyarısı (null = uyarı yok ya da bilinmiyor) ve sistem arızası. */
data class TyreAlerts(
    val frontLeft: TyreAlert? = null,
    val frontRight: TyreAlert? = null,
    val rearLeft: TyreAlert? = null,
    val rearRight: TyreAlert? = null,
    val systemFault: Boolean = false,
) {
    val all: List<TyreAlert> get() = listOfNotNull(frontLeft, frontRight, rearLeft, rearRight)
    val any: Boolean get() = all.isNotEmpty() || systemFault
}

/**
 * Gövde açıklıkları (kapılar, bagaj, kaput, camlar, sunroof). Her alan null = bilinmiyor
 * (araçta yok ya da okunamadı); true = açık. Atto 2 yolcu camlarını veri yoluna göndermiyor,
 * orada yalnızca sürücü camı bilinir.
 */
data class Openings(
    val doorFrontLeft: Boolean? = null,
    val doorFrontRight: Boolean? = null,
    val doorRearLeft: Boolean? = null,
    val doorRearRight: Boolean? = null,
    val trunk: Boolean? = null,
    val hood: Boolean? = null,
    val windowFrontLeft: Boolean? = null,
    val windowFrontRight: Boolean? = null,
    val windowRearLeft: Boolean? = null,
    val windowRearRight: Boolean? = null,
    val sunroof: Boolean? = null,
) {
    /** Açık olan her şey, önem sırasıyla (kapı/bagaj/kaput önce, camlar sonra). */
    val open: List<Opening>
        get() = buildList {
            if (doorFrontLeft == true) add(Opening("Sol ön kapı", Opening.Kind.Door))
            if (doorFrontRight == true) add(Opening("Sağ ön kapı", Opening.Kind.Door))
            if (doorRearLeft == true) add(Opening("Sol arka kapı", Opening.Kind.Door))
            if (doorRearRight == true) add(Opening("Sağ arka kapı", Opening.Kind.Door))
            if (trunk == true) add(Opening("Bagaj", Opening.Kind.Trunk))
            if (hood == true) add(Opening("Kaput", Opening.Kind.Hood))
            if (windowFrontLeft == true) add(Opening("Sol ön cam", Opening.Kind.Window))
            if (windowFrontRight == true) add(Opening("Sağ ön cam", Opening.Kind.Window))
            if (windowRearLeft == true) add(Opening("Sol arka cam", Opening.Kind.Window))
            if (windowRearRight == true) add(Opening("Sağ arka cam", Opening.Kind.Window))
            if (sunroof == true) add(Opening("Sunroof", Opening.Kind.Window))
        }

    val isEmpty: Boolean
        get() = listOf(doorFrontLeft, doorFrontRight, doorRearLeft, doorRearRight, trunk, hood).all { it == null } &&
            listOf(windowFrontLeft, windowFrontRight, windowRearLeft, windowRearRight, sunroof).all { it == null }
}

/** Açık kalan tek bir gövde parçası. Kapı/bagaj/kaput cama göre daha önemli sayılır. */
data class Opening(val label: String, val kind: Kind) {
    enum class Kind { Door, Trunk, Hood, Window }
    val isMajor: Boolean get() = kind != Kind.Window
}

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
    /** Kapı/bagaj/cam durumu; önizlemede her zaman null (uydurma "kapalı" gösterilmez). */
    val openings: Openings? = null,
    /** Aracın TPMS uyarıları; sinyal yoksa null ve arayüz yalnızca değeri gösterir. */
    val tyreAlerts: TyreAlerts? = null,
    /** Sürüş modu ve rejen seviyesi; okunamazsa null (önizlemede hep null). */
    val driveMode: DriveMode? = null,
    val regen: RegenLevel? = null,
    /** Şarj durumu; sinyal yoksa null. Önizlemede yalnızca debug ayarıyla örnek değer. */
    val charge: ChargeState? = null,
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

    /**
     * Emülatörde şarj görünümünü denemek için örnek (yalnızca debug sürümde ve önizlemede;
     * arayüz "Önizleme" yazar). Aç: `run-as app.pano.launcher` ile `shared_prefs/pano_debug.xml`
     * içine `<boolean name="preview_charging" value="true" />`, sonra Pano'yu yeniden başlat.
     */
    val PREVIEW_CHARGE = ChargeState(
        plugged = true, charging = true, kind = ChargeKind.AC,
        powerKw = 6.6f, remainingMin = 14 * 60 + 29, targetPercent = 100,
    )
    private const val PREVIEW_CHARGE_BATTERY = 57

    @Volatile private var previewCharging = false
    @Volatile private var lastChargeLog: String? = null

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
        previewCharging = BuildConfig.DEBUG &&
            app.getSharedPreferences("pano_debug", Context.MODE_PRIVATE).getBoolean("preview_charging", false)
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
        val tyreAlerts: TyreAlerts? = null,
    )

    private fun readSlow() = runCatching {
        SlowReadings(
            BydSignals.outsideTempC(), BydSignals.insideTempC(), BydSignals.tyresKpa(),
            runCatching { BydSignals.consumption() }.getOrNull(),
            runCatching { BydSignals.tyreAlerts() }.getOrNull(),
        )
    }.getOrDefault(SlowReadings())

    private fun compose(byd: Boolean, slow: SlowReadings): VehicleState {
        val gps = gpsSpeedKmh()
        if (!byd) {
            val preview = PREVIEW.copy(
                speedKmh = gps,
                speedSource = if (gps != null) SpeedSource.Gps else SpeedSource.None,
            )
            // Şarj önizlemesi park halindeki aracı canlandırır: emülatörün GPS hızı yok sayılır.
            return if (previewCharging) {
                preview.copy(
                    charge = PREVIEW_CHARGE, batteryPercent = PREVIEW_CHARGE_BATTERY,
                    gear = Gear.P, speedKmh = null, speedSource = SpeedSource.None,
                )
            } else preview
        }
        val busSpeed = runCatching { BydSignals.speedKmh() }.getOrNull()
        val charge = runCatching { BydCharging.read() }.onFailure { Log.w(TAG, "şarj okunamadı: $it") }.getOrNull()
        val chargeLog = charge?.let { "takılı=${it.plugged} şarj=${it.charging} bitti=${it.finished} etkin=${it.isActive}" }
        if (chargeLog != lastChargeLog) {
            lastChargeLog = chargeLog
            Log.i(TAG, "şarj: ${chargeLog ?: "sinyal yok"}")
        }
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
            tyreAlerts = slow.tyreAlerts,
            driveMode = runCatching { BydDrive.mode() }.getOrNull(),
            regen = runCatching { BydDrive.regen() }.getOrNull(),
            // Kapı açılınca hemen görünsün diye her turda okunur.
            openings = runCatching { BydSignals.openings() }.getOrNull(),
            // Kapı kilitleri (sürücü kapısı dahil hepsi); okunamazsa null.
            locked = runCatching { VehicleControls.lockedState() }.getOrNull(),
            charge = charge,
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
