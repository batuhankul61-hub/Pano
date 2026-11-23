/*
 * BYD genel özellik veri yoluna erişim (okuma; klima için yazma).
 *
 * Kaynak: ESMER-UI (https://github.com/akyuznet/ESMER-UI)
 *   app/src/main/java/com/example/esmerui/car/BydFeatures.kt, CarBus.kt
 *   app/src/main/java/com/example/esmerui/launcher/window/HiddenApi.kt
 * Pano için kısaltıldı: yalnızca okuma, yalnızca bu launcher'ın gösterdiği sinyaller.
 *
 * MIT License
 *
 * Copyright (c) 2026 akyuznet
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package app.pano.launcher.vehicle

import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * `BYDAutoDeviceManager` üzerinden BYD'nin genel özellik veri yolu.
 *
 * ESMER-UI'nin notuna göre bu yol, cihaza özel sarmalayıcıların (`BYDAutoAcDevice` vb.)
 * aksine imza düzeyindeki `BYDAUTO_*_GET` izinlerini istemiyor. Her sinyal bir özellik
 * kimliğiyle adreslenir; kimlikler sınıf başlatılırken atandığı için ancak çalışan bir
 * VM'den, `BYDAutoFeatureIds$<Grup>` alanlarından reflection ile okunabilir.
 *
 * BYD olmayan bir cihazda (emülatör dahil) [init] false döner ve her okuma null verir;
 * hiçbir çağrı istisna fırlatmaz.
 */
internal object BydBus {
    private const val TAG = "BydBus"
    private const val DEVICE_ANY = 0

    /** BYD'nin "okuma yok" işaretleri. */
    private const val UNAVAILABLE = 65535
    private const val NOT_FITTED = -10011

    @Volatile private var manager: Any? = null
    private var getInt: Method? = null
    private var getDouble: Method? = null
    private var setIntArray: Method? = null
    private var setInt: Method? = null
    private val ids = HashMap<String, Int?>()
    private var tried = false

    val isAvailable: Boolean get() = manager != null

    /** Yöneticiyi bulmayı bir kez dener; BYD değilse sessizce false döner. */
    @Synchronized
    fun init(context: Context): Boolean {
        if (manager != null) return true
        if (tried) return false
        tried = true
        exemptHiddenApi()
        return runCatching {
            val clazz = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
            val instance = clazz.getMethod("getInstance", Context::class.java)
                .invoke(null, context.applicationContext)
            val int = Int::class.javaPrimitiveType
            getInt = clazz.getMethod("getInt", int, int)
            getDouble = runCatching { clazz.getMethod("getDouble", int, int) }.getOrNull()
            setIntArray = runCatching {
                clazz.getMethod("setIntArray", int, IntArray::class.java, IntArray::class.java)
            }.getOrNull()
            setInt = runCatching { clazz.getMethod("setInt", int, int, int) }.getOrNull()
            manager = instance
            instance != null
        }.onFailure {
            Log.i(TAG, "BYDAutoDeviceManager yok: ${it.cause ?: it}")
        }.getOrDefault(false)
    }

    /** Sinyallerin bulunabileceği gruplar; BYD bazı sinyalleri ROM'dan ROM'a taşıyor. */
    private val GROUPS = listOf(
        "Statistic", "Speed", "Gearbox", "Instrument", "Ac", "Sensor", "Bodywork",
        "Energy", "Charging", "Tyre", "Vehicle", "Setting",
    )

    @Synchronized
    private fun featureId(group: String, field: String): Int? = ids.getOrPut("$group.$field") {
        lookup(group, field) ?: GROUPS.asSequence()
            .filter { it != group }
            .mapNotNull { lookup(it, field) }
            .firstOrNull()
    }

    private fun lookup(group: String, field: String): Int? = runCatching {
        Class.forName("android.hardware.bydauto.BYDAutoFeatureIds\$$group").getField(field).getInt(null)
    }.getOrNull()
        // DiLink 3.0'da yazma kimlikleri (…_SET) üst sınıfta da duruyor.
        ?: runCatching { Class.forName("android.hardware.bydauto.BYDAutoFeatureIds").getField(field).getInt(null) }.getOrNull()

