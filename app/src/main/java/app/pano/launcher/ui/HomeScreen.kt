package app.pano.launcher.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.Launch
import app.pano.launcher.media.MediaHub
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano
import app.pano.launcher.ui.theme.linkColor
import app.pano.launcher.vehicle.ClimateState
import app.pano.launcher.vehicle.VehicleHub
import app.pano.launcher.vehicle.decimal
import app.pano.launcher.vehicle.shortTemp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(climate: ClimateState, onOpenClimate: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TopBar()
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            VehicleCard(Modifier.weight(1.4f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                MapCard(Modifier.weight(300f).fillMaxWidth())
                NowPlayingCard(Modifier.weight(168f).fillMaxWidth())
                Row(Modifier.weight(164f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ClimateMini(climate, onOpenClimate, Modifier.weight(1f).fillMaxHeight())
                    ConsumptionMini(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1_000)
    }
}

@Composable
private fun TopBar() {
    val colors = LocalPano.current
    val now by rememberNow()
    val tr = Locale.forLanguageTag("tr-TR")
    val vehicle by VehicleHub.state.collectAsState()
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            SimpleDateFormat("HH:mm", tr).format(Date(now)),
            color = colors.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp,
        )
        Text(
            SimpleDateFormat("EEEE d MMMM", tr).format(Date(now)),
            color = colors.secondary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )
        Box(Modifier.weight(1f))
        BatteryGlyph(vehicle.batteryPercent ?: 0)
        Text(
            vehicle.batteryPercent?.let { "$it%" } ?: "—", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun BatteryGlyph(percent: Int) {
    val colors = LocalPano.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(28.dp).height(14.dp).border(1.5.dp, colors.tertiary, RoundedCornerShape(4.5.dp)).padding(2.5.dp),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).clip(RoundedCornerShape(2.dp)).background(colors.text))
        }
        Box(Modifier.padding(start = 1.5.dp).width(2.dp).height(5.dp).background(colors.tertiary, RoundedCornerShape(1.dp)))
    }
}

@Composable
private fun VehicleCard(modifier: Modifier) {
    val colors = LocalPano.current
    val context = LocalContext.current
    val vehicle by VehicleHub.state.collectAsState()
    val locked = vehicle.locked
    // Vites biliniyorsa o; önizlemede eski "Park" metni (etiket zaten "Önizleme").
    val gearLabel = vehicle.gear?.label ?: if (vehicle.isPreview) "Park" else null
    val status = listOfNotNull(
        gearLabel,
        locked?.let { if (it) "Kilitli" else "Kilit açık" },
    ).joinToString(" · ").ifEmpty { "—" }
    PanoCard(modifier, padding = 36.dp) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(vehicle.name, color = colors.text, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.6).sp)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(
                        if (locked == false) PanoIcons.Unlock else PanoIcons.Lock, contentDescription = null,
                        tint = colors.secondary, modifier = Modifier.size(18.dp),
                    )
                    Text(
                        status,
                        color = colors.secondary, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (vehicle.isPreview) {
                    Text(
                        "Önizleme · araç bağlı değil",
                        color = colors.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(10.dp))
                            .background(colors.raised).padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BigNumber(vehicle.rangeKm?.toString() ?: "—", size = 64.sp, weight = FontWeight.Light)
                    Text("km", color = colors.secondary, fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, bottom = 9.dp))
                }
                Box(Modifier.padding(top = 12.dp).width(200.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.raised)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((vehicle.batteryPercent ?: 0) / 100f).clip(RoundedCornerShape(3.dp)).background(Accent.Green))
                }
                Text(vehicle.batteryPercent?.let { "%$it batarya" } ?: "Batarya bilinmiyor", color = colors.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
            }
        }

        CarIllustration(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
            dark = colors.isDark, background = colors.card,
        )
        // Kilit/bagaj/şarj/ayna düğmeleri araçta komut almadığı için kaldırıldı.
    }
}

@Composable
private fun NowPlayingCard(modifier: Modifier) {
    val colors = LocalPano.current
    val context = LocalContext.current
    val playing by MediaHub.state.collectAsState()
    val now by rememberNow()

    PanoCard(modifier, padding = 20.dp) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val artSrc = rememberLaunchSource()
            Box(
                Modifier.fillMaxHeight().aspectRatio(1f).launchSource(artSrc).clip(RoundedCornerShape(18.dp)).background(colors.raised)
                    .clickable { Launch.music(context, playing?.packageName, artSrc.anchor()) },
                contentAlignment = Alignment.Center,
            ) {
                val art = playing?.art
                if (art != null) {
                    Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(PanoIcons.Music, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(44.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val p = playing
                if (p == null) {
                    Text("Müzik çalmıyor", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    if (!MediaHub.hasAccess(context)) {
                        Text(
                            "Çalan şarkıyı göstermek için bildirim erişimi ver",
                            color = linkColor(), fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { Launch.notificationAccess(context) },
                        )
                    } else {
                        Text("Müzik uygulamasını açmak için kapağa dokun", color = colors.secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Column {
                        Text(p.title, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.artist, color = colors.secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // "now" her saniye değiştiği için ilerleme çubuğu canlı akar.
                    val position = now.let {
                        if (p.isPlaying && p.positionUpdatedAt > 0) {
                            p.positionMs + ((SystemClock.elapsedRealtime() - p.positionUpdatedAt) * p.speed).toLong()
                        } else p.positionMs
                    }
                    val progress = if (p.durationMs > 0) (position.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.raised)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(colors.text))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        RoundIconButton(PanoIcons.Previous, "Önceki", { MediaHub.previous() }, colors.card, colors.text, size = 48.dp, iconSize = 26.dp)
                        RoundIconButton(
                            if (p.isPlaying) PanoIcons.Pause else PanoIcons.Play,
                            if (p.isPlaying) "Duraklat" else "Oynat",
                            { MediaHub.playPause() }, colors.card, colors.text, size = 48.dp, iconSize = 30.dp,
                        )
                        RoundIconButton(PanoIcons.Next, "Sonraki", { MediaHub.next() }, colors.card, colors.text, size = 48.dp, iconSize = 26.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClimateMini(climate: ClimateState, onOpen: () -> Unit, modifier: Modifier) {
    val colors = LocalPano.current
    PanoCard(modifier, padding = 22.dp, onClick = onOpen) {
        CardLabel(PanoIcons.Fan, if (climate.auto) "Klima · Otomatik" else "Klima")
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BigNumber(if (climate.isOff) "Kapalı" else shortTemp(climate.driver), size = 52.sp, weight = FontWeight.Light)
        }
        Text(if (climate.isOff) "Açmak için dokun" else "Fan ${climate.fan}", color = colors.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ConsumptionMini(modifier: Modifier) {
    val colors = LocalPano.current
    val vehicle by VehicleHub.state.collectAsState()
    PanoCard(modifier, padding = 22.dp) {
        CardLabel(PanoIcons.Bolt, "Tüketim")
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BigNumber(vehicle.consumptionKwh100?.let(::decimal) ?: "—", size = 52.sp, weight = FontWeight.Light)
        }
        // Değerin hangi pencereye ait olduğu; araç bağlı değilken örnek değer açıkça "Önizleme".
        val scope = when {
            vehicle.isPreview -> "Önizleme"
            vehicle.consumption?.recentWindow == true -> "Son 50 km"
            vehicle.consumption != null -> "Ortalama"
            else -> null
        }
        Text(
            if (scope != null) "kWh / 100 km · $scope" else "kWh / 100 km",
            color = colors.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
