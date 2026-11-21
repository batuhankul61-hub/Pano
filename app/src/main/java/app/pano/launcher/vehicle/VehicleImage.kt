package app.pano.launcher.vehicle

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Aracın kendi model görseli. BYD, her modelin görselini araç ayarları eklentisinde
 * ("com.byd.carsettings.plugins") araç tipi koduyla adlandırarak tutuyor; ör. Atto 2 (tip 81 = 0x51)
 * için `common_default_6125f_ivi_0x51`. Görsel Pano'ya kopyalanmaz, her açılışta araçtan okunur;
 * böylece hangi BYD'ye kurulursa onun görseli gelir.
 */
object VehicleImage {
    private const val TAG = "VehicleImage"
    private const val PLUGINS = "com.byd.carsettings.plugins"

    /** Bilinen adlandırma kalıpları; `%x` araç tipinin onaltılık kodudur. */
    private val PATTERNS = listOf("common_default_6125f_ivi_0x%x", "common_default_0x%x")

    @Volatile var cached: ImageBitmap? = null
        private set
    @Volatile private var tried = false

    /** Görseli yükler (G/Ç iş parçacığında çağrılmalı). Yoksa null; bir kez denenir. */
    fun load(context: Context): ImageBitmap? {
        cached?.let { return it }
        if (tried) return null
        tried = true
        val type = carType() ?: return null
        return runCatching {
            val res = context.packageManager.getResourcesForApplication(PLUGINS)
            PATTERNS.asSequence()
                .map { String.format(it, type) }
                .map { res.getIdentifier(it, "drawable", PLUGINS) }
                .firstOrNull { it != 0 }
                ?.let { id -> (res.getDrawable(id, null) as? BitmapDrawable)?.bitmap?.asImageBitmap() }
        }.onFailure { Log.i(TAG, "araç görseli yok: ${it.message}") }
            .getOrNull()
            ?.also {
                cached = it
                Log.i(TAG, "araç görseli yüklendi (tip 0x%x)".format(type))
            }
    }

    /** BYD'nin araç tipi kodu (sistem özelliği); BYD değilse null. */
    @SuppressLint("PrivateApi")
    private fun carType(): Int? = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        val value = sp.getMethod("get", String::class.java).invoke(null, "persist.sys.car.type") as String
        value.trim().toIntOrNull()
    }.getOrNull()?.takeIf { it > 0 }
}