    /** Özellik kimliği adı. */
    fun idOf(group: String, field: String): Int? = featureId(group, field)

    /** Tek sinyal yazar (BYD cihaz sınıflarındaki `set(tür, kimlik, değer)` ile aynı). */
    fun writeOne(deviceType: Int, featureId: Int, value: Int): Int? {
        val target = manager ?: return null
        val method = setInt ?: return null
        return runCatching { method.invoke(target, deviceType, featureId, value) as? Int }
            .onFailure { Log.w(TAG, "yazma 0x${Integer.toHexString(featureId)}: ${it.cause ?: it}") }
            .getOrNull()
            .also { Log.i(TAG, "yazma tür=$deviceType kimlik=0x${Integer.toHexString(featureId)} değer=$value → $it") }
    }

    /**
     * Belirli bir cihaz türüyle okuma. Bazı sinyaller (ör. kapı kilidi, tür 1041) genel türle (0)
     * okununca boş döner; BYD'nin kendi cihaz sınıfı gibi kendi türüyle okunmalı.
     */
    fun readFor(deviceType: Int, group: String, field: String): Int? {
        val target = manager ?: return null
        val id = featureId(group, field) ?: return null
        return runCatching { getInt?.invoke(target, deviceType, id) as? Int }
            .onFailure { Log.w(TAG, "okuma $group.$field (tür $deviceType): ${it.cause ?: it}") }
            .getOrNull()
            ?.takeIf { it != UNAVAILABLE && it != NOT_FITTED }
    }

    /** Ham kimlikle okuma (adı bilinmeyen sinyaller için). */
    fun readRaw(featureId: Int): Int? {
        val target = manager ?: return null
        return runCatching { getInt?.invoke(target, DEVICE_ANY, featureId) as? Int }.getOrNull()
    }

    /**
     * Birden çok sinyali tek komutla yazar (BYD'nin kendi cihaz sınıfları da böyle gönderir).
     * Dönüş: platformun sonuç kodu (0 = kabul); yönetici ya da kimlik yoksa null.
     */
    fun write(deviceType: Int, group: String, fields: List<String>, values: IntArray): Int? {
        val target = manager ?: return null
        val method = setIntArray ?: return null
        val idArray = IntArray(fields.size) { featureId(group, fields[it]) ?: return null }
        return runCatching { method.invoke(target, deviceType, idArray, values) as? Int }
            .onFailure { Log.w(TAG, "yazma $fields: ${it.cause ?: it}") }
            .getOrNull()
            .also { Log.i(TAG, "yazma $fields=${values.toList()} → $it") }
    }

    /** Cihaz sınıflarında tanımlı sabit (örn. vites kodları). */
    fun deviceConstant(deviceClass: String, field: String): Int? = runCatching {
        Class.forName("android.hardware.bydauto.$deviceClass").getField(field).getInt(null)
    }.getOrNull()

    /** Tam sayı sinyal; BYD'nin "yok" işaretleri null'a çevrilir. */
    fun readInt(group: String, field: String): Int? {
        val target = manager ?: return null
        val id = featureId(group, field) ?: return null
        return runCatching { getInt?.invoke(target, DEVICE_ANY, id) as? Int }
            .onFailure { Log.w(TAG, "okuma $group.$field: ${it.cause ?: it}") }
            .getOrNull()
            ?.takeIf { it != UNAVAILABLE && it != NOT_FITTED }
    }

    /** Ondalık sinyal (hız böyle taşınıyor; int olarak okununca "takılı değil" döner). */
    fun readDouble(group: String, field: String): Double? {
        val target = manager ?: return null
        val id = featureId(group, field) ?: return null
        return runCatching { getDouble?.invoke(target, DEVICE_ANY, id) as? Double }
            .onFailure { Log.w(TAG, "okuma $group.$field: ${it.cause ?: it}") }
            .getOrNull()
    }

