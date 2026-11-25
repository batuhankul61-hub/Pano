package app.pano.launcher.system

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Hızlı ayarlar panelinin Android tarafı: ekran parlaklığı, Bluetooth / Wi-Fi durumu ve Android'in
 * ayar ekranlarına geçiş. Araç sinyalleri `vehicle/BydComfort.kt`'de.
 *
 * Hiçbir çağrı istisna fırlatmaz; okunamayan değer null döner ve arayüz o öğeyi çizmez.
 */
object QuickControls {
    private const val TAG = "QuickControls"

    /** Android'in parlaklık ölçeği (Settings.System.SCREEN_BRIGHTNESS). */
    const val BRIGHTNESS_MAX = 255

    // ───────────── Parlaklık ─────────────

    /**
     * Parlaklık yazılabilir mi: WRITE_SECURE_SETTINGS (Pano'ya adb ile verilmiş) SettingsProvider'da
     * sistem ayarı yazmayı da kapsıyor; yoksa kullanıcının verdiği WRITE_SETTINGS.
     */
    fun canWriteBrightness(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED ||
            Settings.System.canWrite(context)

    /** 0..255; okunamazsa null. */
    fun brightness(context: Context): Int? = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull()?.coerceIn(0, BRIGHTNESS_MAX)

    /** Otomatik parlaklık açık mı; okunamazsa null. */
    fun autoBrightness(context: Context): Boolean? = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrNull()

    /**
     * Parlaklığı yazar. Otomatik parlaklık açıksa önce kapatılır (yoksa sistem değeri ezer).
     * Dönüş: yazıldıysa true.
     */
    fun setBrightness(context: Context, value: Int): Boolean = runCatching {
        val r = context.contentResolver
        if (autoBrightness(context) == true) {
            Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        }
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, BRIGHTNESS_MAX))
    }.onFailure { Log.w(TAG, "parlaklık yazılamadı: $it") }.getOrDefault(false)

    /**
     * Ayarın geçerli aralığı. BYD'nin kendi parlaklık sürgüsü (SystemUI `BydBrightnessView`)
     * `PowerManager.getMinimum/MaximumScreenBrightnessSetting` (gizli) arasında çalışıyor; okunamazsa 1..255.
     */
    fun minBrightness(context: Context): Int = powerInt(context, "getMinimumScreenBrightnessSetting") ?: 1

    fun maxBrightness(context: Context): Int = powerInt(context, "getMaximumScreenBrightnessSetting") ?: BRIGHTNESS_MAX

    private fun powerInt(context: Context, method: String): Int? = runCatching {
        HiddenApi.exempt("Landroid/os/PowerManager")
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        android.os.PowerManager::class.java.getMethod(method).invoke(pm) as Int
    }.getOrNull()?.takeIf { it in 0..BRIGHTNESS_MAX }

    /** Android değeri → yüzde (0..100), [min]..[max] aralığına göre (BYD'nin sürgüsüyle aynı hesap). */
    fun percentOf(value: Int, min: Int = 1, max: Int = BRIGHTNESS_MAX): Int {
        if (max <= min) return 100
        return Math.round((value.coerceIn(min, max) - min) * 100f / (max - min))
    }

    /** Yüzde → Android değeri. */
    fun valueOf(percent: Int, min: Int = 1, max: Int = BRIGHTNESS_MAX): Int {
        if (max <= min) return max
        return (min + Math.round(percent.coerceIn(0, 100) * (max - min) / 100f)).coerceIn(min, max)
    }

    // ───────────── Bluetooth ─────────────

    /** Bluetooth durumu; [connected] bağlı cihazın adı (telefon), bilinmiyorsa null. */
    data class Bluetooth(val on: Boolean, val connected: String?)

    /** Okunamazsa (adaptör yok / izin yok) null. */
    @SuppressLint("MissingPermission")
    fun bluetooth(context: Context): Bluetooth? = runCatching {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val on = adapter.isEnabled
        // Android 12+ ad okumak çalışma zamanı izni ister; araç Android 10, orada BLUETOOTH yeterli.
        val name = if (on && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            adapter.bondedDevices.orEmpty().firstOrNull { isConnected(it) }?.name
        } else null
        Bluetooth(on, name)
    }.onFailure { Log.w(TAG, "bluetooth okunamadı: $it") }.getOrNull()

    /** `BluetoothDevice.isConnected()` gizli; Android 10'da Pano'nun gizli API muafiyetiyle çalışır. */
    private fun isConnected(device: BluetoothDevice): Boolean = runCatching {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
    }.getOrDefault(false)

    // ───────────── Wi-Fi ─────────────

    /** Wi-Fi durumu; [network] bağlı ağın adı, bilinmiyorsa null. */
    data class Wifi(val on: Boolean, val connected: Boolean, val network: String?)

    fun wifi(context: Context): Wifi? = runCatching {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val on = wm.isWifiEnabled
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val connected = on && cm?.allNetworks.orEmpty().any {
            cm?.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        @Suppress("DEPRECATION")
        val ssid = if (connected) cleanSsid(wm.connectionInfo?.ssid) else null
        Wifi(on, connected, ssid)
    }.onFailure { Log.w(TAG, "wi-fi okunamadı: $it") }.getOrNull()

    /** Android SSID'yi tırnakla verir; konum izni yoksa "<unknown ssid>" döner. */
    fun cleanSsid(raw: String?): String? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        return s.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>" && it != "0x" }
    }

    // ───────────── Ayar ekranları ─────────────

    /** Android'in ilgili ayar ekranı bu cihazda var mı (BYD'de yoksa düğme gösterilmez). */
    fun hasSettings(context: Context, action: String): Boolean =
        Intent(action).resolveActivity(context.packageManager) != null

    fun openSettings(context: Context, action: String) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ayar ekranı yok: $action")
        }
    }
}
