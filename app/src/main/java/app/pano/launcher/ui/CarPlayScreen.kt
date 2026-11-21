package app.pano.launcher.ui

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import app.pano.launcher.PanelHost
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import app.pano.launcher.LaunchAnchor
import app.pano.launcher.settings.FavoritesStore
import app.pano.launcher.Screen
import app.pano.launcher.media.MediaHub
import app.pano.launcher.navigation.NavHub
import app.pano.launcher.navigation.NavInstruction
import app.pano.launcher.weather.Weather
import app.pano.launcher.weather.WeatherHub
import app.pano.launcher.weather.WeatherIcon
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano
import app.pano.launcher.ui.theme.linkColor
import app.pano.launcher.vehicle.ClimateState
import app.pano.launcher.vehicle.Gear
import app.pano.launcher.vehicle.SpeedSource
import app.pano.launcher.vehicle.VehicleHub
import app.pano.launcher.vehicle.decimal
import app.pano.launcher.vehicle.shortTemp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * CarPlay / iOS havasında ikinci arayüz (Ayarlar > Geliştirici > Stil > Arayüz).
 * Renkli, yumuşak bir duvar kağıdı üzerinde yarı saydam "cam" paneller; solda CarPlay'deki gibi
 * dikey uygulama çubuğu, altta klima şeridi. Klasik Pano düzenine dokunmaz.
 */

private val tr: Locale = Locale.forLanguageTag("tr-TR")

/** Cam arayüzün renkleri. Duvar kağıdı renkli olduğu için Pano'nun düz renklerinden ayrı tutuluyor. */
private data class GlassColors(
    val dark: Boolean,
    val text: Color,
    val secondary: Color,
    val tertiary: Color,
    val fill: Color,        // cam panel zemini
    val sheen: Color,       // panelin üst kısmındaki hafif parlama
    val edgeTop: Color,     // ışık alan üst kenar
    val edgeBottom: Color,
    val chip: Color,        // panel içindeki buton zemini
    val overMap: Color,     // harita üstündeki paneller (daha opak, okunurluk için)
)

private val DarkGlass = GlassColors(
    dark = true,
    text = Color.White,
    secondary = Color.White.copy(alpha = 0.62f),
    tertiary = Color.White.copy(alpha = 0.36f),
    fill = Color.White.copy(alpha = 0.075f),
    sheen = Color.White.copy(alpha = 0.07f),
    edgeTop = Color.White.copy(alpha = 0.30f),
    edgeBottom = Color.White.copy(alpha = 0.05f),
    chip = Color.White.copy(alpha = 0.12f),
    overMap = Color(0xC2141418),
)

private val LightGlass = GlassColors(
    dark = false,
    text = Color(0xFF0B0B0C),
    secondary = Color.Black.copy(alpha = 0.55f),
    tertiary = Color.Black.copy(alpha = 0.30f),
    fill = Color.White.copy(alpha = 0.58f),
    sheen = Color.White.copy(alpha = 0.45f),
    edgeTop = Color.White.copy(alpha = 0.95f),
    edgeBottom = Color.White.copy(alpha = 0.30f),
    chip = Color.Black.copy(alpha = 0.06f),
    overMap = Color(0xE6FFFFFF),
)

@Composable
private fun glass(): GlassColors = if (LocalPano.current.isDark) DarkGlass else LightGlass

/** Liquid Glass benzeri panel: yarı saydam zemin, üstte parlama, ışık alan ince kenar. */
private fun Modifier.glass(c: GlassColors, shape: Shape = RoundedCornerShape(32.dp)): Modifier =
    this.clip(shape)
        .background(c.fill)
        .background(Brush.verticalGradient(listOf(c.sheen, Color.Transparent)))
        .border(1.dp, Brush.verticalGradient(listOf(c.edgeTop, c.edgeBottom)), shape)

// ───────────────────────── Animasyon ─────────────────────────

/** Animasyonlar sürüşte dikkat dağıtmasın diye kısa tutulur. */
private const val MotionMs = 260

/**
 * Basınca hafifçe küçülen dokunma hissi (iOS / CarPlay gibi). Dokunmayı yalnızca izler, tüketmez;
 * bu yüzden içindeki herhangi bir tıklanabilir öğeyle (RoundIconButton dahil) birlikte çalışır.
 * Zincirin başına (boyut ve kırpmadan önce) koy ki bütün karo birlikte küçülsün.
 */
private fun Modifier.pressScale(pressed: Float = 0.95f): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (down) pressed else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "basma",
    )
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                down = true
                waitForUpOrCancellation()
                down = false
            }
        }
}