    /**
     * Gizli API kısıtını bu süreç için kaldırmaya çalışır (DiLink 3.0 = Android 10'da çalışır).
     * Olmazsa BYD sınıflarına yine de doğrudan erişilmeye çalışılır.
     */
    private fun exemptHiddenApi() {
        runCatching {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java
                .getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
            val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntime, "getRuntime", null) as Method
            val setExemptions = getDeclaredMethod
                .invoke(vmRuntime, "setHiddenApiExemptions", arrayOf(Array<String>::class.java)) as Method
            setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
        }.onFailure { Log.i(TAG, "gizli API muafiyeti alınamadı: ${it.message}") }
    }
}

/** Pano'nun gösterdiği araç sinyalleri; ESMER-UI `CarBus`'tan uyarlandı. */
internal object BydSignals {
    private const val GEARBOX_DEVICE = "gearbox.BYDAutoGearboxDevice"
    private const val AC_DEVICE = "ac.BYDAutoAcDevice"

    /** Platformun kendi üst sınırı; ötesi okuma değil işarettir. */
    private val SPEED_RANGE = 0.0..282.0
    private val SPEED_FIELDS = listOf("SPEED_AUTO_SPEED", "SPEED_FROM_GATEWAY", "SPEED_AUTO_SPEED_121")

    /** Yol hızı (km/s); üç sinyalden ilk makul olan. */
    fun speedKmh(): Int? {
        for (field in SPEED_FIELDS) {
            val v = BydBus.readDouble("Speed", field)
            if (v != null && v in SPEED_RANGE) return Math.round(v).toInt()
        }
        return null
    }

    /**
     * Vites. ESMER-UI'nin araçta doğruladığına göre dolu olan sinyal `GEARBOX_AUTO_MODE_TYPE`
     * (P=1, R=2, N=3, D=4, M=5, S=6); `GEARBOX_ACTUAL_GERA_STATE` başka ölçekte yedek.
     */
    private val autoModeGears by lazy {
        listOf("P", "R", "N", "D", "M", "S").withIndex().associate { (i, letter) ->
            (BydBus.deviceConstant(GEARBOX_DEVICE, "GEARBOX_AUTO_MODE_$letter") ?: (i + 1)) to letter
        }
    }
    private val actualGears by lazy {
        mapOf("N" to 0, "R" to 1, "D" to 2, "P" to 3).entries.associate { (letter, fallback) ->
            (BydBus.deviceConstant(GEARBOX_DEVICE, "GEAR_$letter") ?: fallback) to letter
        }
    }

    fun gear(): Gear? {
        val letter = BydBus.readInt("Gearbox", "GEARBOX_AUTO_MODE_TYPE")?.let { autoModeGears[it] }
            ?: BydBus.readInt("Gearbox", "GEARBOX_ACTUAL_GERA_STATE")?.let { actualGears[it] }
        return when (letter) {
            "P" -> Gear.P
            "R" -> Gear.R
            "N" -> Gear.N
            // M ve S ileri vitestir; arayüz için D sayılır.
            "D", "M", "S" -> Gear.D
            else -> null
        }
    }

    fun batteryPercent(): Int? =
        BydBus.readInt("Statistic", "STATISTIC_SOC_BATTERY_PERCENTAGE")?.takeIf { it in 0..100 }

    /** Elektrikli menzil; yoksa (yalnız yakıtlı/DM-i) yakıt menzili. */
    fun rangeKm(): Int? =
        (BydBus.readInt("Statistic", "STATISTIC_ELEC_DRIVING_RANGE")?.takeIf { it in 0..2000 })
            ?: BydBus.readInt("Statistic", "STATISTIC_FUEL_DRIVING_RANGE")?.takeIf { it in 0..3000 }

    /** Araç ekranının birimi Fahrenheit ise °C'ye çevirir. */
    private fun toCelsius(value: Int): Int {
        val unit = BydBus.readInt("Ac", "AC_TEMPERATURE_UNIT") ?: return value
        val celsius = BydBus.deviceConstant(AC_DEVICE, "AC_TEMPERATURE_UNIT_OC") ?: 1
        return if (unit == celsius) value else Math.round((value - 32) * 5f / 9f)
    }

