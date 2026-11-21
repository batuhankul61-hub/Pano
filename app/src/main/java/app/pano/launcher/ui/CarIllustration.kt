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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun svg(d: String): Path = PathParser().parsePathString(d).toPath()

// Çizim alanı 640×260 birim; ön taraf sağda.
private const val W = 640f
private const val H = 260f
private const val GROUND = 236f
private const val WHEEL_Y = 192f
private const val REAR_X = 154f
private const val FRONT_X = 482f

/**
 * Yandan görünüş kompakt SUV çizimi; oranlar BYD Atto 2'den alındı (boy 4,31 m, dingil mesafesi
 * 2,62 m, yükseklik 1,68 m): kısa kaput, dik arka kapak, kalın D direği, tavan rayları,
 * çamurluk ve marşpiyel kaplamaları, arkada boydan boya stop. Üreticinin görseli değil, özgün çizim.
 */
@Composable
fun CarIllustration(modifier: Modifier, dark: Boolean, background: Color) {
    val body = remember {
        svg(
            "M60 214 L56 200 C50 196 48 186 48 176 L52 128 C58 96 70 62 88 40 C92 33 98 30 108 29 " +
                "L330 25 C342 25 350 28 356 33 L432 98 C470 104 530 110 562 118 C580 122 590 130 592 144 " +
                "L594 176 C594 196 590 206 582 214 L529 214 A52 52 0 1 0 435 214 L201 214 A52 52 0 1 0 107 214 Z",
        )
    }
    // Yan camlar: arkada yukarı kıvrılan bel çizgisi, kalın D direği.
    val glass = remember {
        svg("M124 50 L330 40 C340 40 346 43 350 47 L414 100 L170 102 C148 102 130 96 118 84 Z")
    }
    val shoulder = remember { svg("M62 122 C250 113 450 110 584 128") }
    val crease = remember { svg("M210 172 C290 166 380 165 432 168") }
    val mirror = remember { svg("M398 98 L418 90 C428 88 432 98 424 104 L402 108 Z") }

    Canvas(modifier) {
        val s = min(size.width / W, size.height / H)
        val dx = (size.width - W * s) / 2f
        val dy = (size.height - H * s) / 2f
        translate(dx, dy) {
            scale(s, s, pivot = Offset.Zero) {
                // Yer gölgesi
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = if (dark) 0.55f else 0.22f), Color.Transparent),
                        center = Offset(320f, GROUND), radius = 300f,
                    ),
                    topLeft = Offset(40f, GROUND - 16f), size = Size(570f, 32f),
                )

                // Tavan rayları gövdenin biraz üstünde durur.
                drawLine(Color(0xFF1A1B1E), Offset(122f, 22f), Offset(318f, 17f), strokeWidth = 6f, cap = StrokeCap.Round)
                drawLine(Color(0xFF1A1B1E), Offset(140f, 26f), Offset(140f, 21f), strokeWidth = 5f)
                drawLine(Color(0xFF1A1B1E), Offset(300f, 22f), Offset(300f, 17f), strokeWidth = 5f)

                // Gövde: gündüz gümüş, gece grafit
                val bodyColors = if (dark) listOf(Color(0xFF4F535A), Color(0xFF2A2C31), Color(0xFF17181B))
                else listOf(Color(0xFFF3F4F6), Color(0xFFCDD1D7), Color(0xFF8F949C))
                drawPath(body, Brush.verticalGradient(bodyColors, startY = 25f, endY = 214f))

                clipPath(body) {
                    // Alt kısım hafifçe koyulaşır; araç yere oturur.
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.4f else 0.18f)), startY = 150f, endY = 214f),
                        topLeft = Offset(0f, 150f), size = Size(W, 64f),
                    )
                    // Çamurluk kaplamaları, marşpiyel ve tampon altları (mat siyah plastik).
                    val clad = Color(0xFF1C1D20)
                    for (cx in listOf(REAR_X, FRONT_X)) drawCircle(clad, radius = 57f, center = Offset(cx, WHEEL_Y))
                    drawRect(clad, topLeft = Offset(190f, 206f), size = Size(260f, 8f))
                    drawRect(clad, topLeft = Offset(40f, 196f), size = Size(80f, 18f))
                    drawRect(clad, topLeft = Offset(520f, 198f), size = Size(80f, 16f))
                }

                // Camlar ve direkler
                drawPath(
                    glass,
                    Brush.verticalGradient(
                        if (dark) listOf(Color(0xFF0B0C0E), Color(0xFF1E2024)) else listOf(Color(0xFF26292E), Color(0xFF4C515A)),
                        startY = 40f, endY = 104f,
                    ),
                )
                val pillar = if (dark) Color(0xFF2A2C31) else Color(0xFF9CA1A9)
                drawLine(pillar, Offset(298f, 40f), Offset(294f, 102f), strokeWidth = 7f) // B direği
                drawLine(pillar, Offset(176f, 46f), Offset(170f, 102f), strokeWidth = 6f) // C direği
                // Camın üst kenarında ince parlama
                drawLine(Color.White.copy(alpha = if (dark) 0.08f else 0.18f), Offset(132f, 52f), Offset(326f, 43f), strokeWidth = 2f)

                // Omuz çizgisi, alt kapı oyuğu, kapı ayrımları
                drawPath(shoulder, Color.White.copy(alpha = if (dark) 0.16f else 0.7f), style = Stroke(2.5f, cap = StrokeCap.Round))
                drawPath(crease, Color.Black.copy(alpha = if (dark) 0.25f else 0.1f), style = Stroke(2f, cap = StrokeCap.Round))
                val seam = Color.Black.copy(alpha = if (dark) 0.35f else 0.14f)
                drawLine(seam, Offset(296f, 104f), Offset(300f, 202f), strokeWidth = 1.5f)
                drawLine(seam, Offset(412f, 104f), Offset(426f, 160f), strokeWidth = 1.5f)
                drawLine(seam, Offset(172f, 104f), Offset(192f, 196f), strokeWidth = 1.5f)

                // Gömme kapı kolları
                val handle = if (dark) Color(0xFF1B1C1F) else Color(0xFF7D828A)
                drawLine(handle, Offset(244f, 128f), Offset(268f, 127f), strokeWidth = 4.5f, cap = StrokeCap.Round)
                drawLine(handle, Offset(360f, 125f), Offset(384f, 124f), strokeWidth = 4.5f, cap = StrokeCap.Round)

                // Ayna
                drawPath(mirror, Color(0xFF1C1D20))

                // Farlar: önde ince LED şerit, arkada boydan boya stop lambasının köşesi.
                drawLine(Color.White.copy(alpha = 0.95f), Offset(548f, 118f), Offset(584f, 126f), strokeWidth = 4f, cap = StrokeCap.Round)
                drawLine(Color(0xFFFF453A), Offset(60f, 106f), Offset(88f, 103f), strokeWidth = 5f, cap = StrokeCap.Round)
                drawLine(Color(0xFFFF453A).copy(alpha = 0.55f), Offset(58f, 114f), Offset(72f, 113f), strokeWidth = 2.5f, cap = StrokeCap.Round)

                for (cx in listOf(REAR_X, FRONT_X)) wheel(cx, background)
            }
        }
    }
}

