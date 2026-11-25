package app.pano.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.system.VolumeHudOverlay
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano

/**
 * Aracın sol taraftaki ses barının yerine çıkan Pano ses barı ([VolumeHudOverlay]): dikey, Pano
 * kartı renklerinde. Sürükleyerek ya da dokunarak ses ayarlanır.
 */
@Composable
fun SideVolumeHud() {
    val c = LocalPano.current
    val level = VolumeHudOverlay.level.intValue
    val max = VolumeHudOverlay.max.intValue.coerceAtLeast(1)
    val muted = VolumeHudOverlay.muted.value
    val fraction = if (muted) 0f else level / max.toFloat()
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).background(c.card).border(1.dp, c.hairline, shape)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            if (muted || level == 0) PanoIcons.SpeakerOff else PanoIcons.Speaker, null,
            tint = if (muted) Accent.Red else c.text, modifier = Modifier.size(30.dp),
        )
        Box(
            Modifier.weight(1f).width(64.dp).clip(RoundedCornerShape(20.dp)).background(c.raised)
                .semantics { contentDescription = "Ses $level" }
                .pointerInput(max) {
                    // Parmağın yüksekliği sesi belirler (aşağı sıfır, yukarı en yüksek).
                    fun valueAt(y: Float) = ((1f - (y / size.height).coerceIn(0f, 1f)) * max).let { kotlin.math.round(it).toInt() }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        VolumeHudOverlay.touching = true
                        VolumeHudOverlay.set(valueAt(down.position.y))
                        do {
                            val e = awaitPointerEvent()
                            e.changes.firstOrNull()?.let { ch ->
                                if (ch.pressed) { ch.consume(); VolumeHudOverlay.set(valueAt(ch.position.y)) }
                            }
                        } while (e.changes.any { it.pressed })
                        VolumeHudOverlay.touching = false
                        VolumeHudOverlay.scheduleHide()
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(fraction).background(c.text))
        }
        Text(
            if (muted) "Sessiz" else "$level", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        )
    }
}