    fun outsideTempC(): Int? =
        (BydBus.readInt("Ac", "AC_TEMP_OUT") ?: BydBus.readInt("Instrument", "INSTRUMENT_DD_OUT_TEMP"))
            ?.let(::toCelsius)?.takeIf { it in -50..70 }

    /** Kabin sıcaklığı veri yolunda yok; ESMER-UI'ye göre en yakını ünitenin kendi sensörü. */
    fun insideTempC(): Int? =
        BydBus.readInt("Sensor", "SENSOR_TEMPERATURE")?.takeIf { it in -40..90 }

    /** Onda bir psi → kPa. Veri yolu 384 = 38,4 psi = 265 kPa taşıyor. */
    private const val PSI_TENTHS_TO_KPA = 0.6894757f

    /**
     * Ortalama elektrik tüketimi (kWh/100 km) ve hangi pencereye ait olduğu.
     * BYD'nin `BYDAutoStatisticDevice` sınıfı bunları `getDouble` ile okuyor:
     * `getLastElecConPHMValue` (son 50 km) → `getTotalElecConPHMValue` (toplam ortalama).
     * Makul aralık dışı değerler (0 = henüz hesaplanmadı) bilinmiyor sayılır.
     */
    fun consumption(): Consumption? {
        val recent = BydBus.readDouble("Statistic", "STATISTIC_LAST_ELEC_CON_PHM")
        val total = BydBus.readDouble("Statistic", "STATISTIC_TOTAL_ELEC_CON_PHM")
        if (!loggedConsumption) {
            // Birim ve ölçek araçta henüz doğrulanmadı; ilk okumayı kayda yaz.
            loggedConsumption = true
            val unit = BydBus.readInt("Statistic", "STATISTIC_TOTAL_ELEC_CON_PHM_UNIT")
            val instant = BydBus.readDouble("Statistic", "STATISTIC_INSTANT_EV_CONSUME")
            Log.i("BydBus", "tüketim ham: son50=$recent toplam=$total anlık=$instant birim=$unit")
        }
        recent?.takeIf { it in CONSUMPTION_RANGE }?.let { return Consumption(it.toFloat(), recentWindow = true) }
        total?.takeIf { it in CONSUMPTION_RANGE }?.let { return Consumption(it.toFloat(), recentWindow = false) }
        return null
    }

    private val CONSUMPTION_RANGE = 1.0..60.0
    @Volatile private var loggedConsumption = false

    /**
     * Kapı, bagaj, kaput, cam ve sunroof durumu. `BYDAutoBodyworkDevice.getDoorState` /
     * `getWindowOpenPercent` aynı kimlikleri okuyor: kapılarda `BODYWORK_STATE_OPEN` = 1,
     * `BODYWORK_STATE_CLOSED` = 0 (255 = tanımsız); camlarda 0–100 açıklık yüzdesi.
     * Hiçbiri okunamazsa null.
     */
    fun openings(): Openings? {
        fun door(field: String) = when (BydBus.readInt("Bodywork", field)) {
            1 -> true
            0 -> false
            else -> null
        }
        fun percent(field: String) = BydBus.readInt("Bodywork", field)?.takeIf { it in 0..100 }
        val o = Openings(
            doorFrontLeft = door("BODYWORK_LEFT_HAND_FRONT_DOOR"),
            doorFrontRight = door("BODYWORK_RIGHT_HAND_FRONT_DOOR"),
            doorRearLeft = door("BODYWORK_LEFT_HAND_REAR_DOOR"),
            doorRearRight = door("BODYWORK_RIGHT_HAND_REAR_DOOR"),
            trunk = door("BODYWORK_LUGGAGE_DOOR"),
            hood = door("BODYWORK_HOOD"),
            windowFrontLeft = percent("BODYWORK_WINDOW_LEFT_FRONT_PERCENT"),
            windowFrontRight = percent("BODYWORK_WINDOW_RIGHT_FRONT_PERCENT"),
            windowRearLeft = percent("BODYWORK_WINDOW_LEFT_REAR_PERCENT"),
            windowRearRight = percent("BODYWORK_WINDOW_RIGHT_REAR_PERCENT"),
            sunroof = percent("BODYWORK_MOON_ROOF_OPEN_PERCENT"),
        )
        if (!loggedOpenings) {
            // Ölçek araçta henüz doğrulanmadı; ilk okumayı ve cam durum sinyallerini kayda yaz.
            loggedOpenings = true
            val states = listOf("LEFT_FRONT", "RIGHT_FRONT", "LEFT_REAR", "RIGHT_REAR")
                .map { BydBus.readInt("Bodywork", "BODYWORK_${it}_WINDOW") }
            Log.i("BydBus", "gövde ham: $o cam durumları=$states")
        }
        return o.takeUnless { it.isEmpty }
    }