/** Davlumbaz boşluğu, lastik ve beş kollu jant. */
private fun DrawScope.wheel(cx: Float, background: Color) {
    val c = Offset(cx, WHEEL_Y)
    // Davlumbazın içi; gövde altının aşağısına taşmasın.
    clipRect(top = 0f, bottom = 214f) {
        drawCircle(background, radius = 52f, center = c)
        drawCircle(Color(0xFF0A0B0C), radius = 50f, center = c)
    }
    drawCircle(Color(0xFF121315), radius = 44f, center = c)
    drawCircle(Color(0xFF24262A), radius = 44f, center = c, style = Stroke(2f))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFF9AA0A7), Color(0xFF41444A)), center = Offset(cx - 7f, WHEEL_Y - 9f), radius = 36f),
        radius = 30f, center = c,
    )
    for (i in 0 until 5) {
        val a = Math.toRadians(i * 72.0 - 90.0)
        drawLine(
            Color(0xFF1E2023),
            Offset(cx + (cos(a) * 9).toFloat(), WHEEL_Y + (sin(a) * 9).toFloat()),
            Offset(cx + (cos(a) * 28).toFloat(), WHEEL_Y + (sin(a) * 28).toFloat()),
            strokeWidth = 5f, cap = StrokeCap.Round,
        )
    }
    drawCircle(Color(0xFF2A2C30), radius = 8f, center = c)
}
