package app.pano.launcher.ui

import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import app.pano.launcher.LaunchAnchor
import kotlinx.coroutines.launch

/**
 * Dokunulan öğenin ekrandaki yerini tutar; uygulama oradan büyüyerek açılır.
 * Sınırlar `boundsInWindow` ile alınır: DesignCanvas ölçeği ve basınca küçülme zaten işlenmiş,
 * gerçek piksel değerleridir.
 */
class LaunchSource internal constructor(private val view: android.view.View) {
    internal var bounds: Rect? = null
    fun anchor(): LaunchAnchor? = bounds?.let { LaunchAnchor(view.rootView, Rect(it)) }
}

@Composable
fun rememberLaunchSource(): LaunchSource {
    val view = LocalView.current
    return remember(view) { LaunchSource(view) }
}

/** Öğenin pencere içindeki sınırlarını [source]'a yazar. Kendi çizimini değiştirmez. */
fun Modifier.launchSource(source: LaunchSource): Modifier = this.onGloballyPositioned { coords ->
    val r = coords.boundsInWindow()
    source.bounds = Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
}

/** Launcher'a her dönüşte artar; içerik buna bakıp kısa bir giriş animasyonu oynatır. */
object ReturnMotion {
    internal val tick = mutableIntStateOf(0)
    fun onReturn() { tick.intValue++ }
}

/**
 * Başka bir uygulamadan dönünce içerik çok hafif bir belirmeyle gelir: belirgin yakınlaşma ya da
 * yaylanma yok, 180 ms'de biter. Duvar kağıdı / zemin sabit kalsın diye yalnızca içeriğe uygulanır.
 */
fun Modifier.returnZoom(): Modifier = composed {
    val tick = ReturnMotion.tick.intValue
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        scale.snapTo(0.985f)
        alpha.snapTo(0.7f)
        launch { alpha.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
        scale.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
    }
    this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        this.alpha = alpha.value
    }
}
