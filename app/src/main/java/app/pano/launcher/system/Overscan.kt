package app.pano.launcher.system

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Ekranın alt kenarından Pano'nun alt şeridi kadar yeri uygulamalardan ayırır (Android 10 "overscan").
 * Böylece başka bir uygulama tam ekran açıkken şeridin üstünde biter; şerit hiçbir şeyi örtmez.
 *
 * Yalnızca Android 10'da var (11'de kaldırıldı) ve Pano'ya bilgisayardan verilen güvenli ayar izni
 * (WRITE_SECURE_SETTINGS) gerekir; `wm overscan` komutunun çağırdığı pencere yöneticisi yöntemi
 * (`IWindowManager.setOverscan`) doğrudan çağrılır (komutun kendisi uygulamalara kapalı). Ayar
 * kalıcıdır (yeniden başlatmada da kalır): Pano öne gelince, şerit gizlenince ve Pano çökerken
 * sıfırlanır. Elle geri almak için: `adb shell wm overscan reset`.
 */
object Overscan {
    private const val TAG = "Overscan"

    /** Şu an istenen alt boşluk (piksel); 0 = yok. Aynı değer tekrar yazılmaz. */
    @Volatile private var applied: Int? = null
    private var guardInstalled = false

    fun available(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT == 29 &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Alt kenardan [bottomPx] piksel ayırır; 0 sıfırlar (kısa bir sistem çağrısı, hemen uygulanır).
     * Dönen değer: şu an ayrılan alt boşluk (yapılamadıysa 0).
     */
    fun set(context: Context, bottomPx: Int): Int {
        if (!available(context)) return 0
        val want = bottomPx.coerceAtLeast(0)
        if (applied != want) {
            applied = want
            write(want)
        }
        return applied ?: 0
    }

    fun reset(context: Context) = set(context, 0)

    /**
     * Pano açılırken (önceki oturumdan kalmış olabilir) ve çökerken boşluğu sıfırlar; yoksa ekranın
     * altında boş bir şerit kalırdı.
     */
    fun installGuard(context: Context) {
        if (!available(context)) return
        if (!guardInstalled) {
            guardInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                // Süreç ölmeden, eşzamanlı.
                runCatching { write(0) }
                previous?.uncaughtException(t, e)
            }
        }
        applied = null
        reset(context)
    }

    @Synchronized
    private fun write(bottom: Int) {
        runCatching {
            exemptHiddenApi()
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
                ?: error("pencere yöneticisi yok")
            wm.javaClass.getMethod(
                "setOverscan",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).invoke(wm, 0, 0, 0, 0, bottom)
            Log.i(TAG, if (bottom == 0) "sıfırlandı" else "alt boşluk $bottom px")
        }.onFailure {
            applied = null
            Log.w(TAG, "yazılamadı: ${(it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it}")
        }
    }

    private var exempted = false

    /**
     * Android 10 gizli sistem yöntemlerini uygulamalara kapatır; Pano yalnızca kendi süreci için ve
     * yalnızca pencere yöneticisi sınıfları için bu kısıtı kaldırır.
     */
    private fun exemptHiddenApi() {
        if (exempted) return
        runCatching {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getMethod = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
            val vm = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getMethod.invoke(vm, "getRuntime", null) as java.lang.reflect.Method
            val setExemptions = getMethod.invoke(vm, "setHiddenApiExemptions", arrayOf(arrayOf<String>()::class.java)) as java.lang.reflect.Method
            setExemptions.invoke(getRuntime.invoke(null), arrayOf("Landroid/view/IWindowManager", "Landroid/view/WindowManagerGlobal"))
            exempted = true
        }.onFailure { Log.w(TAG, "gizli API izni alınamadı: $it") }
    }
}
