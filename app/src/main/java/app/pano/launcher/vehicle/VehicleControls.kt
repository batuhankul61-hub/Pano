package app.pano.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import app.pano.launcher.Launch

/**
 * Araç gövdesi komutları (kilit, şarj kapağı). Biçimler DiLink 3.0 framework'ünden (araçtan çekilen
 * `framework.jar` incelendi):
 *  - Kilit komutu: `BYDAutoSettingDevice.setDoorLock(v)` → cihaz türü 1023,
 *    `Setting.SET_CAR_DOOR_LOCK_SET` (0x7de3f); yalnızca 0 ya da 1 kabul ediliyor.
 *  - Kilit durumu: `BYDAutoDoorLockDevice.getDoorLockStatus(alan)` → `Door.DOOR_LOCK_COMMAND_AREA_*`;
 *    1 = açık, 2 = kilitli, 0 = geçersiz.
 *  - Şarj kapağı: `SET_DR_CHARGER_PORT_SET`, değer 0/1/2 (henüz arayüze bağlı değil).
 *
 * 0/1'den hangisinin "kilitle" olduğu framework'te yazmıyor. Bu yüzden komut her zaman aracın şu anki
 * durumunun tersini ister: yanlış değer zaten açık olanı "açmak" gibi etkisiz kalır. Sonuç kapı
 * durumundan doğrulanır ve doğru değer bir kez öğrenilip saklanır.
 */
object VehicleControls {
    private const val TAG = "VehicleControls"
    private const val SETTING_DEVICE = 1023
    private const val DOOR_LOCK_ID = 0x7de3f
    private const val PREFS = "pano_vehicle"
    private const val KEY_LOCK_VALUE = "lockValue"

    /** Durum değişimini bekleme süresi; kilit motoru ~1 sn sürüyor. */
    private const val CONFIRM_MS = 3_000L

    private const val STATE_UNLOCK = 1
    private const val STATE_LOCK = 2
    private val DOORS = listOf(
        "DOOR_LOCK_COMMAND_AREA_LEFT_FRONT", "DOOR_LOCK_COMMAND_AREA_RIGHT_FRONT",
        "DOOR_LOCK_COMMAND_AREA_LEFT_REAR", "DOOR_LOCK_COMMAND_AREA_RIGHT_REAR",
    )

    /** `BYDAutoDoorLockDevice.mDeviceType`: kilit durumu bu türle okunuyor. */
    private const val DOOR_LOCK_DEVICE = 1041

    @Volatile private var lastRaw: List<Int?>? = null
    @Volatile private var busy = false
    private val main = Handler(Looper.getMainLooper())

    /**
     * Araç kilitli mi: okunabilen kapılardan biri açıksa false, hepsi kilitliyse true, hiçbiri
     * okunamazsa null (uydurma "kilitli" gösterilmez).
     */
    fun lockedState(): Boolean? {
        val raw = DOORS.map { f -> BydBus.readFor(DOOR_LOCK_DEVICE, "Door", f) ?: BydBus.readInt("Door", f) }
        // Teşhis: bazı ROM'larda kapı sinyalleri boş; diğer adaylar da kayda yazılır.
        val extra = listOf(
            BydBus.readFor(SETTING_DEVICE, "Setting", "SET_CAR_DOOR_LOCK"),
            BydBus.readInt("Setting", "SET_CAR_DOOR_LOCK"),
            BydBus.readInt("Ota", "OTA_LF_DOOR_LOCK"),
            BydBus.readRaw(DOOR_LOCK_ID),
        )
        if (raw + extra != lastRaw) {
            lastRaw = raw + extra
            Log.i(TAG, "kilit ham kapılar=$raw ayar(1023)=${extra[0]} ayar(0)=${extra[1]} ota=${extra[2]} set-id=${extra[3]}")
        }
        val states = raw.mapNotNull { it?.takeIf { v -> v == STATE_UNLOCK || v == STATE_LOCK } }
        return when {
            states.isEmpty() -> null
            states.any { it == STATE_UNLOCK } -> false
            else -> true
        }
    }

    /**
     * Kilit düğmesi: aracı kilitliyse açar, açıksa kilitler. Yalnızca BYD veri yolu varken ve park
     * halindeyken çalışır; sonucu kısa bir bildirimle söyler. Arka planda çalışır.
     */
    fun toggleLock(context: Context) {
        val app = context.applicationContext
        if (!BydBus.isAvailable) {
            Launch.notConnectedYet(context)
            return
        }
        if (VehicleHub.isDriving.value) {
            toast(app, "Sürüş sırasında kilit kullanılamaz")
            return
        }
        if (busy) return
        busy = true
        Thread({
            try {
                val message = runToggle(app)
                toast(app, message)
            } catch (t: Throwable) {
                Log.w(TAG, "kilit: $t")
                toast(app, "Kilit komutu gönderilemedi")
            } finally {
                busy = false
            }
        }, "pano-kilit").start()
    }

    private fun runToggle(app: Context): String {
        val current = lockedState() ?: return "Kilit durumu okunamadı"
        val want = !current
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = prefs.getInt(KEY_LOCK_VALUE, -1).takeIf { it == 0 || it == 1 }
        val done = if (want) "Kilitlendi" else "Kilit açıldı"

        // Değer biliniyorsa doğrudan; değilse önce 1, olmazsa 0 denenir.
        val candidates = if (known != null) listOf(if (want) known else 1 - known) else listOf(1, 0)
        for (value in candidates) {
            val result = BydBus.writeOne(SETTING_DEVICE, DOOR_LOCK_ID, value)
            Log.i(TAG, "kilit ${if (want) "kilitle" else "aç"}: değer=$value sonuç=$result")
            if (result == null) return "Araç komutu almadı"
            if (waitFor(want)) {
                if (known == null) {
                    // Öğrenilen: "kilitle" için gönderilecek değer.
                    val lockValue = if (want) value else 1 - value
                    prefs.edit().putInt(KEY_LOCK_VALUE, lockValue).apply()
                    Log.i(TAG, "kilit değeri öğrenildi: kilitle=$lockValue")
                }
                return done
            }
        }
        return if (known != null) "Araç kilidi değiştirmedi" else "Araç kilit komutunu kabul etmedi"
    }

    /** Kilit durumu [locked] olana dek kısa aralıklarla okur. */
    private fun waitFor(locked: Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + CONFIRM_MS
        while (SystemClock.elapsedRealtime() < until) {
            Thread.sleep(250)
            if (lockedState() == locked) return true
        }
        return false
    }

    private fun toast(app: Context, text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }

    /** Şarj kapağı komutu; 0, 1 ya da 2. Henüz arayüze bağlı değil. */
    fun chargePort(value: Int): Int? {
        val id = BydBus.idOf("Setting", "SET_DR_CHARGER_PORT_SET") ?: return null
        Log.i(TAG, "şarj kapağı önce: ${BydBus.readInt("Setting", "SET_DR_CHARGER_PORT")}")
        return BydBus.writeOne(SETTING_DEVICE, id, value)
    }
}
