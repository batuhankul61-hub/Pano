package app.pano.launcher.system

import android.util.Log

/**
 * Android 10 gizli sistem yöntemlerini uygulamalara kapatır. Pano yalnızca kendi süreci için ve
 * yalnızca gereken sınıflar için bu kısıtı kaldırır (overscan, BYD'nin genel sessiz durumu).
 * `setHiddenApiExemptions` listeyi her çağrıda baştan yazdığı için izinler burada birikir.
 */
object HiddenApi {
    private const val TAG = "HiddenApi"
    private val prefixes = linkedSetOf<String>()

    @Synchronized
    fun exempt(vararg classPrefixes: String) {
        if (prefixes.containsAll(classPrefixes.toList())) return
        prefixes.addAll(classPrefixes)
        runCatching {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getMethod = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
            val vm = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getMethod.invoke(vm, "getRuntime", null) as java.lang.reflect.Method
            val setExemptions = getMethod.invoke(vm, "setHiddenApiExemptions", arrayOf(arrayOf<String>()::class.java)) as java.lang.reflect.Method
            setExemptions.invoke(getRuntime.invoke(null), prefixes.toTypedArray())
        }.onFailure { Log.w(TAG, "gizli API izni alınamadı: $it") }
    }
}
