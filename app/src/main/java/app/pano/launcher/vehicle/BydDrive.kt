package app.pano.launcher.vehicle

import android.util.Log
import app.pano.launcher.settings.Settings

/**
 * Sürüş modu. [bus] veri yolundaki değerdir (`BYDAutoEnergyDevice.ENERGY_OPERATION_MODE_*`):
 * Normal = 1, Eco = 2, Sport = 3. Cihaz sınıfının `setOperationMode` API'si başka bir
 * numaralandırma alıp (Eco 1, Sport 2, Normal 3) bu değere çeviriyor; biz doğrudan veri yolu
 * değerini kullanıyoruz.
 */
enum class DriveMode(val label: String, val bus: Int) { Eco("Eco", 2), Normal("Normal", 1), Sport("Sport", 3) }

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

    fun mode(): DriveMode? {
        val v = BydBus.readRaw(OPERATION_MODE_RAW)?.takeIf { it in 1..3 }
            ?: BydBus.readInt("Energy", "ENERGY_OPERATION_MODE")
        return DriveMode.entries.firstOrNull { it.bus == v }
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
        val id = BydBus.idOf("Energy", "ENERGY_OPERATION_MODE_SET") ?: return null
        return BydBus.writeOne(ENERGY_DEVICE, id, mode.bus)
    }

    fun setRegen(level: RegenLevel): Int? {
        if (!canWrite()) return null.also { Log.i(TAG, "rejen gönderilmedi (ayar kapalı / sürüşte / BYD yok)") }
        val id = BydBus.idOf("Setting", "SET_DR_ENERGY_FB_SET") ?: return null
        return BydBus.writeOne(SETTING_DEVICE, id, level.code)
    }
}
