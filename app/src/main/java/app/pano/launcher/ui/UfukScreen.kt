package app.pano.launcher.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.Launch
import app.pano.launcher.Screen
import app.pano.launcher.media.MediaHub
import app.pano.launcher.navigation.NavHub
import app.pano.launcher.navigation.NavPhase
import app.pano.launcher.navigation.PanoNav
import app.pano.launcher.navigation.formatDistance
import app.pano.launcher.settings.LocalStyle
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano
import app.pano.launcher.ui.theme.PanoColors
import app.pano.launcher.vehicle.ClimateState
import app.pano.launcher.vehicle.Gear
import app.pano.launcher.vehicle.VehicleHub
import app.pano.launcher.vehicle.decimal
import app.pano.launcher.vehicle.shortTemp
import app.pano.launcher.weather.WeatherHub
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Ufuk: haritayı tuval yapan üçüncü düzen (Ayarlar > Geliştirici > Stil > Arayüz).
 *
 * İlkeler:
 *  - Ekranın çoğu harita. Araç ve sürüş bilgisi solda dar bir sütunda.
 *  - Altta her zaman aynı yerde duran opak çubuk: sıcaklıklar, uygulamalar, ses.
 *  - Klima, Uygulamalar, Ayarlar, Müzik ve "Nereye?" haritanın üstünde opak bir kart olarak açılır;
 *    araç sütunu ve alt çubuk yerinde kalır.
 *  - Bilgi tek yerde: batarya yalnızca araç sütununda, sıcaklık yalnızca alt çubukta.
 *  - Verisi olmayan kart çizilmez; "Önizleme" tek yerde, araç sütununun başında yazar.
 *  - Tek renk: renk yalnızca durum anlatır (etkin vites, uyarı, sıcak/soğuk).
 *  - Sürüşte okunacak metin en az 18 sp, dokunma alanları en az 72 dp.
 * Klasik ve CarPlay düzenlerine dokunmaz.
 */

private val trLocale: Locale = Locale.forLanguageTag("tr-TR")

private const val UfukMotionMs = 240

/** Araç sütununun genişliği (tasarım tuvalinde). */
private val CarColumnWidth = 500.dp

/** Rota önizlemesinde "Nereye?" kartının kapladığı harita oranı (sağdan). */
private const val NavNarrowInset = 0.56f

/** Alt çubuğun yüksekliği. */
private val BarHeight = 112.dp

/** Sürüşte kısıtlama: araç hareket ediyor **ve** ayarlarda açık. */
@Composable
private fun ufukDrivingLocked(): Boolean {
    val driving by VehicleHub.isDriving.collectAsState()
    return driving && LocalStyle.current.drivingLock
}

/** Haritanın üstünde açılan kart. */
private enum class UfukPanel { Climate, Apps, Settings, Music, Nav }

@Composable
fun UfukApp(screen: Screen, climate: ClimateState, onNavigate: (Screen) -> Unit) {
    val colors = LocalPano.current
    val panel: UfukPanel? = when {
        screen == Screen.Climate -> UfukPanel.Climate
        screen == Screen.Apps -> UfukPanel.Apps
        screen == Screen.Settings -> UfukPanel.Settings
        AppSheet.nav -> UfukPanel.Nav
        AppSheet.music -> UfukPanel.Music
        else -> null
    }
    // Aynı anda tek kart: yerel ekran açılınca müzik / nereye kapanır; onlar açılınca ekran ana sayfaya döner.
    LaunchedEffect(screen) { if (screen != Screen.Home) AppSheet.close() }
    val sheetOpen = AppSheet.nav || AppSheet.music
    LaunchedEffect(sheetOpen) { if (sheetOpen && screen != Screen.Home) onNavigate(Screen.Home) }

    fun closePanel() {
        AppSheet.close()
        onNavigate(Screen.Home)
    }

    Box(
        Modifier.fillMaxSize().background(colors.background)
            .then(if (!LocalStyle.current.hideSystemBar) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier),
    ) {
        DesignCanvas {
            Column(Modifier.fillMaxSize().returnZoom()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Rota seçilince "Nereye?" kartı haritanın sağ yarısına çekilir; rota sol yarıda görünür.
                    val navSession by PanoNav.state.collectAsState()
                    val navNarrow = panel == UfukPanel.Nav && navSession != null
                    Row(Modifier.fillMaxSize()) {
                        CarColumn(Modifier.width(CarColumnWidth).fillMaxHeight())
                        MapCanvas(Modifier.weight(1f).fillMaxHeight(), endInset = if (navNarrow) NavNarrowInset else 0f)
                    }
                    // Kartlar haritanın üstünde açılır; klima geniş olduğu için araç sütununu da örter.
                    PanelHost(panel, navNarrow, climate, onNavigate, onClose = ::closePanel)
                }
                BottomBar(climate, screen, panel, onNavigate, Modifier.fillMaxWidth().height(BarHeight))
            }
        }
    }
}

