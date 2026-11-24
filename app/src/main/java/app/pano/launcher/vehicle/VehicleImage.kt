package app.pano.launcher.vehicle

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
                ?.let { id -> (res.getDrawable(id, null) as? BitmapDrawable)?.bitmap?.let(::trimTransparent)?.let(::featherEdges)?.asImageBitmap() }
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

    /**
     * Görselin dört kenarını yumuşakça saydamlaştırır. BYD görselinin açık renkli bir zemini var;
     * Pano'nun zemininde dikdörtgenin köşeleri keskin bir geçiş olarak görünüyordu. Kenardan
     * [FEATHER] oranında içeriye kadar saydamlık yumuşak bir eğriyle (smoothstep) artar.
     */
    private fun featherEdges(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val fx = FloatArray(w) { ramp(it, w) }
        val fy = FloatArray(h) { ramp(it, h) }
        val row = IntArray(w)
        for (y in 0 until h) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val k = fx[x] * fy[y]
                if (k >= 1f) continue
                val p = row[x]
                val a = ((p ushr 24) * k).toInt()
                row[x] = (a shl 24) or (p and 0x00FFFFFF)
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    /** Kenardan uzaklığa göre 0 (kenar) … 1 (iç) saydamlık çarpanı. */
    private fun ramp(i: Int, n: Int): Float {
        val edge = minOf(i, n - 1 - i).toFloat() / (n * FEATHER)
        if (edge >= 1f) return 1f
        return edge * edge * (3f - 2f * edge)
    }

    /** Kenarın ne kadarı yumuşatılır (her kenarda boyutun %12'si). */
    private const val FEATHER = 0.12f

    /**
     * Görselin saydam kenarlarını kırpar. BYD görselinde araç tuvalin ortasında değil (sağ altta
     * boşluk az, sol üstte çok); kırpılmazsa ortalanmış kutuda araç sağ alta kaymış görünür.
     * Yarı saydam yer gölgesi korunur.
     */
    private fun trimTransparent(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val w = src.width
        val h = src.height
        val row = IntArray(w)
        var top = h; var bottom = -1; var left = w; var right = -1
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((row[x] ushr 24) > 12) {
                    if (y < top) top = y
                    bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (bottom < 0) return src
        Log.i(TAG, "görsel ${w}x$h, dolu alan x=$left..$right y=$top..$bottom")
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return src
        return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
    }
}