/** Ekranların sırası; geçişte yön buna göre seçilir (çubukta aşağıdaki ekran aşağıdan gelir). */
private fun Screen.order(): Int = when (this) {
    Screen.Home -> 0
    Screen.Climate -> 1
    Screen.Apps -> 2
    Screen.Settings -> 3
}

private class Blob(val brush: Brush, val center: Offset, val radius: Float)

private fun blob(color: Color, alpha: Float, center: Offset, radius: Float) =
    Blob(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, radius), center, radius)

/** Duvar kağıdının fırçaları: bir zemin geçişi ve üstüne yumuşak renk lekeleri. */
private fun wallpaper(dark: Boolean, w: Float, h: Float): Pair<Brush, List<Blob>> =
    if (dark) {
        Brush.linearGradient(listOf(Color(0xFF060918), Color(0xFF110E33), Color(0xFF240C3E)), Offset.Zero, Offset(w, h)) to listOf(
            blob(Color(0xFF2F6BFF), 0.42f, Offset(w * 0.18f, h * 0.05f), w * 0.48f),
            blob(Color(0xFFB44CFF), 0.34f, Offset(w * 0.88f, h * 0.98f), w * 0.46f),
            blob(Color(0xFF00C2D1), 0.16f, Offset(w * 0.52f, h * 1.08f), w * 0.36f),
        )
    } else {
        Brush.linearGradient(listOf(Color(0xFFE6ECFB), Color(0xFFEFEAFA), Color(0xFFF8ECF3)), Offset.Zero, Offset(w, h)) to listOf(
            blob(Color(0xFF7AA7FF), 0.38f, Offset(w * 0.16f, h * 0.04f), w * 0.46f),
            blob(Color(0xFFD59BFF), 0.32f, Offset(w * 0.9f, h * 0.96f), w * 0.44f),
            blob(Color(0xFF7FE3E0), 0.24f, Offset(w * 0.5f, h * 1.1f), w * 0.34f),
        )
    }

@Composable
private fun rememberClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1_000)
    }
}