// ───────────────────────── Araç sütunu ─────────────────────────

@Composable
private fun CarColumn(modifier: Modifier) {
    val c = LocalPano.current
    val context = LocalContext.current
    val v by VehicleHub.state.collectAsState()
    val driving by VehicleHub.isDriving.collectAsState()
    val locked = ufukDrivingLocked()

    Column(modifier.background(c.card).padding(start = 36.dp, end = 32.dp, top = 28.dp, bottom = 20.dp)) {
        // Vites: hepsi görünür, etkin olan beyaz. Bilinmiyorsa hiçbiri vurgulanmaz (uydurma "P" yok).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Gear.entries.forEach { g ->
                    val on = v.gear == g || (v.gear == null && g == Gear.D && driving)
                    Text(
                        g.name, fontSize = 28.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) c.text else c.tertiary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            BatteryReadout(v.batteryPercent, v.rangeKm, c)
        }

        // Hız: sütunun en büyük öğesi. Bilinmiyorsa "—".
        Row(Modifier.fillMaxWidth().padding(top = 18.dp)) {
            // Hız bilinmiyorsa kısa, soluk bir çizgi (uydurma 0 yok).
            val speed = v.speedKmh
            // Pano'nun yönlendirmesi hız sınırını biliyorsa levha; aşılınca hız kırmızı. Bilinmiyorsa levha yok.
            val limit = navSpeedLimit()
            val over = limit != null && speed != null && speed > limit
            Text(
                speed?.toString() ?: "–", color = when { speed == null -> c.tertiary; over -> Color(0xFFFF453A); else -> c.text }, fontSize = 132.sp,
                fontWeight = FontWeight.Light, letterSpacing = (-5).sp, lineHeight = 132.sp,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                "km/sa", color = c.secondary, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.alignByBaseline().padding(start = 12.dp),
            )
            if (limit != null) {
                Spacer(Modifier.weight(1f))
                SpeedLimitSign(limit, 92.dp, Modifier.align(Alignment.CenterVertically))
            }
        }

        // Önizleme yalnızca burada, bir kez.
        if (v.isPreview) {
            Text(
                "Önizleme · araç bağlı değil", color = c.secondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.raised).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        // Sürüş modu, rejen, tüketim: yalnızca araçtan okununca.
        val chips = listOfNotNull(
            v.driveMode?.label,
            v.regen?.let { "Rejen ${it.label}" },
            v.consumption?.takeIf { !v.isPreview }?.let { "${decimal(it.kwh100)} kWh/100 km" },
        )
        if (chips.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chips.forEach { label ->
                    Text(
                        label, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.raised).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // Araç kontrolleri çizimin hemen üstünde, ilgili yerin hizasında: bagaj arkada (solda),
        // kilit ortada, şarj kapağı önde (sağda). Sürüşte gizlenir, çizim büyür.
        Spacer(Modifier.weight(0.25f))
        val open = v.openings?.open.orEmpty()
        if (open.isNotEmpty()) {
            Text(
                open.take(3).joinToString(" · ") { "${it.label} açık" }, color = Accent.Red, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        AnimatedVisibility(!locked, enter = fadeIn(tween(UfukMotionMs)), exit = fadeOut(tween(UfukMotionMs))) {
            val notYet = { Launch.notConnectedYet(context) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CarHotspot(PanoIcons.Trunk, if (v.openings?.trunk == true) "Bagaj açık" else "Bagaj", notYet, Modifier)
                CarHotspot(
                    if (v.locked == false) PanoIcons.Unlock else PanoIcons.Lock,
                    when (v.locked) { true -> "Kilitli"; false -> "Açık"; null -> "Kilit" },
                    notYet, Modifier,
                )
                CarHotspot(PanoIcons.Bolt, "Şarj", notYet, Modifier)
            }
        }
        CarIllustration(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), dark = c.isDark, background = Color.Transparent)
        // Müzik yalnızca bir şey çalıyorsa yer kaplar.
        NowPlayingStrip()
    }
}

@Composable
private fun BatteryReadout(percent: Int?, rangeKm: Int?, c: PanoColors) {
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(rangeKm?.toString() ?: "—", color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp)
            Text("km", color = c.secondary, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(64.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.raised)) {
                val p = (percent ?: 0).coerceIn(0, 100) / 100f
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(5.dp))
                        .background(if ((percent ?: 100) <= 15) Accent.Red else c.text),
                )
            }
            Text(
                "%${percent ?: "—"}", color = c.secondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/** Çizimin üstünde etiketli dokunma noktası (≥72 dp). */
@Composable
private fun CarHotspot(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    val c = LocalPano.current
    Row(
        modifier.height(72.dp).clip(RoundedCornerShape(36.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(c.glass)
                .border(1.dp, c.hairline, RoundedCornerShape(26.dp)).padding(start = 14.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(24.dp))
            Text(label, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun NowPlayingStrip() {
    val c = LocalPano.current
    val playing by MediaHub.state.collectAsState()
    val p = playing
    AnimatedVisibility(p != null, enter = fadeIn(tween(UfukMotionMs)), exit = fadeOut(tween(UfukMotionMs))) {
        if (p == null) return@AnimatedVisibility
        Row(
            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(24.dp)).background(c.raised)
                .clickable(role = Role.Button, onClickLabel = "Şimdi çalıyor") { AppSheet.openMusic() }
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(c.card), contentAlignment = Alignment.Center) {
                val art = p.art
                if (art != null) {
                    Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(PanoIcons.Music, contentDescription = null, tint = c.secondary, modifier = Modifier.size(30.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(p.title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.artist.isNotBlank()) {
                    Text(p.artist, color = c.secondary, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            RoundIconButton(PanoIcons.Previous, "Önceki", { MediaHub.previous() }, Color.Transparent, c.text, size = 72.dp, iconSize = 26.dp)
            RoundIconButton(
                if (p.isPlaying) PanoIcons.Pause else PanoIcons.Play, if (p.isPlaying) "Duraklat" else "Çal",
                { MediaHub.playPause() }, Color.Transparent, c.text, size = 72.dp, iconSize = 30.dp,
            )
            RoundIconButton(PanoIcons.Next, "Sonraki", { MediaHub.next() }, Color.Transparent, c.text, size = 72.dp, iconSize = 26.dp)
        }
    }
}

// ───────────────────────── Harita tuvali ─────────────────────────

@Composable
private fun MapCanvas(modifier: Modifier, endInset: Float) {
    val c = LocalPano.current
    val context = LocalContext.current
    val granted = remember { hasLocationPermission(context) }
    LaunchedEffect(Unit) { PanoNav.init(context) }
    val own by PanoNav.state.collectAsState()
    val session = own?.takeIf { it.phase != NavPhase.Preview && it.route != null }
    val external by NavHub.state.collectAsState()

    Box(modifier.background(if (c.isDark) Color(0xFF16181C) else Color(0xFFE8E8ED))) {
        LiveMap(Modifier.fillMaxSize(), dark = c.isDark, showLocation = granted, bottomInset = 0f, endInset = endInset, interactive = true)
        val controls = rememberMapControls()
        // Yönlendirmede harita elle kaydırıldıysa bir süre dokunulmayınca araca geri döner.
        val free = controls.isFree
        LaunchedEffect(free, session != null) {
            if (free && session != null) {
                delay(AutoRecenterMs)
                controls.recenter()
            }
        }

        // Üst sol: arama ya da sıradaki manevra.
        val top = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 24.dp).width(560.dp)
        val step = external
        when {
            session != null -> {
                val route = session.route!!
                val p = session.progress
                val m = route.maneuvers[(p?.nextIndex ?: 0).coerceIn(0, route.maneuvers.lastIndex)]
                val arrived = session.phase == NavPhase.Arrived
                ManeuverCard(
                    distance = if (arrived) null else p?.let { formatDistance(it.distanceToNextM) },
                    title = if (arrived) "Vardınız: ${session.destination.name}" else m.instruction,
                    eta = if (session.rerouting) "Rota yeniden hesaplanıyor…" else null,
                    vector = if (arrived) PanoIcons.Flag else maneuverIcon(m.type),
                    lanes = if (arrived) null else p?.lanes,
                    modifier = top,
                ) { AppSheet.openNav() }
                NavBottomBar(session, Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 24.dp).width(560.dp))
            }
            step != null -> ManeuverCard(
                distance = step.distance, title = step.street ?: step.maneuver, eta = step.eta, bitmap = step.icon, modifier = top,
            ) { Launch.maps(context) }
            // "Nereye?" kartı açıkken arama kutusu tekrar olur ve kartın arkasında yarım kalır.
            AppSheet.nav -> Unit
            else -> Row(
                top.height(72.dp).clip(RoundedCornerShape(36.dp)).background(c.glass)
                    .border(1.dp, c.hairline, RoundedCornerShape(36.dp))
                    .clickable(role = Role.Button) { AppSheet.openNav() }
                    .padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PanoIcons.Search, contentDescription = null, tint = c.secondary, modifier = Modifier.size(26.dp))
                Text("Nereye gidiyoruz?", color = c.secondary, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 14.dp))
            }
        }

        // Üst sağ: saat ve dış hava. Sistem durum çubuğu yerine.
        ClockWeather(Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 24.dp))

        // Sağ alt: yakınlaştır / uzaklaştır ve elle kaydırılınca "Ortala".
        MapButtons(controls, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 24.dp))
        // OpenStreetMap lisansı gereği atıf görünür kalmalı.
        Text(
            "© OpenFreeMap © OpenMapTiles © OpenStreetMap",
            color = c.secondary, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                .clip(RoundedCornerShape(6.dp)).background(c.glass).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ClockWeather(modifier: Modifier) {
    val c = LocalPano.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1_000) }
    }
    val weather by WeatherHub.state.collectAsState()
    Row(
        modifier.height(72.dp).clip(RoundedCornerShape(36.dp)).background(c.glass)
            .border(1.dp, c.hairline, RoundedCornerShape(36.dp)).padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(SimpleDateFormat("HH:mm", trLocale).format(Date(now)), color = c.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        weather?.let { w ->
            Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(28.dp).background(c.hairline))
            Text("${Math.round(w.temperatureC)}°", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Text(w.description, color = c.secondary, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** Yönlendirmede elle kaydırılan harita bu kadar süre sonra araca döner. */
private const val AutoRecenterMs = 12_000L

@Composable
private fun MapButtons(controls: MapControls, modifier: Modifier) {
    val c = LocalPano.current
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.width(72.dp).clip(RoundedCornerShape(36.dp)).background(c.glass)
                .border(1.dp, c.hairline, RoundedCornerShape(36.dp)),
        ) {
            RoundIconButton(PanoIcons.Plus, "Yakınlaştır", { controls.zoomIn() }, Color.Transparent, c.text, size = 72.dp, iconSize = 28.dp)
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(c.hairline))
            RoundIconButton(PanoIcons.Minus, "Uzaklaştır", { controls.zoomOut() }, Color.Transparent, c.text, size = 72.dp, iconSize = 28.dp)
        }
        AnimatedVisibility(controls.isFree, enter = fadeIn(tween(UfukMotionMs)), exit = fadeOut(tween(UfukMotionMs))) {
            Row(
                Modifier.height(72.dp).clip(RoundedCornerShape(36.dp)).background(c.text)
                    .clickable(role = Role.Button) { controls.recenter() }
                    .semantics { contentDescription = "Haritayı araca ortala" }
                    .padding(start = 20.dp, end = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PanoIcons.Location, contentDescription = null, tint = c.inverseText, modifier = Modifier.size(26.dp))
                Text("Ortala", color = c.inverseText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

// ───────────────────────── Harita üstü kart ─────────────────────────

@Composable
private fun BoxScope.PanelHost(panel: UfukPanel?, navNarrow: Boolean, climate: ClimateState, onNavigate: (Screen) -> Unit, onClose: () -> Unit) {
    val c = LocalPano.current
    var shown by remember { mutableStateOf<UfukPanel?>(null) }
    if (panel != null) shown = panel
    AnimatedVisibility(
        visible = panel != null,
        enter = fadeIn(tween(UfukMotionMs)) + slideInHorizontally(tween(UfukMotionMs)) { -it / 12 },
        exit = fadeOut(tween(UfukMotionMs / 2)) + slideOutHorizontally(tween(UfukMotionMs)) { -it / 12 },
        modifier = Modifier.matchParentSize(),
    ) {
        val p = shown ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(28.dp)
        Box(
            Modifier.fillMaxSize()
                .padding(
                    start = when {
                        p == UfukPanel.Climate -> 0.dp
                        navNarrow -> CarColumnWidth + (1600.dp - CarColumnWidth) * (1f - NavNarrowInset)
                        else -> CarColumnWidth
                    },
                )
                .padding(16.dp).clip(shape).background(c.card).border(1.dp, c.hairline, shape),
        ) {
            AnimatedContent(
                p,
                transitionSpec = { fadeIn(tween(UfukMotionMs)).togetherWith(fadeOut(tween(UfukMotionMs / 2))) },
                label = "ufuk-kart",
                modifier = Modifier.fillMaxSize(),
            ) { target ->
                val inner = Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 20.dp)
                when (target) {
                    UfukPanel.Climate -> Box(inner) { ClimateScreen(climate) { onNavigate(Screen.Home) } }
                    UfukPanel.Apps -> Box(inner) { DrivingGate("Uygulamalar", onClose) { AppsScreen { onNavigate(Screen.Home) } } }
                    UfukPanel.Settings -> Box(inner) { DrivingGate("Ayarlar", onClose) { SettingsScreen { onNavigate(Screen.Home) } } }
                    UfukPanel.Music -> PanelWithTitle("Şimdi çalıyor", onClose) {
                        NowPlayingScreen(Modifier.fillMaxSize().padding(start = 36.dp, end = 36.dp, bottom = 28.dp))
                    }
                    UfukPanel.Nav -> PanelWithTitle("Nereye?", onClose) {
                        NavScreen(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 24.dp)) { onClose() }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelWithTitle(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalPano.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(84.dp).padding(start = 32.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            RoundIconButton(PanoIcons.Close, "Kapat", onClose, c.raised, c.text, size = 72.dp, iconSize = 24.dp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
private fun DrivingGate(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalPano.current
    if (!ufukDrivingLocked()) {
        content()
        return
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("$title sürüş sırasında kapalı", color = c.text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text("Araç durunca kendiliğinden açılır.", color = c.secondary, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
        Box(
            Modifier.padding(top = 32.dp).height(72.dp).clip(RoundedCornerShape(36.dp)).background(c.raised)
                .clickable(role = Role.Button, onClick = onClose).padding(horizontal = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Haritaya dön", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ───────────────────────── Alt çubuk ─────────────────────────

@Composable
private fun BottomBar(climate: ClimateState, screen: Screen, panel: UfukPanel?, onNavigate: (Screen) -> Unit, modifier: Modifier) {
    val c = LocalPano.current
    val context = LocalContext.current
    val playing by MediaHub.state.collectAsState()
    val locked = ufukDrivingLocked()
    fun toggle(target: Screen) = onNavigate(if (screen == target) Screen.Home else target)

    Row(
        modifier.background(c.background).drawBehind { drawLine(c.hairline, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarTemp(
            "Sürücü", climate.driver, climate.isOff,
            onDown = { climate.changeDriver(-0.5f) }, onUp = { climate.changeDriver(0.5f) },
            onOpen = { toggle(Screen.Climate) },
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            BarApp(PanoIcons.Music, "Müzik", active = panel == UfukPanel.Music) {
                when {
                    AppSheet.music -> AppSheet.close()
                    playing != null -> AppSheet.openMusic()
                    else -> Launch.music(context, null)
                }
            }
            BarApp(PanoIcons.Phone, "Telefon") { Launch.phone(context) }
            BarApp(PanoIcons.Fan, "Klima", active = panel == UfukPanel.Climate) { toggle(Screen.Climate) }
            BarApp(PanoIcons.Apps, "Uygulamalar", active = panel == UfukPanel.Apps, dimmed = locked) { toggle(Screen.Apps) }
            BarApp(PanoIcons.Sliders, "Ayarlar", active = panel == UfukPanel.Settings, dimmed = locked) { toggle(Screen.Settings) }
        }
        if (climate.dualZone) {
            BarTemp(
                "Yolcu", climate.passenger, climate.isOff,
                onDown = { climate.changePassenger(-0.5f) }, onUp = { climate.changePassenger(0.5f) },
                onOpen = { toggle(Screen.Climate) },
            )
        }
        Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(48.dp).background(c.hairline))
        VolumeControl()
    }
}

/** Sıcaklık: oklar ≥72 dp, sayıya dokununca klima kartı açılır. */
@Composable
private fun BarTemp(who: String, value: Float, off: Boolean, onDown: () -> Unit, onUp: () -> Unit, onOpen: () -> Unit) {
    val c = LocalPano.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(PanoIcons.ChevronLeft, "$who sıcaklığını düşür", onDown, Color.Transparent, c.secondary, size = 72.dp, iconSize = 30.dp)
        Box(
            Modifier.width(104.dp).height(72.dp).clip(RoundedCornerShape(20.dp))
                .clickable(role = Role.Button, onClickLabel = "Klima") { onOpen() }
                .semantics { contentDescription = "$who sıcaklığı" },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (off) "Kapalı" else shortTemp(value), color = c.text, fontSize = if (off) 24.sp else 36.sp, fontWeight = FontWeight.Normal, letterSpacing = (-1).sp)
        }
        RoundIconButton(PanoIcons.ChevronRight, "$who sıcaklığını artır", onUp, Color.Transparent, c.secondary, size = 72.dp, iconSize = 30.dp)
    }
}

/** Tek renk uygulama düğmesi; etkin olan açık zeminle belirginleşir. */
@Composable
private fun BarApp(icon: ImageVector, label: String, active: Boolean = false, dimmed: Boolean = false, onClick: () -> Unit) {
    val c = LocalPano.current
    Box(
        Modifier.width(96.dp).height(80.dp).clip(RoundedCornerShape(22.dp))
            .background(if (active) c.raised else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = null, modifier = Modifier.size(34.dp),
            tint = when {
                dimmed -> c.tertiary
                active -> c.text
                else -> c.secondary
            },
        )
    }
}

/** Ses: − / hoparlör (sessize al) / +. Medya ses akışını değiştirir. */
@Composable
private fun VolumeControl() {
    val c = LocalPano.current
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var level by remember { mutableIntStateOf(audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) }
    var muted by remember { mutableStateOf(audio?.isStreamMute(AudioManager.STREAM_MUSIC) == true) }
    fun adjust(direction: Int) {
        val a = audio ?: return
        runCatching { a.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
        level = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        muted = a.isStreamMute(AudioManager.STREAM_MUSIC)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(PanoIcons.Minus, "Sesi kıs", { adjust(AudioManager.ADJUST_LOWER) }, Color.Transparent, c.secondary, size = 72.dp, iconSize = 26.dp)
        Box(
            Modifier.size(72.dp).clip(CircleShape)
                .clickable(role = Role.Button) { adjust(AudioManager.ADJUST_TOGGLE_MUTE) }
                .semantics { contentDescription = if (muted) "Sesi aç" else "Sessize al" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (muted || level == 0) PanoIcons.SpeakerOff else PanoIcons.Speaker, contentDescription = null,
                tint = if (muted) Accent.Red else c.text, modifier = Modifier.size(30.dp),
            )
        }
        RoundIconButton(PanoIcons.Plus, "Sesi aç", { adjust(AudioManager.ADJUST_RAISE) }, Color.Transparent, c.secondary, size = 72.dp, iconSize = 26.dp)
    }
}