    @Volatile private var loggedOpenings = false

    /**
     * Aracın kendi TPMS uyarıları (`BYDAutoTyreDevice` / `BYDAutoInstrumentDevice` sabitleri):
     *  - `TYRE_AIR_LEAK_STATE_*`: 0 normal, 1 hızlı kaçak, 2 yavaş kaçak
     *  - `TYRE_PRESSURE_STATE_*`: 0 normal, 1 yüksek basınç, 2 düşük basınç
     *  - `INSTRUMENT_2IN1_*_TYRE_COLOR`: göstergedeki renk, 1 beyaz, 2 sarı, 3 kırmızı
     *  - `TYRE_SYSTEM_STATE`: 3 = sistem arızası
     * Hiçbir sinyal okunamazsa null (arayüz uyarı göstermez).
     */
    fun tyreAlerts(): TyreAlerts? {
        var anyRead = false
        fun read(group: String, field: String) = BydBus.readInt(group, field)?.also { anyRead = true }
        fun corner(area: String, short: String): TyreAlert? {
            val leak = read("Tyre", "TYRE_AIR_LEAK_STATE_$area")
            val state = read("Tyre", "TYRE_PRESSURE_STATE_$area")
            val color = read("Instrument", "INSTRUMENT_2IN1_${short}_TYRE_COLOR")
            val red = color == 3
            return when {
                leak == 1 -> TyreAlert("Hızlı hava kaçağı", severe = true)
                leak == 2 -> TyreAlert("Yavaş hava kaçağı", severe = red)
                state == 2 -> TyreAlert("Düşük basınç", severe = red)
                state == 1 -> TyreAlert("Yüksek basınç", severe = red)
                red -> TyreAlert("Lastik uyarısı", severe = true)
                color == 2 -> TyreAlert("Lastik uyarısı", severe = false)
                else -> null
            }
        }
        val alerts = TyreAlerts(
            frontLeft = corner("LEFT_FRONT", "LF"),
            frontRight = corner("RIGHT_FRONT", "RF"),
            rearLeft = corner("LEFT_REAR", "LB"),
            rearRight = corner("RIGHT_REAR", "RB"),
            systemFault = read("Tyre", "TYRE_SYSTEM_STATE") == 3,
        )
        if (!loggedTyres) {
            loggedTyres = true
            Log.i("BydBus", "TPMS ilk okuma: $alerts (okunan sinyal var: $anyRead)")
        }
        return alerts.takeIf { anyRead }
    }

    @Volatile private var loggedTyres = false

    fun tyresKpa(): TyrePressures? {
        fun corner(c: String) = BydBus.readInt("Instrument", "INSTRUMENT_2IN1_${c}_TYRE_PRESSURE")
            ?.takeIf { it > 0 }?.let { Math.round(it * PSI_TENTHS_TO_KPA) }
        val t = TyrePressures(corner("LF"), corner("RF"), corner("LB"), corner("RB"))
        return if (t.frontLeft == null && t.frontRight == null && t.rearLeft == null && t.rearRight == null) null else t
    }
}
