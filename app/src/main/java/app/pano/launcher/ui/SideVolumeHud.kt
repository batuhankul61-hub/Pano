package app.pano.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.system.VolumeHudOverlay
import app.pano.launcher.ui.theme.LocalPano
import kotlin.math.roundToInt

/**
 * Aracın soldaki ses barının yerine çıkan Pano ses barı ([VolumeHudOverlay]). BYD barı gibi iki
 * sürgü: Medya (aramada Arama) ve Navigasyon. Pano öndeyken sol sütunun zemininde, kenarsız; başka
 * bir uygulamanın üstünde Pano kartı olarak çizilir. Gece/gündüz temasını izler.
 *
 * Açılış anında ve opaktır (yoksa alttaki BYD barı bir an görünürdü); yalnızca kapanış solar.
 */
@Composable
fun SideVolumeHud() {
    val c = LocalPano.current
    val onPano = VolumeHudOverlay.onPano.value
    val alpha by animateFloatAsState(
        if (VolumeHudOverlay.visible.value) 1f else 0f,
        tween(if (VolumeHudOverlay.visible.value) 0 else VolumeHudOverlay.EXIT_MS.toInt()), label = "ses-barı",
    )
    // Pencere BYD barından yanlarda 20, üst/altta 6 px taşıyor; kart kenarları taşmada.
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }
            .then(
                if (onPano) Modifier.background(c.card)
                else Modifier.padding(4.dp).clip(shape).background(c.card).border(1.dp, c.hairline, shape),
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val naviActive = VolumeHudOverlay.naviActive.value
            VolumeSlider(
                VolumeHudOverlay.media,
                if (VolumeHudOverlay.media.title.value == "Arama") PanoIcons.Phone else PanoIcons.Speaker,
                active = !naviActive, modifier = Modifier.weight(1f),
            )
            VolumeSlider(VolumeHudOverlay.navi, PanoIcons.Navigation, active = naviActive, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun VolumeSlider(s: VolumeHudOverlay.Slider, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, modifier: Modifier) {
    val c = LocalPano.current
    val max = s.max.intValue.coerceAtLeast(1)
    val level = s.level.intValue
    val muted = s.muted.value
    val target = if (muted) 0f else (level / max.toFloat()).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(target, tween(140), label = "ses-dolgu")
    val percent = (target * 100).roundToInt()
    // Etkin sürgü tam renkli; diğeri geri planda.
    val fill = if (active) c.text else c.text.copy(alpha = 0.45f)
    val track = c.text.copy(alpha = if (c.isDark) 0.10f else 0.07f)

    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (muted) "Kapalı" else "%$percent",
            color = if (active) c.text else c.secondary,
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        )
        Box(
            Modifier.padding(top = 12.dp, bottom = 12.dp).weight(1f).width(56.dp)
                .clip(RoundedCornerShape(20.dp)).background(track)
                .semantics { contentDescription = "${s.title.value} %$percent" }
                .pointerInput(max) {
                    // Parmağın yüksekliği sesi belirler (aşağı sıfır, yukarı en yüksek).
                    fun valueAt(y: Float) = ((1f - (y / size.height).coerceIn(0f, 1f)) * max).roundToInt()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        VolumeHudOverlay.touching = true
                        VolumeHudOverlay.set(s, valueAt(down.position.y))
                        do {
                            val e = awaitPointerEvent()
                            e.changes.firstOrNull()?.let { ch ->
                                if (ch.pressed) { ch.consume(); VolumeHudOverlay.set(s, valueAt(ch.position.y)) }
                            }
                        } while (e.changes.any { it.pressed })
                        VolumeHudOverlay.touching = false
                        VolumeHudOverlay.scheduleHide()
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(fraction).clip(RoundedCornerShape(20.dp)).background(fill))
            // Simge dolgunun üstündeyse ters renkte okunur.
            val iconOnFill = fraction > 0.16f
            Icon(
                icon, null,
                tint = if (iconOnFill) c.card else c.secondary,
                modifier = Modifier.padding(bottom = 14.dp).size(24.dp),
            )
        }
        Text(
            s.title.value, color = if (active) c.text else c.secondary,
            fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        )
    }
}
