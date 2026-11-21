package app.pano.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.settings.DockStyle
import app.pano.launcher.settings.LocalStyle
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano
import app.pano.launcher.ui.theme.activeColor
import app.pano.launcher.ui.theme.onActiveColor
import kotlin.math.min

/**
 * Tasarım 1600×900 birimlik bir tuval üzerinde çizildi. Bu sarmalayıcı, ekran ne olursa olsun
 * o tuvali ekrana sığacak şekilde ölçekler; böylece ölçüler tasarımdakiyle birebir kalır.
 */
@Composable
fun DesignCanvas(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = min(maxWidth.value / 1600f, maxHeight.value / 900f)
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, 1f)) {
            content()
        }
    }
}

/** Gündüz yumuşak gölgeli beyaz kart, gece ince kenarlı koyu kart. */
fun Modifier.surface(shape: Shape, color: Color, dark: Boolean, hairline: Color, elevation: Dp = 18.dp): Modifier =
    if (dark) this.clip(shape).background(color).border(1.dp, hairline, shape)
    else this.shadow(elevation, shape, ambientColor = Color(0x14000000), spotColor = Color(0x1F000000)).background(color, shape).clip(shape)

@Composable
fun PanoCard(
    modifier: Modifier = Modifier,
    padding: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPano.current
    var m = modifier.surface(RoundedCornerShape(30.dp), colors.card, colors.isDark, colors.hairline)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

/** Kart başlığı: küçük ikon + gri etiket. */
@Composable
fun CardLabel(icon: ImageVector?, text: String, modifier: Modifier = Modifier, color: Color = LocalPano.current.secondary) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Text(
            text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp),
        )
    }
}

/** Büyük, ince rakamlar (Tesla/Apple tarzı). */
@Composable
fun BigNumber(text: String, size: TextUnit = 56.sp, color: Color = LocalPano.current.text, weight: FontWeight = FontWeight.Normal) {
    Text(text, color = color, fontSize = size, fontWeight = weight, letterSpacing = (-size.value * 0.035f).sp, lineHeight = size)
}

/**
 * Dock ikonu. "Tek renk" stilinde etkin olan yumuşak bir kapsülle öne çıkar;
 * "Renkli" stilde her uygulama kendi renginde yuvarlatılmış bir kare olur.
 */
@Composable
fun DockItem(
    icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, tileColor: Color = Color.Gray,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPano.current
    val style = LocalStyle.current
    val base = modifier
        .width(96.dp)
        .height(72.dp)
        .clip(RoundedCornerShape(22.dp))
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }

    if (style.dock == DockStyle.Colored) {
        Box(base, contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(66.dp)
                    .border(2.5.dp, if (active) activeColor() else Color.Transparent, RoundedCornerShape(20.dp))
                    .padding(4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(tileColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    } else {
        val accent = style.accent.color
        val bg = when {
            !active -> Color.Transparent
            accent != null -> accent
            else -> colors.raised
        }
        val fg = when {
            !active -> colors.secondary
            accent != null -> Color.White
            else -> colors.text
        }
        Box(base.background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(32.dp))
        }
    }
}

/** Yuvarlak, ince kenarlı araç komutu + altında etiket (Kilit, Bagaj…). */
@Composable
fun VehicleAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalPano.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.raised)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.text, modifier = Modifier.size(28.dp))
        }
        Text(label, color = colors.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
    }
}

/** Yuvarlak ikon butonu (+ / − / geri). */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    background: Color,
    tint: Color,
    size: Dp = 52.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Kapsül şeklinde aç/kapa butonu (Otomatik, A/C). Açıkken renkleri tersine döner. */
@Composable
fun PillToggle(text: String, on: Boolean, onClick: () -> Unit) {
    val colors = LocalPano.current
    Box(
        Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (on) activeColor() else colors.card)
            .border(1.dp, if (on) Color.Transparent else colors.hairline, RoundedCornerShape(26.dp))
            .clickable(role = Role.Switch, onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (on) onActiveColor() else colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** iOS tarzı anahtar. */
@Composable
fun PanoSwitch(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .width(64.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (checked) Accent.Green else Accent.SwitchOff)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics { contentDescription = label }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(32.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape))
    }
}

/** iOS tarzı segment kontrolü: seçili parça kabarık beyaz bir kart olur. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalPano.current
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(colors.raised).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            val segShape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .then(
                        if (isSel) Modifier.shadow(if (colors.isDark) 0.dp else 4.dp, segShape)
                            .background(if (colors.isDark) Color(0xFF3A3A3E) else Color.White, segShape)
                        else Modifier,
                    )
                    .clip(segShape)
                    .clickable(role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (isSel) colors.text else colors.secondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    val colors = LocalPano.current
    Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(PanoIcons.ChevronLeft, "Geri", onBack, colors.card, colors.text, iconSize = 22.dp)
        Text(
            title, color = colors.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp,
            modifier = Modifier.padding(start = 20.dp),
        )
        if (subtitle != null) {
            Text(subtitle, color = colors.secondary, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
        }
        Box(Modifier.weight(1f))
        trailing()
    }
}
