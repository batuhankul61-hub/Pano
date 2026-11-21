package app.pano.launcher.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pano.launcher.ui.theme.LocalPano
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** iOS / CarPlay ikonlarındaki köşe oranı: kenarın ~%23'ü. */
private const val CORNER_RATIO = 0.23f
private const val ICON_PX = 192

/** Uygulama ikonlarının kare kırpılmış bitmap'leri. Her yeniden çizimde PackageManager'a gidilmez. */
object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(160)

    fun peek(packageName: String, className: String): ImageBitmap? = cache.get(key(packageName, className))

    /** Diskten okur; ana iş parçacığında çağırma. Uygulama kaldırılmışsa null döner. */
    fun load(context: Context, packageName: String, className: String): ImageBitmap? {
        val key = key(packageName, className)
        cache.get(key)?.let { return it }
        val drawable = try {
            context.packageManager.getActivityIcon(ComponentName(packageName, className))
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val bitmap = renderSquircle(drawable, ICON_PX).asImageBitmap()
        cache.put(key, bitmap)
        return bitmap
    }

    private fun key(packageName: String, className: String) = "$packageName/$className"

    /**
     * Sistemin dairesel maskesi yerine ikonu kendimiz çizeriz: adaptive ikonun arka ve ön katmanı
     * kare tuvale serilir, sonra yumuşak köşeli kareyle (kenar yumuşatmalı) kırpılır.
     */
    private fun renderSquircle(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (drawable is AdaptiveIconDrawable) {
            // Katmanlar 108dp; görünen alan ortadaki 72dp. Bu yüzden her kenardan %25 taşırırız.
            val inset = size / 4
            for (layer in listOf(drawable.background, drawable.foreground)) {
                layer ?: continue
                layer.setBounds(-inset, -inset, size + inset, size + inset)
                layer.draw(canvas)
            }
        } else {
            // Eski tip ikon: açık bir zeminin ortasına, kenarlardan pay bırakarak.
            canvas.drawColor(android.graphics.Color.WHITE)
            val pad = (size * 0.16f).toInt()
            drawable.setBounds(pad, pad, size - pad, size - pad)
            drawable.draw(canvas)
        }
        // Kırpma: kare görüntü, kenarı yumuşatılmış yuvarlatılmış kare olarak yeni bitmap'e boyanır.
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = size * CORNER_RATIO
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
        bitmap.recycle()
        return out
    }
}

/**
 * Uygulamalar ekranında ve favori şeridinde kullanılan yumuşak kare ikon.
 * [favorite] doluysa sağ üstte küçük bir yıldız işareti gösterilir.
 */
@Composable
fun AppIcon(packageName: String, className: String, size: Dp, modifier: Modifier = Modifier, favorite: Boolean = false) {
    val context = LocalContext.current
    val dark = LocalPano.current.isDark
    val icon by produceState(AppIconCache.peek(packageName, className), packageName, className) {
        if (value == null) value = withContext(Dispatchers.IO) { AppIconCache.load(context, packageName, className) }
    }
    val shape = RoundedCornerShape(percent = (CORNER_RATIO * 100).toInt())
    // İnce kenar: beyaz zeminli ikonlar gündüz temasında arka plana karışmasın.
    val edge = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)

    Box(modifier.size(size)) {
        val bmp = icon
        if (bmp != null) {
            Image(bmp, contentDescription = null, modifier = Modifier.size(size).border(1.dp, edge, shape))
        } else {
            Box(Modifier.size(size).background(edge, shape))
        }
        if (favorite) {
            val badge = size * 0.3f
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = badge * 0.3f, y = -badge * 0.3f).size(badge)
                    .background(if (dark) Color(0xFF2C2C2E) else Color.White, CircleShape)
                    .border(1.dp, edge, CircleShape)
                    .padding(badge * 0.18f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PanoIcons.Star, contentDescription = "Favori", tint = Color(0xFFFFB800), modifier = Modifier.fillMaxSize())
            }
        }
    }
}
