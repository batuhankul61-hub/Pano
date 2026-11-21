package app.pano.launcher

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * Uygulamaları launcher'dan çıkmadan, ana panodaki bir panelin (harita kartı) tam üstünde açar.
 *
 * Android normal uygulamaların başka uygulamaları kendi içine gömmesine izin vermiyor; bunun
 * yerine uygulama "serbest pencere" (freeform) olarak, panelin ekrandaki sınırlarıyla açılır.
 * Böylece Pano'nun geri kalanı görünür ve kullanılabilir kalır. Serbest pencere desteği kapalıysa
 * uygulama her zamanki gibi tam ekran açılır.
 */
object PanelHost {
    private const val TAG = "PanelHost"
    private const val WINDOWING_MODE_FREEFORM = 5

    /** Panelin ekrandaki piksel sınırları; harita kartı yerleşince güncellenir. */
    @Volatile var bounds: Rect? = null

    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) == 1

    /** Uygulamayı panelde açar; açamazsa false döner (çağıran tam ekran açar). */
    fun open(context: Context, packageName: String, className: String): Boolean {
        val b = bounds ?: return false
        if (!isSupported(context)) return false
        val options = ActivityOptions.makeBasic().setLaunchBounds(Rect(b))
        // Pencere kipi API'si gizli; yoksa sınır vermek de çoğu cihazda serbest pencere açar.
        runCatching {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, WINDOWING_MODE_FREEFORM)
        }.onFailure { Log.i(TAG, "pencere kipi verilemedi: ${it.message}") }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(packageName, className)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent, options.toBundle())
            Log.i(TAG, "$packageName panelde açıldı: $b")
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Uygulama açılamadı", Toast.LENGTH_SHORT).show()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "panelde açılamadı: ${e.message}")
            false
        }
    }
}
