package app.pano.launcher.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.pano.launcher.R
import app.pano.launcher.settings.FontChoice
import app.pano.launcher.settings.LocalStyle
import app.pano.launcher.settings.StyleSettings
import app.pano.launcher.settings.ThemeMode

/** Inter: Apple'ın SF yazı tipine en yakın açık lisanslı font. */
val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * Renkler bilinçli olarak neredeyse renksiz: siyah, beyaz ve griler.
 * Renk yalnızca bir şeyin durumunu anlatırken kullanılıyor (mavi = etkin, kırmızı/mavi = sıcak/soğuk).
 */
data class PanoColors(
    val background: Color,
    val card: Color,
    val raised: Color,      // kart içindeki buton/kontrol zemini
    val hairline: Color,    // ince ayırıcı ve kart kenarı
    val text: Color,
    val secondary: Color,
    val tertiary: Color,
    val inverseText: Color,
    val glass: Color,       // harita üstündeki buzlu cam paneller
    val isDark: Boolean,
)

val LightPano = PanoColors(
    background = Color(0xFFF2F2F4),
    card = Color(0xFFFFFFFF),
    raised = Color(0xFFF4F4F6),
    hairline = Color(0x14000000),
    text = Color(0xFF0B0B0C),
    secondary = Color(0xFF6E6E73),
    tertiary = Color(0xFFAEAEB2),
    inverseText = Color(0xFFFFFFFF),
    glass = Color(0xEBFFFFFF),
    isDark = false,
)

val DarkPano = PanoColors(
    background = Color(0xFF000000),
    card = Color(0xFF121214),
    raised = Color(0xFF1E1E21),
    hairline = Color(0x17FFFFFF),
    text = Color(0xFFF5F5F7),
    secondary = Color(0xFF98989F),
    tertiary = Color(0xFF5A5A60),
    inverseText = Color(0xFF0B0B0C),
    glass = Color(0xE01C1C1F),
    isDark = true,
)

object Accent {
    val Blue = Color(0xFF0A84FF)
    val Green = Color(0xFF30D158)
    val Red = Color(0xFFFF453A)
    val Cold = Color(0xFF3B9BFF)
    val Warm = Color(0xFFFF6A3D)
    val SwitchOff = Color(0x29787880)
}

val LocalPano = staticCompositionLocalOf { LightPano }

@Composable
fun PanoTheme(style: StyleSettings, content: @Composable () -> Unit) {
    val auto = rememberAutoTheme()
    val dark = when (style.theme) {
        ThemeMode.Auto -> auto.dark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (dark) DarkPano else LightPano
    val font = if (style.font == FontChoice.Inter) Inter else FontFamily.Default
    CompositionLocalProvider(LocalPano provides colors, LocalStyle provides style, LocalAutoTheme provides auto) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = font, color = colors.text)) {
                content()
            }
        }
    }
}

/** Etkin öğelerin rengi: "tek renk" seçiliyse yazı rengi, değilse seçilen vurgu rengi. */
@Composable
fun activeColor(): Color = LocalStyle.current.accent.color ?: LocalPano.current.text

/** Etkin öğenin üstündeki yazı/ikon rengi. */
@Composable
fun onActiveColor(): Color = if (LocalStyle.current.accent.color == null) LocalPano.current.inverseText else Color.White

/** Bağlantı ve bilgi rengi: vurgu seçilmediyse sistem mavisi. */
@Composable
fun linkColor(): Color = LocalStyle.current.accent.color ?: Accent.Blue