/** CarPlay düzeninin kökü: MainActivity, ayarlarda bu düzen seçiliyse PanoApp yerine bunu çizer. */
@Composable
fun CarPlayApp(screen: Screen, climate: ClimateState, onNavigate: (Screen) -> Unit) {
    val dark = LocalPano.current.isDark
    // drawWithCache: duvar kağıdı yalnızca boyut ya da tema değişince yeniden hesaplanır, her karede değil.
    Box(
        Modifier.fillMaxSize()
            .drawWithCache {
                val (base, blobs) = wallpaper(dark, size.width, size.height)
                onDrawBehind {
                    drawRect(base)
                    blobs.forEach { drawCircle(it.brush, it.radius, it.center) }
                }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        DesignCanvas {
            // Duvar kağıdı sabit kalır; başka uygulamadan dönünce yalnızca içerik yakınlaşarak gelir.
            Row(Modifier.fillMaxSize().returnZoom().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Rail(screen, onNavigate, Modifier.width(116.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    // Ekranlar arası kısa fade + hafif dikey kayma.
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val dir = if (targetState.order() > initialState.order()) 1 else -1
                            (fadeIn(tween(MotionMs)) + slideInVertically(tween(MotionMs)) { dir * it / 14 })
                                .togetherWith(fadeOut(tween(MotionMs / 2)) + slideOutVertically(tween(MotionMs)) { -dir * it / 14 })
                        },
                        label = "ekran",
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { target ->
                        Box(Modifier.fillMaxSize()) {
                            when (target) {
                                Screen.Home -> Dashboard(Modifier.fillMaxSize())
                                Screen.Climate -> ClimateScreen(climate) { onNavigate(Screen.Home) }
                                Screen.Apps -> DrivingGate("Uygulamalar", { onNavigate(Screen.Home) }) { AppsScreen { onNavigate(Screen.Home) } }
                                Screen.Settings -> DrivingGate("Ayarlar", { onNavigate(Screen.Home) }) { SettingsScreen { onNavigate(Screen.Home) } }
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = screen != Screen.Climate,
                        enter = fadeIn(tween(MotionMs)) + expandVertically(tween(MotionMs)),
                        exit = fadeOut(tween(MotionMs / 2)) + shrinkVertically(tween(MotionMs)),
                    ) {
                        ClimateStrip(climate, { onNavigate(Screen.Climate) }, Modifier.fillMaxWidth().height(92.dp))
                    }
                }
            }
        }
    }
}

// ───────────────────────── Sol uygulama çubuğu ─────────────────────────

@Composable
private fun Rail(current: Screen, onNavigate: (Screen) -> Unit, modifier: Modifier) {
    val c = glass()
    val context = LocalContext.current
    val now by rememberClock()
    val playing by MediaHub.state.collectAsState()
    val vehicle by VehicleHub.state.collectAsState()
    val driving by VehicleHub.isDriving.collectAsState()
    fun toggle(target: Screen) = onNavigate(if (current == target) Screen.Home else target)

    Column(
        modifier.glass(c, RoundedCornerShape(38.dp)).padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            SimpleDateFormat("HH:mm", tr).format(Date(now)),
            color = c.text, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp,
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SignalBars(c)
            Text(
                "%${vehicle.batteryPercent ?: "—"}", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        // Seçili ekranın arkasındaki cam vurgu tek bir kutu; seçim değişince yeni öğeye kayar.
        val activeIndex = when (current) {
            Screen.Climate -> 3
            Screen.Apps -> 4
            Screen.Settings -> 5
            Screen.Home -> -1
        }
        var lastIndex by remember { mutableIntStateOf(if (activeIndex >= 0) activeIndex else 3) }
        if (activeIndex >= 0) lastIndex = activeIndex
        val highlightY by animateDpAsState(
            (RailItemSize + RailItemGap) * lastIndex,
            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
            label = "vurgu",
        )
        val highlightAlpha by animateFloatAsState(if (activeIndex >= 0) 1f else 0f, tween(MotionMs / 2), label = "vurgu-görünürlük")
        Box {
            Box(
                Modifier.offset(y = highlightY).size(RailItemSize)
                    .graphicsLayer { alpha = highlightAlpha }
                    .clip(RoundedCornerShape(26.dp)).background(c.chip),
            )
            Column(verticalArrangement = Arrangement.spacedBy(RailItemGap), horizontalAlignment = Alignment.CenterHorizontally) {
                RailApp(PanoIcons.Navigation, "Haritalar", listOf(Color(0xFF5AC8FA), Color(0xFF007AFF))) { Launch.maps(context, it) }
                RailApp(PanoIcons.Music, "Müzik", listOf(Color(0xFFFF6482), Color(0xFFFF2D55))) { Launch.music(context, playing?.packageName, it) }
                RailApp(PanoIcons.Phone, "Telefon", listOf(Color(0xFF63E07F), Color(0xFF28C840))) { Launch.phone(context, it) }
                RailApp(PanoIcons.Fan, "Klima", listOf(Color(0xFFFFB340), Color(0xFFFF8A00))) { toggle(Screen.Climate) }
                // Sürüşte soluklaşır; dokununca sürüş uyarısı açılır.
                RailApp(PanoIcons.Apps, "Uygulamalar", listOf(Color(0xFF9A9AA0), Color(0xFF48484A)), dimmed = driving) { toggle(Screen.Apps) }
                RailApp(PanoIcons.Sliders, "Ayarlar", listOf(Color(0xFFAEAEB2), Color(0xFF636366)), dimmed = driving) { toggle(Screen.Settings) }
            }
        }
        Spacer(Modifier.weight(1f))
        // CarPlay'deki "Dashboard" düğmesi: her zaman ana panoya döner.
        val home = current == Screen.Home
        val homeBg by animateColorAsState(if (home) c.text else c.chip, tween(MotionMs), label = "pano-zemin")
        val homeTint by animateColorAsState(
            if (home) (if (c.dark) Color.Black else Color.White) else c.text, tween(MotionMs), label = "pano-ikon",
        )
        Box(
            Modifier.pressScale().size(68.dp).clip(CircleShape)
                .background(homeBg)
                .clickable(role = Role.Button) { onNavigate(Screen.Home) }
                .semantics { contentDescription = "Ana pano" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PanoIcons.Dashboard, contentDescription = null,
                tint = homeTint, modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun SignalBars(c: GlassColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(5.dp, 8.dp, 11.dp, 14.dp).forEach { h ->
            Box(Modifier.width(3.5.dp).height(h).clip(RoundedCornerShape(1.dp)).background(c.secondary))
        }
    }
}

private val RailItemSize = 88.dp
private val RailItemGap = 4.dp

/**
 * CarPlay uygulama ikonu: renk geçişli yumuşak kare. Etkin olanın vurgusunu Rail çizer (kayan kutu).
 * [onClick] ikonun ekrandaki yerini alır; başka uygulama açan öğeler onu oradan büyüterek açar.
 */
@Composable
private fun RailApp(icon: ImageVector, label: String, tint: List<Color>, dimmed: Boolean = false, onClick: (LaunchAnchor?) -> Unit) {
    val tile = RoundedCornerShape(18.dp)
    val fade by animateFloatAsState(if (dimmed) 0.35f else 1f, tween(MotionMs), label = "soluk")
    val source = rememberLaunchSource()
    Box(
        Modifier.pressScale().size(RailItemSize).launchSource(source).graphicsLayer { alpha = fade }.clip(RoundedCornerShape(26.dp))
            .clickable(role = Role.Button) { onClick(source.anchor()) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(64.dp)
                .shadow(10.dp, tile, ambientColor = tint.last(), spotColor = tint.last())
                .background(Brush.verticalGradient(tint), tile)
                .border(0.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.05f))), tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

// ───────────────────────── Ana pano ─────────────────────────

@Composable
private fun Dashboard(modifier: Modifier) {
    val driving by VehicleHub.isDriving.collectAsState()
    // 1 = park düzeni, 0 = sürüş düzeni. Ağırlık ve boşluklar birlikte küçülür; sıçrama olmaz.
    val parked by animateFloatAsState(if (driving) 0f else 1f, tween(MotionMs * 2), label = "sürüş-düzeni")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        // Harita sütunu: altta favori uygulamalar adası, sağdaki Tüketim kartıyla aynı hizada ve boyda.
        Column(Modifier.weight(1.25f).fillMaxHeight()) {
            MapWidget(Modifier.weight(1f).fillMaxWidth())
            if (parked > 0.001f) {
                Spacer(Modifier.height(18.dp * parked))
                FavoritesIsland(Modifier.height(IslandHeight * parked).fillMaxWidth().graphicsLayer { alpha = parked }.clipToBounds())
            }
        }
        VehicleWidget(Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.weight(0.9f).fillMaxHeight()) {
            if (parked > 0.001f) {
                ClockWidget(Modifier.weight(0.82f * parked).fillMaxWidth().graphicsLayer { alpha = parked }.clipToBounds())
                Spacer(Modifier.height(18.dp * parked))
            }
            NowPlayingWidget(Modifier.weight(1.3f).fillMaxWidth())
            if (parked > 0.001f) {
                Spacer(Modifier.height(18.dp * parked))
                ConsumptionWidget(Modifier.height(IslandHeight * parked).fillMaxWidth().graphicsLayer { alpha = parked }.clipToBounds())
            }
        }
    }
}

/** Alt sıradaki küçük adaların (favoriler, Tüketim) ortak yüksekliği. */
private val IslandHeight = 112.dp

/**
 * Sürüş sırasında dikkat dağıtan ekranların (uygulama ızgarası, uzun ayar listeleri) yerine
 * büyük bir uyarı ve ana panoya dönüş düğmesi gösterir. Araç durunca içerik geri gelir.
 */
@Composable
private fun DrivingGate(title: String, onHome: () -> Unit, content: @Composable () -> Unit) {
    val driving by VehicleHub.isDriving.collectAsState()
    AnimatedContent(
        targetState = driving,
        transitionSpec = {
            (fadeIn(tween(MotionMs)) + scaleIn(tween(MotionMs), initialScale = 0.96f))
                .togetherWith(fadeOut(tween(MotionMs / 2)) + scaleOut(tween(MotionMs), targetScale = 0.96f))
        },
        label = "sürüş-kilidi",
        modifier = Modifier.fillMaxSize(),
    ) { locked ->
        if (!locked) {
            Box(Modifier.fillMaxSize()) { content() }
        } else {
            val c = glass()
            Column(
                Modifier.fillMaxSize().glass(c).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(PanoIcons.Navigation, contentDescription = null, tint = c.secondary, modifier = Modifier.size(64.dp))
                Text(
                    "$title sürüş sırasında kapalı", color = c.text, fontSize = 36.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    "Araç durunca kendiliğinden açılır.", color = c.secondary, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
                )
                Box(
                    Modifier.padding(top = 36.dp).pressScale().height(72.dp).clip(RoundedCornerShape(36.dp))
                        .background(c.chip)
                        .clickable(role = Role.Button, onClick = onHome)
                        .padding(horizontal = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Ana panoya dön", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Haritanın altındaki cam ada: favori uygulamalar tek satırda, dokununca açılır. */
@Composable
private fun FavoritesIsland(modifier: Modifier) {
    val c = glass()
    val context = LocalContext.current
    FavoritesStore.load(context)
    val favorites by FavoritesStore.state

    Row(
        modifier.glass(c).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (favorites.isEmpty()) {
            Icon(PanoIcons.Star, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 6.dp).size(20.dp))
            Text(
                "Uygulamalar'da bir ikona uzun basarak favori ekle",
                color = c.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        } else {
            favorites.forEach { app ->
                key(app.packageName, app.className) {
                    val source = rememberLaunchSource()
                    AppIcon(
                        app.packageName, app.className, 64.dp,
                        Modifier.pressScale().launchSource(source).clip(RoundedCornerShape(23)).semantics { contentDescription = app.label }
                            .clickable(role = Role.Button) {
                                // Destekleniyorsa launcher'dan çıkmadan harita panelinde açılır.
                                if (!PanelHost.open(context, app.packageName, app.className)) {
                                    Launch.app(context, app.packageName, app.className, source.anchor())
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun MapWidget(modifier: Modifier) {
    val c = glass()
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasLocationPermission(context)
    }
    val shape = RoundedCornerShape(32.dp)
    val clearance = 14.dp
    val view = LocalView.current

    // Panelde açılan uygulamalar tam bu kartın ekrandaki yerine oturur.
    Box(modifier.onGloballyPositioned { coords ->
        val r = coords.boundsInWindow()
        val loc = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
        val dy = loc[1]
        PanelHost.bounds = android.graphics.Rect(
            r.left.toInt() + loc[0], r.top.toInt() + dy, r.right.toInt() + loc[0], r.bottom.toInt() + dy,
        )
    }.clip(shape).background(if (c.dark) Color(0xFF16181C) else Color(0xFFE8E8ED))) {
        LiveMap(Modifier.fillMaxSize(), dark = c.dark, showLocation = granted, bottomInset = 0f)
        // Navigasyon uygulaması dokunulan yerden (harita, manevra kartı ya da arama çubuğu) büyüyerek açılır.
        val mapSrc = rememberLaunchSource()
        val barSrc = rememberLaunchSource()
        Box(Modifier.fillMaxSize().launchSource(mapSrc).clickable(role = Role.Button) { Launch.maps(context, mapSrc.anchor()) })

        val nav by NavHub.state.collectAsState()
        val step = nav
        if (step != null) {
            // Navigasyon uygulaması rota veriyorsa: CarPlay'deki yeşil manevra kartı.
            ManeuverCard(step, Modifier.align(Alignment.TopStart).padding(16.dp).fillMaxWidth().launchSource(barSrc)) {
                Launch.maps(context, barSrc.anchor())
            }
        } else Row(
            Modifier.align(Alignment.TopStart).padding(16.dp).pressScale(0.97f).fillMaxWidth().height(60.dp).launchSource(barSrc)
                .clip(RoundedCornerShape(30.dp)).background(c.overMap)
                .clickable(role = Role.Button) { Launch.maps(context, barSrc.anchor()) }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PanoIcons.Search, contentDescription = null, tint = c.secondary, modifier = Modifier.size(22.dp))
            Text("Nereye gidiyoruz?", color = c.secondary, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp))
        }

        if (!granted) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = clearance).height(50.dp).clip(RoundedCornerShape(25.dp))
                    .background(c.overMap)
                    .clickable(role = Role.Button) {
                        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PanoIcons.Location, contentDescription = null, tint = linkColor(), modifier = Modifier.size(20.dp))
                Text("Konumumu göster", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            }
        }

        // OpenStreetMap lisansı gereği atıf görünür kalmalı.
        Text(
            "© OpenFreeMap © OpenMapTiles © OpenStreetMap",
            color = c.secondary, fontSize = 10.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = clearance)
                .clip(RoundedCornerShape(6.dp)).background(c.overMap).padding(horizontal = 6.dp, vertical = 2.dp),
        )

        // Cam çerçeve: haritayı diğer panellerle aynı ışıklı kenarla sarar.
        Box(Modifier.matchParentSize().border(1.dp, Brush.verticalGradient(listOf(c.edgeTop, c.edgeBottom)), shape))
    }
}

@Composable
private fun ManeuverCard(step: NavInstruction, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier.pressScale(0.97f).shadow(16.dp, shape).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2DBE60), Color(0xFF1E9E4C))))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = step.icon
            if (icon != null) {
                Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(52.dp))
            } else {
                Icon(PanoIcons.Navigation, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                if (step.distance != null) {
                    Text(step.distance, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, lineHeight = 32.sp)
                }
                Text(
                    step.street ?: step.maneuver, color = Color.White.copy(alpha = 0.92f),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (step.eta != null) {
            Text(
                step.eta, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun VehicleWidget(modifier: Modifier) {
    val c = glass()
    val context = LocalContext.current
    val v by VehicleHub.state.collectAsState()
    val driving by VehicleHub.isDriving.collectAsState()

    Column(modifier.glass(c).padding(28.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    // Hız yoksa (BYD veri yolu da GPS de yok) "—": uydurma 0 gösterilmez.
                    Text(v.speedKmh?.toString() ?: "—", color = c.text, fontSize = 80.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp, lineHeight = 80.sp)
                    Text("km/s", color = c.secondary, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val status = when {
                        v.gear == Gear.R -> "GERİ"
                        driving -> "SÜRÜŞ"
                        v.gear == Gear.N -> "BOŞ"
                        else -> "PARK"
                    }
                    val statusColor by animateColorAsState(if (driving) linkColor() else Accent.Green, tween(MotionMs), label = "durum-rengi")
                    AnimatedContent(
                        targetState = status,
                        transitionSpec = {
                            (fadeIn(tween(MotionMs)) + slideInVertically(tween(MotionMs)) { it / 2 })
                                .togetherWith(fadeOut(tween(MotionMs / 2)) + slideOutVertically(tween(MotionMs)) { -it / 2 })
                        },
                        label = "durum",
                    ) { label ->
                        Text(label, color = statusColor, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                    if (v.speedSource == SpeedSource.Gps) {
                        Text(
                            "GPS", color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(8.dp)).background(c.chip)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    if (v.isPreview) {
                        Text(
                            "Önizleme", color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(8.dp)).background(c.chip)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("MENZİL", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(v.rangeKm?.toString() ?: "—", color = c.text, fontSize = 44.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.2).sp, lineHeight = 44.sp)
                    Text("km", color = c.secondary, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                }
                Box(Modifier.padding(top = 10.dp).width(150.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.chip)) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth((v.batteryPercent ?: 0) / 100f).clip(RoundedCornerShape(3.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF30D158), Color(0xFF63E6A0)))),
                    )
                }
                Text("%${v.batteryPercent ?: "—"}", color = c.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            }
        }

        CarIllustration(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp), dark = c.dark, background = Color.Transparent)

        // Araç düğmeleri sürüşte gerekmez; kapanınca araç çizimi büyür.
        AnimatedVisibility(
            visible = !driving,
            enter = fadeIn(tween(MotionMs)) + expandVertically(tween(MotionMs * 2)),
            exit = fadeOut(tween(MotionMs / 2)) + shrinkVertically(tween(MotionMs * 2)),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val notYet = { Launch.notConnectedYet(context) }
                val locked = v.locked
                GlassAction(
                    if (locked == false) PanoIcons.Unlock else PanoIcons.Lock,
                    when (locked) { true -> "Kilitli"; false -> "Açık"; null -> "Kilit" },
                    notYet,
                )
                GlassAction(PanoIcons.Trunk, "Bagaj", notYet)
                GlassAction(PanoIcons.Bolt, "Şarj", notYet)
                GlassAction(PanoIcons.Mirror, "Aynalar", notYet)
            }
        }
    }
}

@Composable
private fun GlassAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = glass()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.pressScale().size(66.dp).clip(CircleShape).background(c.chip)
                .border(1.dp, Brush.verticalGradient(listOf(c.edgeTop, c.edgeBottom)), CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(26.dp))
        }
        Text(label, color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
    }
}

private fun weatherIcon(w: Weather): ImageVector = when (w.icon) {
    WeatherIcon.Clear -> if (w.isDay) PanoIcons.Sun else PanoIcons.Moon
    WeatherIcon.Cloudy -> PanoIcons.Cloud
    WeatherIcon.Rain -> PanoIcons.Rain
    WeatherIcon.Snow -> PanoIcons.Snow
    WeatherIcon.Fog -> PanoIcons.Fog
    WeatherIcon.Storm -> PanoIcons.Storm
}

private fun weatherTint(w: Weather): Color = when (w.icon) {
    WeatherIcon.Clear -> if (w.isDay) Color(0xFFFFCC00) else Color(0xFFBFC8FF)
    WeatherIcon.Rain, WeatherIcon.Storm -> Color(0xFF64B5FF)
    WeatherIcon.Snow -> Color(0xFFE3F2FF)
    else -> Color(0xFFB8C2D0)
}

private fun deg(v: Double): String = "${Math.round(v)}°"

/**
 * iOS takvim + hava durumu widget'ı: solda kırmızı gün adı ve büyük gün numarası,
 * sağda anlık sıcaklık, açıklama ve günün en yüksek / en düşük değeri.
 * Hava durumu henüz gelmediyse sağda saat gösterilir.
 */
@Composable
private fun ClockWidget(modifier: Modifier) {
    val c = glass()
    val now by rememberClock()
    val weather by WeatherHub.state.collectAsState()
    val date = Date(now)
    val w = weather
    if (w != null) {
        Row(modifier.glass(c).padding(horizontal = 26.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    SimpleDateFormat("EEEE", tr).format(date).uppercase(tr),
                    color = Accent.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                )
                Text(SimpleDateFormat("d", tr).format(date), color = c.text, fontSize = 60.sp, fontWeight = FontWeight.Light, lineHeight = 62.sp)
                Text(SimpleDateFormat("MMMM", tr).format(date), color = c.secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(weatherIcon(w), contentDescription = null, tint = weatherTint(w), modifier = Modifier.size(38.dp))
                    Text(
                        deg(w.temperatureC), color = c.text, fontSize = 52.sp, fontWeight = FontWeight.Light,
                        letterSpacing = (-1.5).sp, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(w.description, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Y:${deg(w.highC)}  D:${deg(w.lowC)}",
                    color = c.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        return
    }
    Row(modifier.glass(c).padding(horizontal = 26.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                SimpleDateFormat("EEEE", tr).format(date).uppercase(tr),
                color = Accent.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            )
            Text(SimpleDateFormat("d", tr).format(date), color = c.text, fontSize = 60.sp, fontWeight = FontWeight.Light, lineHeight = 62.sp)
            Text(SimpleDateFormat("MMMM", tr).format(date), color = c.secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Text(
            SimpleDateFormat("HH:mm", tr).format(date),
            color = c.text, fontSize = 54.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp,
        )
    }
}

@Composable
private fun NowPlayingWidget(modifier: Modifier) {
    val c = glass()
    val context = LocalContext.current
    val playing by MediaHub.state.collectAsState()
    val now by rememberClock()
    val driving by VehicleHub.isDriving.collectAsState()
    val p = playing

    Column(modifier.glass(c).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val artSrc = rememberLaunchSource()
            Box(
                Modifier.pressScale().size(96.dp).launchSource(artSrc).shadow(12.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFF6482), Color(0xFFBF5AF2))))
                    .clickable(role = Role.Button) { Launch.music(context, p?.packageName, artSrc.anchor()) },
                contentAlignment = Alignment.Center,
            ) {
                val art = p?.art
                if (art != null) {
                    Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(PanoIcons.Music, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Text(
                    if (p?.isPlaying == true) "ŞİMDİ ÇALIYOR" else "MÜZİK",
                    color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                )
                Text(
                    p?.title ?: "Müzik çalmıyor", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
                if (p != null) {
                    Text(p.artist, color = c.secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (!driving && !MediaHub.hasAccess(context)) {
                    Text(
                        "Bildirim erişimi ver", color = linkColor(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { Launch.notificationAccess(context) },
                    )
                } else {
                    Text("Kapağa dokunup uygulamayı aç", color = c.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        if (p != null) {
            val position = now.let {
                if (p.isPlaying && p.positionUpdatedAt > 0) {
                    p.positionMs + ((SystemClock.elapsedRealtime() - p.positionUpdatedAt) * p.speed).toLong()
                } else p.positionMs
            }
            val progress = if (p.durationMs > 0) (position.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.chip)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(RoundedCornerShape(3.dp)).background(c.text))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(PanoIcons.Previous, "Önceki", { MediaHub.previous() }, Color.Transparent, c.text, size = 56.dp, iconSize = 28.dp)
                RoundIconButton(
                    if (p.isPlaying) PanoIcons.Pause else PanoIcons.Play,
                    if (p.isPlaying) "Duraklat" else "Oynat",
                    { MediaHub.playPause() }, c.chip, c.text, size = 64.dp, iconSize = 30.dp,
                )
                RoundIconButton(PanoIcons.Next, "Sonraki", { MediaHub.next() }, Color.Transparent, c.text, size = 56.dp, iconSize = 28.dp)
            }
        }
    }
}

@Composable
private fun ConsumptionWidget(modifier: Modifier) {
    val c = glass()
    val v by VehicleHub.state.collectAsState()
    Row(modifier.glass(c).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF63E07F), Color(0xFF28C840)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PanoIcons.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text("Tüketim", color = c.secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 14.dp).weight(1f))
        Text(v.consumptionKwh100?.let(::decimal) ?: "—", color = c.text, fontSize = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp)
        Text("kWh/100", color = c.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
    }
}

// ───────────────────────── Alt klima şeridi ─────────────────────────

@Composable
private fun ClimateStrip(climate: ClimateState, onOpen: () -> Unit, modifier: Modifier) {
    val c = glass()
    Row(modifier.glass(c, RoundedCornerShape(30.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        // Tek bölgeli klimada tek, geniş bir sürgü; çift bölgede sürücü ve yolcu ayrı.
        TempSlider(
            climate.driver, climate, if (climate.dualZone) "Sürücü" else "Sıcaklık",
            Modifier.width(if (climate.dualZone) 300.dp else 420.dp),
        ) { climate.setDriverTemp(it) }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassPill("Otomatik", climate.auto) { climate.auto = !climate.auto }
            GlassPill("A/C", climate.ac) { climate.ac = !climate.ac }
            Row(
                Modifier.height(58.dp).clip(RoundedCornerShape(29.dp)).background(c.chip),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(PanoIcons.Minus, "Fanı azalt", { climate.setFanLevel(climate.fan - 1) }, Color.Transparent, c.text, size = 58.dp, iconSize = 20.dp)
                Icon(PanoIcons.Fan, contentDescription = null, tint = c.text, modifier = Modifier.size(24.dp))
                Text(
                    if (climate.isOff) "Kapalı" else "${climate.fan}",
                    color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.width(64.dp),
                )
                RoundIconButton(PanoIcons.Plus, "Fanı artır", { climate.setFanLevel(climate.fan + 1) }, Color.Transparent, c.text, size = 58.dp, iconSize = 20.dp)
            }
            Box(Modifier.pressScale()) { RoundIconButton(PanoIcons.ChevronUp, "Klima ayrıntıları", onOpen, c.chip, c.text, size = 58.dp, iconSize = 22.dp) }
        }
        if (climate.dualZone) {
            TempSlider(climate.passenger, climate, "Yolcu", Modifier.width(300.dp)) { climate.setPassengerTemp(it) }
        }
    }
}

/**
 * Parmakla kaydırılan sıcaklık sürgüsü: soldan sağa soğuktan sıcağa. Dokunulan yere atlar,
 * sürüklerken adım adım değişir (araçta 1°). Klima kapalıyken de son sıcaklık soluk gösterilir.
 */
@Composable
private fun TempSlider(value: Float, climate: ClimateState, label: String, modifier: Modifier, onChange: (Float) -> Unit) {
    val c = glass()
    val min = climate.minTemp
    val max = climate.maxTemp
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val shown by animateFloatAsState(fraction, tween(120), label = "sürgü")
    val shape = RoundedCornerShape(29.dp)

    fun valueAt(x: Float, width: Int): Float = min + (x / width).coerceIn(0f, 1f) * (max - min)

    Box(
        modifier.height(58.dp).clip(shape).background(c.chip)
            .pointerInput(min, max) {
                detectTapGestures { onChange(valueAt(it.x, size.width)) }
            }
            .pointerInput(min, max) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onChange(valueAt(change.position.x, size.width))
                }
            }
            .semantics { contentDescription = "$label sıcaklığı ${shortTemp(value)}" },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Dolgu: mavi (soğuk) → turuncu (sıcak); kapalıyken soluk.
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(shown.coerceAtLeast(0.08f)).clip(shape)
                .graphicsLayer { alpha = if (climate.isOff) 0.35f else 1f }
                .background(Brush.horizontalGradient(listOf(Color(0xFF4AA8FF), Color(0xFFFFB340), Color(0xFFFF6A3D)), startX = 0f)),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.text.copy(alpha = 0.75f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                tempLabel(value, climate),
                color = c.text.copy(alpha = if (climate.isOff) 0.5f else 1f),
                fontSize = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.8).sp,
            )
        }
    }
}

/** Araçtaki gibi: aralığın üst ucu "Hi" (BYD 33 °C'yi böyle gösteriyor). */
private fun tempLabel(t: Float, climate: ClimateState): String = when {
    climate.linked && t >= climate.maxTemp -> "Hi"
    else -> shortTemp(t)
}

@Composable
private fun GlassPill(text: String, on: Boolean, onClick: () -> Unit) {
    val c = glass()
    Box(
        Modifier.pressScale().height(58.dp).clip(RoundedCornerShape(29.dp))
            .background(if (on) linkColor() else c.chip)
            .clickable(role = Role.Switch, onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (on) Color.White else c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
