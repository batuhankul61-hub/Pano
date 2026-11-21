package app.pano.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.Launch
import app.pano.launcher.Screen
import app.pano.launcher.media.MediaHub
import app.pano.launcher.ui.theme.LocalPano
import app.pano.launcher.vehicle.ClimateState
import app.pano.launcher.vehicle.shortTemp

/** Her ekranda duran alt çubuk: iki uçta sıcaklıklar, ortada tek renkli uygulama ikonları. */
@Composable
fun Dock(climate: ClimateState, current: Screen, onNavigate: (Screen) -> Unit) {
    val colors = LocalPano.current
    val context = LocalContext.current
    val playing by MediaHub.state.collectAsState()

    Row(
        Modifier.fillMaxWidth().height(96.dp)
            .surface(RoundedCornerShape(30.dp), colors.card, colors.isDark, colors.hairline, elevation = 24.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TempStepper(
            value = climate.driver, off = climate.isOff, who = "Sürücü",
            onDown = { climate.changeDriver(-0.5f) }, onUp = { climate.changeDriver(0.5f) },
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            fun toggle(target: Screen) = onNavigate(if (current == target) Screen.Home else target)
            DockItem(PanoIcons.Apps, "Uygulamalar", { toggle(Screen.Apps) }, active = current == Screen.Apps, tileColor = Color(0xFF3A3A3C))
            // Uygulamalar dokunulan ikonun yerinden büyüyerek açılır.
            val mapsSrc = rememberLaunchSource()
            val musicSrc = rememberLaunchSource()
            val phoneSrc = rememberLaunchSource()
            DockItem(PanoIcons.Navigation, "Harita", { Launch.maps(context, mapsSrc.anchor()) }, tileColor = Color(0xFF0A84FF), modifier = Modifier.launchSource(mapsSrc))
            DockItem(PanoIcons.Music, "Müzik", { Launch.music(context, playing?.packageName, musicSrc.anchor()) }, tileColor = Color(0xFFFF375F), modifier = Modifier.launchSource(musicSrc))
            DockItem(PanoIcons.Phone, "Telefon", { Launch.phone(context, phoneSrc.anchor()) }, tileColor = Color(0xFF30D158), modifier = Modifier.launchSource(phoneSrc))
            DockItem(PanoIcons.Fan, "Klima", { toggle(Screen.Climate) }, active = current == Screen.Climate, tileColor = Color(0xFFFF9F0A))
            DockItem(PanoIcons.Sliders, "Ayarlar", { toggle(Screen.Settings) }, active = current == Screen.Settings, tileColor = Color(0xFF8E8E93))
        }
        TempStepper(
            value = climate.passenger, off = climate.isOff, who = "Yolcu",
            onDown = { climate.changePassenger(-0.5f) }, onUp = { climate.changePassenger(0.5f) },
        )
    }
}

@Composable
private fun TempStepper(value: Float, off: Boolean, who: String, onDown: () -> Unit, onUp: () -> Unit) {
    val colors = LocalPano.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(PanoIcons.ChevronLeft, "$who sıcaklığını düşür", onDown, Color.Transparent, colors.tertiary, size = 60.dp, iconSize = 24.dp)
        Text(
            if (off) "—" else shortTemp(value),
            color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.Normal, letterSpacing = (-1).sp,
            textAlign = TextAlign.Center, modifier = Modifier.width(84.dp).padding(bottom = 2.dp),
        )
        RoundIconButton(PanoIcons.ChevronRight, "$who sıcaklığını artır", onUp, Color.Transparent, colors.tertiary, size = 60.dp, iconSize = 24.dp)
    }
}
