package app.pano.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.pano.launcher.media.MediaHub
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Kaydırma yönü → parça. Sola kaydırma sonraki, sağa kaydırma önceki (telefonlardaki gibi). */
internal enum class TrackSwipe { Next, Previous, None }

/** Bırakılan kaydırma mesafesine göre karar; [threshold] px. Saf işlev: birim testi için. */
internal fun trackSwipeFor(offsetPx: Float, threshold: Float): TrackSwipe = when {
    offsetPx <= -threshold -> TrackSwipe.Next
    offsetPx >= threshold -> TrackSwipe.Previous
    else -> TrackSwipe.None
}

/**
 * Müzik kartını sağa/sola kaydırınca önceki/sonraki parçaya geçer. Kart parmağı izler (biraz
 * dirençle), eşiği geçince hafif titreşim verir, bırakınca yerine yaylanarak döner. Dokunma
 * (kartı açma, düğmeler) etkilenmez: kaydırma ancak dokunma eşiğini aşınca başlar.
 *
 * İçinde yatay sürüklenen başka bir şey (ilerleme çubuğu gibi) olan bir alana uygulanmamalı.
 */
internal fun Modifier.trackSwipe(enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed this
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    // Sürüşte rahat: kısa bir kaydırma yeter ama yanlışlıkla tetiklenmez.
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    var armed = TrackSwipe.None
    this
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { armed = TrackSwipe.None },
                onDragEnd = {
                    when (trackSwipeFor(offset.value, threshold)) {
                        TrackSwipe.Next -> MediaHub.next()
                        TrackSwipe.Previous -> MediaHub.previous()
                        TrackSwipe.None -> Unit
                    }
                    scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f)) }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    // Eşikten sonra direnç artar: kart ekrandan kaçmaz.
                    val next = offset.value + dx * if (abs(offset.value) > threshold) 0.35f else 0.8f
                    scope.launch { offset.snapTo(next) }
                    val now = trackSwipeFor(next, threshold)
                    if (now != armed) {
                        if (now != TrackSwipe.None) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        armed = now
                    }
                },
            )
        }
        .graphicsLayer {
            translationX = offset.value
            alpha = 1f - (abs(offset.value) / (threshold * 4f)).coerceAtMost(0.35f)
        }
}
