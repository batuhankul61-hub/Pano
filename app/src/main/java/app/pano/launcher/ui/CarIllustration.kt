package app.pano.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Yandan görünüşlü, sade bir sedan çizimi. Gerçek araç görseli eklenene kadar
 * Tesla/Apple ekranlarındaki büyük araç görselinin yerini tutar.
 * Çizim 640×230 birimlik bir alanda yapılıp kartın boyutuna ölçeklenir.
 */
@Composable
fun CarIllustration(modifier: Modifier, dark: Boolean, background: Color) {
    val body = remember {
        PathParser().parsePathString(
            "M44 170 L30 142 C31 124 46 115 82 110 L172 101 C218 72 268 50 332 48 " +
                "C398 46 444 60 484 88 C534 96 584 104 608 116 C624 124 630 140 626 162 L620 170 Z",
        ).toPath()
    }
    val glass = remember {
        PathParser().parsePathString("M204 100 C244 74 284 60 334 58 C388 57 424 68 458 92 L204 100 Z").toPath()
    }
    val shoulder = remember {
        PathParser().parsePathString("M70 126 C250 116 450 113 604 123").toPath()
    }

    Canvas(modifier) {
        val s = min(size.width / 640f, size.height / 230f)
        val dx = (size.width - 640f * s) / 2f
        val dy = (size.height - 230f * s) / 2f
        translate(dx, dy) {
            scale(s, s, pivot = Offset.Zero) {
                // Yer gölgesi
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = if (dark) 0.55f else 0.22f), Color.Transparent),
                        center = Offset(330f, 212f), radius = 300f,
                    ),
                    topLeft = Offset(40f, 196f), size = Size(580f, 32f),
                )

                // Gövde: gündüz gümüş, gece grafit
                val bodyColors = if (dark) listOf(Color(0xFF4A4D53), Color(0xFF26282C), Color(0xFF151618))
                else listOf(Color(0xFFF1F2F4), Color(0xFFC9CDD3), Color(0xFF8E939B))
                drawPath(body, Brush.verticalGradient(bodyColors, startY = 48f, endY = 172f))

                // Camlar
                drawPath(
                    glass,
                    Brush.verticalGradient(
                        if (dark) listOf(Color(0xFF0B0C0E), Color(0xFF1C1E22)) else listOf(Color(0xFF2B2E33), Color(0xFF4A4F57)),
                        startY = 58f, endY = 100f,
                    ),
                )
                drawLine(background.copy(alpha = 0.9f), Offset(334f, 58f), Offset(329f, 100f), strokeWidth = 5f)

                // Omuz çizgisi ve kapı ayrımı
                drawPath(shoulder, Color.White.copy(alpha = if (dark) 0.18f else 0.75f), style = Stroke(2.5f, cap = StrokeCap.Round))
                drawLine(Color.Black.copy(alpha = 0.12f), Offset(330f, 104f), Offset(332f, 164f), strokeWidth = 1.5f)

                // Farlar
                drawLine(Color.White.copy(alpha = 0.95f), Offset(590f, 118f), Offset(616f, 124f), strokeWidth = 4f, cap = StrokeCap.Round)
                drawLine(Color(0xFFFF453A), Offset(36f, 124f), Offset(62f, 118f), strokeWidth = 5f, cap = StrokeCap.Round)

                // Alt marşpiyel: gövdenin alt kısmı hafifçe koyulaşır, araç yere oturur.
                clipPath(body) {
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.45f else 0.22f)), startY = 138f, endY = 172f),
                        topLeft = Offset(0f, 138f), size = Size(640f, 36f),
                    )
                }

                // Tekerlek davlumbazları ve aero jantlı tekerlekler
                val wy = 166f
                for (cx in listOf(156f, 518f)) {
                    drawCircle(background, radius = 43f, center = Offset(cx, wy))
                    drawCircle(Color(0xFF0E0F11), radius = 37f, center = Offset(cx, wy))
                    drawCircle(
                        Brush.radialGradient(listOf(Color(0xFF8D9299), Color(0xFF3E4146)), center = Offset(cx - 6f, wy - 8f), radius = 30f),
                        radius = 25f, center = Offset(cx, wy),
                    )
                    for (i in 0 until 5) {
                        val a = Math.toRadians(i * 72.0 - 90.0)
                        drawLine(
                            Color(0xFF1E2023),
                            Offset(cx + (cos(a) * 8).toFloat(), wy + (sin(a) * 8).toFloat()),
                            Offset(cx + (cos(a) * 23).toFloat(), wy + (sin(a) * 23).toFloat()),
                            strokeWidth = 3.5f, cap = StrokeCap.Round,
                        )
                    }
                    drawCircle(Color(0xFF2A2C30), radius = 7f, center = Offset(cx, wy))
                }
            }
        }
    }
}
