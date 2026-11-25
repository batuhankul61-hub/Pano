package app.pano.launcher.vehicle

import android.util.Log
import app.pano.launcher.settings.Settings

/**
 * Sürüş modu: araçtaki dört seçenek (Eco, Normal, Sport, Kar / "Snowfield").
 *
 * İlk üçü sürüş modudur. [bus] yazarken kullanılan değer (`BYDAutoEnergyDevice.ENERGY_OPERATION_MODE_*`:
 * Normal = 1, Eco = 2, Sport = 3; yazma araçta denenmedi). Okuma bu numaralandırmaya uymuyor,
 * bkz. [BydDrive.mode]. Kar ise ayrı bir sinyal: yol yüzeyi modu
 * (`ENERGY_ROAD_SURFACE_MODE`: Normal yol 1, Kar 2, Çamur 3, Kum 4; firmware 13.1.33). Kar seçiliyken
 * sürüş modu değeri ne olursa olsun Kar gösterilir. [bus] Kar için yol yüzeyi değeridir.
 */
enum class DriveMode(val label: String, val bus: Int) { Eco("Eco", 2), Normal("Normal", 1), Sport("Sport", 3), Snow("Kar", 2) }

/**
 * Rejeneratif fren seviyesi (`BYDAutoSettingDevice.SET_DR_ENERGY_FB_*`):
 * Düşük 1, Standart 2, Yüksek 3, Süper 4 (DM araçlarda Standart 5, Yüksek 6).
 */
enum class RegenLevel(val label: String, val code: Int) {
    Low("Düşük", 1), Standard("Standart", 2), High("Yüksek", 3), Super("Süper", 4);

    companion object {
        fun of(code: Int?): RegenLevel? = when (code) {
            1 -> Low
            2, 5 -> Standard
            3, 6 -> High
            4 -> Super
            else -> null
        }
    }
}

/**
 * Sürüş modu ve rejen okuma (her zaman) ve yazma (yalnızca "Araca komut gönder" ayarı açıkken
 * ve park halindeyken). Yazma kodu araçta HENÜZ DENENMEDİ.
 */
object BydDrive {
    private const val TAG = "BydDrive"
    private const val ENERGY_DEVICE = 1006
    private const val SETTING_DEVICE = 1023

    /** `BYDAutoEnergyDevice.getOperationMode` bu ham kimliği okuyor (adlı alan yedek). */
    private const val OPERATION_MODE_RAW = 0x2120000e

    private const val ROAD_COMMON = 1
    private const val ROAD_SNOW = 2

    /** Son okunan ham değerler; değişince loglanır (araçta eşlemeyi doğrulamak için). */
    private var lastRaw: String? = null

    /**
     * Araçta doğrulandı (BYD Atto 2, 25.11.2026, dört mod iki tur): `ENERGY_OPERATION_MODE` Sport'ta 2,
     * Eco'da da Normal'de de 1 veriyor; Eco'yu göstergedeki ECO ışığı ayırıyor
     * (`INSTRUMENT_2IN1_FAULT_ECO_INDICATOR`, en düşük iki bit: 1 = yanık, 2 = sönük; üst bitler başka
     * bilgi taşıyor). Kar yol yüzeyi sinyalinden (2). 3 araçta görülmedi; diğer numaralandırmadaki
     * gibi Normal sayılır.
     */
    fun mode(): DriveMode? {
        val raw = BydBus.readRaw(OPERATION_MODE_RAW)?.takeIf { it in 1..3 }
            ?: BydBus.readInt("Energy", "ENERGY_OPERATION_MODE")
        val road = BydBus.readInt("Energy", "ENERGY_ROAD_SURFACE_MODE")
        val ecoLamp = BydBus.readInt("Instrument", "INSTRUMENT_2IN1_FAULT_ECO_INDICATOR")?.and(0x3)
        val line = "sürüş modu ham=$raw yol=$road eko=$ecoLamp"
        if (line != lastRaw) { lastRaw = line; Log.i(TAG, line) }
        if (road == ROAD_SNOW) return DriveMode.Snow
        return when (raw) {
            2 -> DriveMode.Sport
            1 -> if (ecoLamp == 1) DriveMode.Eco else DriveMode.Normal
            3 -> DriveMode.Normal
            else -> null
        }
    }

    fun regen(): RegenLevel? = RegenLevel.of(
        BydBus.readInt("Setting", "SET_DR_ENERGY_FB")
            // Yedek: gösterge sinyali yalnızca standart (1) / yüksek (2) ayırıyor.
            ?: BydBus.readInt("Instrument", "INSTRUMENT_DD_ENERGY_INTENSITY_FEEDBACK")?.let { if (it == 1) 2 else if (it == 2) 3 else null },
    )

    /** Komut gönderilebilir mi: ayar açık, BYD veri yolu var ve araç park halinde. */
    fun canWrite(): Boolean =
        Settings.state.value.vehicleCommands && BydBus.isAvailable && !VehicleHub.isDriving.value

    /** Dönüş: platform sonuç kodu (0 = kabul) ya da gönderilmediyse null. */
    fun setMode(mode: DriveMode): Int? {
        if (!canWrite()) return null.also { Log.i(TAG, "sürüş modu gönderilmedi (ayar kapalı / sürüşte / BYD yok)") }
        val road = BydBus.idOf("Energy", "ENERGY_ROAD_SURFACE_MODE_SET")
        if (mode == DriveMode.Snow) return road?.let { BydBus.writeOne(ENERGY_DEVICE, it, ROAD_SNOW) }
        // Kar'dan çıkarken yol yüzeyi normale döner, sonra sürüş modu yazılır.
        if (road != null && BydBus.readInt("Energy", "ENERGY_ROAD_SURFACE_MODE") == ROAD_SNOW) BydBus.writeOne(ENERGY_DEVICE, road, ROAD_COMMON)
        val id = BydBus.idOf("Energy", "ENERGY_OPERATION_MODE_SET") ?: return null
        return BydBus.writeOne(ENERGY_DEVICE, id, mode.bus)
    }

    fun setRegen(level: RegenLevel): Int? {
        if (!canWrite()) return null.also { Log.i(TAG, "rejen gönderilmedi (ayar kapalı / sürüşte / BYD yok)") }
        val id = BydBus.idOf("Setting", "SET_DR_ENERGY_FB_SET") ?: return null
        return BydBus.writeOne(SETTING_DEVICE, id, level.code)
    }
}
