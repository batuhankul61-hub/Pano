package app.pano.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.pano.launcher.ui.theme.linkColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano
import app.pano.launcher.ui.theme.activeColor
import app.pano.launcher.ui.theme.onActiveColor
import app.pano.launcher.vehicle.ClimateState
import app.pano.launcher.vehicle.longTemp

@Composable
fun ClimateScreen(climate: ClimateState, onBack: () -> Unit) {
    val colors = LocalPano.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        ScreenHeader("Klima", onBack, subtitle = "Önizleme · araca gönderilmiyor") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillToggle("Otomatik", climate.auto) { climate.auto = !climate.auto }
                PillToggle("A/C", climate.ac) { climate.ac = !climate.ac }
                RoundIconButton(PanoIcons.Power, "Klimayı kapat", { climate.powerOff() }, colors.card, if (climate.isOff) colors.tertiary else Accent.Red)
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ZonePanel(
                title = "Sürücü", temp = climate.driver,
                onDown = { climate.changeDriver(-0.5f) }, onUp = { climate.changeDriver(0.5f) },
                seat = climate.driverSeat, onSeat = { climate.driverSeat = it },
                badge = null, modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            CenterPanel(climate, Modifier.weight(1.25f).fillMaxHeight())
            ZonePanel(
                title = "Yolcu", temp = climate.passenger,
                onDown = { climate.changePassenger(-0.5f) }, onUp = { climate.changePassenger(0.5f) },
                seat = climate.passengerSeat, onSeat = { climate.passengerSeat = it },
                badge = if (climate.sync) "Senkron" else null, modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/** Sıcaklık rakamı nötr; sadece belirgin soğuk/sıcakta hafifçe renklenir. */
private fun tempColor(t: Float, default: Color): Color = when {
    t <= 18f -> Accent.Cold
    t >= 26f -> Accent.Warm
    else -> default
}

@Composable
private fun ZonePanel(
    title: String,
    temp: Float,
    onDown: () -> Unit,
    onUp: () -> Unit,
    seat: Int,
    onSeat: (Int) -> Unit,
    badge: String?,
    modifier: Modifier,
) {
    val colors = LocalPano.current
    PanoCard(modifier, padding = 30.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CardLabel(null, title)
            Box(Modifier.weight(1f))
            if (badge != null) {
                Text(
                    badge, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.raised).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RoundIconButton(PanoIcons.ChevronUp, "$title sıcaklığını artır", onUp, Color.Transparent, colors.secondary, size = 72.dp, iconSize = 30.dp)
            BigNumber(longTemp(temp), size = 108.sp, color = tempColor(temp, colors.text), weight = FontWeight.Light)
            RoundIconButton(PanoIcons.ChevronDown, "$title sıcaklığını düşür", onDown, Color.Transparent, colors.secondary, size = 72.dp, iconSize = 30.dp)
        }
        CardLabel(PanoIcons.Seat, "Koltuk ısıtma", modifier = Modifier.padding(bottom = 12.dp))
        Segmented(listOf("Kapalı", "1", "2", "3"), seat, onSeat)
    }
}

@Composable
private fun CenterPanel(climate: ClimateState, modifier: Modifier) {
    val colors = LocalPano.current
    PanoCard(modifier, padding = 30.dp) {
        CardLabel(null, "Hava yönü", modifier = Modifier.padding(bottom = 14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeTile("Yüz", PanoIcons.AirFace, climate.face, Modifier.weight(1f)) { climate.face = !climate.face; climate.auto = false }
            ModeTile("Ayak", PanoIcons.AirFeet, climate.foot, Modifier.weight(1f)) { climate.foot = !climate.foot; climate.auto = false }
            ModeTile("Buğu çözme", PanoIcons.Defrost, climate.defrost, Modifier.weight(1f)) { climate.defrost = !climate.defrost; climate.auto = false }
        }

        Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            CardLabel(PanoIcons.Fan, "Fan")
            Box(Modifier.weight(1f))
            val label = when {
                climate.fan == 0 -> "Kapalı"
                climate.auto -> "Otomatik · ${climate.fan}"
                else -> "${climate.fan}"
            }
            Text(label, color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundIconButton(PanoIcons.Minus, "Fanı azalt", { climate.setFanLevel(climate.fan - 1) }, colors.raised, colors.text, size = 56.dp)
            Row(Modifier.weight(1f).height(56.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (level in 1..7) {
                    Box(
                        Modifier.weight(1f).height((16 + level * 5.7f).dp).clip(RoundedCornerShape(7.dp))
                            .background(if (level <= climate.fan) activeColor() else colors.raised)
                            .clickable(role = Role.Button) { climate.setFanLevel(level) },
                    )
                }
            }
            RoundIconButton(PanoIcons.Plus, "Fanı artır", { climate.setFanLevel(climate.fan + 1) }, colors.raised, colors.text, size = 56.dp)
        }

        Box(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleTile("Senkron", "Sürücü ile senkronize et", PanoIcons.Sync, climate.sync, Modifier.weight(1f)) {
                climate.setSyncEnabled(it)
            }
            ToggleTile("İç hava", "İç hava sirkülasyonu", PanoIcons.Recirculation, climate.recirculation, Modifier.weight(1f)) {
                climate.recirculation = it
            }
            ToggleTile("Arka cam", "Arka cam ısıtma", PanoIcons.RearDefrost, climate.rearDefrost, Modifier.weight(1f)) {
                climate.rearDefrost = it
            }
        }
    }
}

/** Açık olan mod renkleri tersine çevirir (gündüz siyah, gece beyaz kart). */
@Composable
private fun ModeTile(label: String, icon: ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalPano.current
    Column(
        modifier.height(124.dp).clip(RoundedCornerShape(22.dp)).background(if (on) activeColor() else colors.raised)
            .clickable(role = Role.Switch, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        val fg = if (on) onActiveColor() else colors.text
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(34.dp))
        Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Sürüşte kolay dokunulan büyük aç/kapa karosu (CarPlay kontrol merkezi havası).
 * Kapalıyken nötr zemin, açıkken vurgu rengiyle dolu; karonun tamamı dokunma alanı.
 */
@Composable
private fun ToggleTile(
    label: String,
    description: String,
    icon: ImageVector,
    on: Boolean,
    modifier: Modifier,
    onChange: (Boolean) -> Unit,
) {
    val colors = LocalPano.current
    val bg by animateColorAsState(if (on) linkColor() else colors.raised, tween(180), label = "tileBg")
    val fg by animateColorAsState(if (on) Color.White else colors.text, tween(180), label = "tileFg")
    val sub by animateColorAsState(if (on) Color.White.copy(alpha = 0.8f) else colors.secondary, tween(180), label = "tileSub")
    Column(
        modifier.height(118.dp).clip(RoundedCornerShape(24.dp)).background(bg)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = description }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(32.dp))
        Column {
            Text(label, color = fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (on) "Açık" else "Kapalı", color = sub, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}
