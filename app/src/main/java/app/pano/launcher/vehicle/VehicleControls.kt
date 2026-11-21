package app.pano.launcher.vehicle

import android.util.Log

/**
 * Araç gövdesi komutları (kilit, şarj kapağı). Biçimler DiLink 3.0'daki `BYDAutoSettingDevice`'ten:
 * cihaz türü 1023; kilit `setDoorLock` (kimlik 0x7de3f, değer 0/1), şarj kapağı `setChargingPort`
 * (`SET_DR_CHARGER_PORT_SET`, değer 0/1/2). Değerlerin anlamı araçta doğrulanıyor.
 */
object VehicleControls {
    private const val TAG = "VehicleControls"
    private const val SETTING_DEVICE = 1023
    private const val DOOR_LOCK_ID = 0x7de3f

    /** Kilit komutu; 0 ya da 1. Sonuç kodu (0 = kabul) ya da null. */
    fun doorLock(value: Int): Int? {
        Log.i(TAG, "kilit önce: ${BydBus.readRaw(DOOR_LOCK_ID)}")
        return BydBus.writeOne(SETTING_DEVICE, DOOR_LOCK_ID, value)
    }

    /** Şarj kapağı komutu; 0, 1 ya da 2. */
    fun chargePort(value: Int): Int? {
        val id = BydBus.idOf("Setting", "SET_DR_CHARGER_PORT_SET") ?: return null
        Log.i(TAG, "şarj kapağı önce: ${BydBus.readInt("Setting", "SET_DR_CHARGER_PORT")}")
        return BydBus.writeOne(SETTING_DEVICE, id, value)
    }
}
