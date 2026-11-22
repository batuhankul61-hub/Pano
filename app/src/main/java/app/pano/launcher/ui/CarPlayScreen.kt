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
import app.pano.launcher.settings.DockStore
import app.pano.launcher.settings.LocalStyle
import app.pano.launcher.navigation.NavPhase
import app.pano.launcher.navigation.NavSession
import app.pano.launcher.navigation.PanoNav
import app.pano.launcher.navigation.RouteEngine
import app.pano.launcher.navigation.formatDistance
import app.pano.launcher.navigation.formatDuration
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.focusRequester
import app.pano.launcher.settings.DockControl
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.snap
import app.pano.launcher.RecentApps
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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

/**
 * Sürüş kısıtlaması uygulanacak mı: araç hareket ediyor **ve** ayarlarda "Sürüşte kısıtla"
 * açık. Kapalıyken (geliştirme varsayılanı) sürüşte hiçbir kart gizlenmez, ekran kilitlenmez.
 * "SÜRÜŞ" durum etiketi bundan bağımsız olarak gerçek durumu gösterir.
 */
@Composable
private fun drivingLocked(): Boolean {
    val driving by VehicleHub.isDriving.collectAsState()
    return driving && LocalStyle.current.drivingLock
}

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
      BoxWithConstraints(Modifier.fillMaxSize()) {
        // Bölünmüş ekranda (sağda başka uygulama) Pano dar kalır: sade düzen.
        if (maxWidth < maxHeight * 1.3f) {
            CompactCarPlay(screen, climate, onNavigate)
            return@BoxWithConstraints
        }
        DesignCanvas {
            // Duvar kağıdı sabit kalır; başka uygulamadan dönünce yalnızca içerik yakınlaşarak gelir.
            Row(Modifier.fillMaxSize().returnZoom().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Rail(screen, onNavigate, Modifier.width(116.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                  Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Ekranlar arası kısa fade + hafif dikey kayma.
                    // Uygulamalar ve Ayarlar sağ ekranda açılır; arkada ana pano kalır.
                    AnimatedContent(
                        targetState = if (screen == Screen.Apps || screen == Screen.Settings) Screen.Home else screen,
                        transitionSpec = {
                            val dir = if (targetState.order() > initialState.order()) 1 else -1
                            (fadeIn(tween(MotionMs)) + slideInVertically(tween(MotionMs)) { dir * it / 14 })
                                .togetherWith(fadeOut(tween(MotionMs / 2)) + slideOutVertically(tween(MotionMs)) { -dir * it / 14 })
                        },
                        label = "ekran",
                        modifier = Modifier.fillMaxSize(),
                    ) { target ->
                        Box(Modifier.fillMaxSize()) {
                            when (target) {
                                Screen.Home -> Dashboard(Modifier.fillMaxSize())
                                Screen.Climate -> ClimateScreen(climate) { onNavigate(Screen.Home) }
                                // Sağ ekranda açılırlar (RightScreenHost).
                                Screen.Apps, Screen.Settings -> Dashboard(Modifier.fillMaxSize())
                            }
                        }
                    }
                    // Tesla tarzı sağ ekran: harita ve uygulama çubuğu açık kalır.
                    RightScreenHost(screen, onNavigate, Modifier.fillMaxSize())
                  }
                    AnimatedVisibility(
                        visible = screen != Screen.Climate,
                        enter = fadeIn(tween(MotionMs)) + expandVertically(tween(MotionMs)),
                        exit = fadeOut(tween(MotionMs / 2)) + shrinkVertically(tween(MotionMs)),
                    ) {
                        ClimateStrip(climate, onNavigate, Modifier.fillMaxWidth().height(92.dp))
                    }
                }
            }
            // Dock düzenleme paneli: şeridin üstünde, arkası karartılmış; boşluğa dokununca kapanır.
            AnimatedVisibility(
                visible = DockEditing.open,
                enter = fadeIn(tween(MotionMs)),
                exit = fadeOut(tween(MotionMs)),
            ) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                        .pointerInput(Unit) { detectTapGestures { DockEditing.open = false } },
                ) {
                    DockEditor(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp).width(1280.dp)
                            .pointerInput(Unit) { detectTapGestures { } },
                    )
                }
            }
        }
      }
    }
}

/**
 * Bölünmüş ekranda Pano'nun sol yarısı: sol çubuk, harita (navigasyon kartıyla) ve alt şerit.
 * Sağ yarıda açılan uygulama (Spotify vb.) çalışırken ikisi aynı anda kullanılır.
 * "Tam ekran" bölünmüş ekrandan çıkar (Erişilebilirlik'te Pano hizmeti açık olmalı).
 */
@Composable
private fun CompactCarPlay(screen: Screen, climate: ClimateState, onNavigate: (Screen) -> Unit) {
    val c = glass()
    val context = LocalContext.current
    DesignCanvas(designWidth = 760f, designHeight = 900f) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Rail(screen, onNavigate, Modifier.width(108.dp).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MapWidget(Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ClimateStrip(climate, onNavigate, Modifier.weight(1f).fillMaxHeight())
                    Box(
                        Modifier.pressScale().size(92.dp).glass(c, RoundedCornerShape(30.dp))
                            .clickable(role = Role.Button) { app.pano.launcher.system.SystemActions.splitScreen(context) }
                            .semantics { contentDescription = "Tam ekran" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(PanoIcons.Split, contentDescription = null, tint = c.text, modifier = Modifier.size(26.dp))
                            Text("Tam ekran", color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        }
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
    val driving = drivingLocked()
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
    val driving = drivingLocked()
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
                ClockWidget(Modifier.weight(0.56f * parked).fillMaxWidth().graphicsLayer { alpha = parked }.clipToBounds())
                Spacer(Modifier.height(18.dp * parked))
                TyreBatteryWidget(Modifier.weight(0.74f * parked).fillMaxWidth().graphicsLayer { alpha = parked }.clipToBounds())
                Spacer(Modifier.height(18.dp * parked))
            }
            NowPlayingWidget(Modifier.weight(1.2f).fillMaxWidth())
            if (parked > 0.001f) {
                Spacer(Modifier.height(18.dp * parked))
                ConsumptionWidget(Modifier.height(IslandHeight * parked).fillMaxWidth().graphicsLayer { alpha = parked }.clipToBounds())
            }
        }
    }
}

/** Alt sıradaki küçük adaların (favoriler, Tüketim) ortak yüksekliği. */
private val IslandHeight = 112.dp

/** Sağ ekranda ne açık: bir web uygulaması ya da Pano'nun kendi ekranı (Uygulamalar, Ayarlar). */
private sealed interface RightContent {
    data class Web(val page: AppSheet.Page) : RightContent
    data class Native(val screen: Screen) : RightContent
    data object Music : RightContent
    data object Nav : RightContent
}

/**
 * Tesla tarzı sağ ekran: harita ve uygulama çubuğu yerinde kalır, sağ taraf (araç kartından
 * itibaren) yeni ekranla değişir. Yüzen pencere gibi değil, panonun bir parçası gibi durur:
 * diğer kartlarla aynı yüzey ve köşeler, gölge yok. Sağdan kayarak gelir; sol kenarından ya da
 * başlığından sağa sürükleyince birlikte kayar, üçte birden fazla çekilirse kapanır.
 * Araç hareket halindeyken web içeriği (video) kilitlenir.
 */
@Composable
private fun RightScreenHost(screen: Screen, onNavigate: (Screen) -> Unit, modifier: Modifier) {
    val c = glass()
    val page = AppSheet.page
    val native = screen == Screen.Apps || screen == Screen.Settings

    // Aynı anda tek içerik: yerel ekran seçilince web kapanır, web açılınca yerel ekran kapanır.
    LaunchedEffect(screen) { if (native) AppSheet.close() }
    val music = AppSheet.music
    val navPanel = AppSheet.nav
    LaunchedEffect(page, music, navPanel) { if ((page != null || music || navPanel) && native) onNavigate(Screen.Home) }

    val target: RightContent? = when {
        native -> RightContent.Native(screen)
        page != null -> RightContent.Web(page)
        music -> RightContent.Music
        navPanel -> RightContent.Nav
        else -> null
    }
    fun close() = if (native) onNavigate(Screen.Home) else AppSheet.close()

    // Kapanış animasyonu bitene kadar içerik ekranda kalsın.
    var shown by remember { mutableStateOf<RightContent?>(null) }
    if (target != null) shown = target
    // 1 = tamamen dışarıda (sağda), 0 = açık.
    val hidden = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val driving = drivingLocked()
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }

    LaunchedEffect(target != null) {
        if (target != null) {
            hidden.animateTo(0f, spring(dampingRatio = 0.92f, stiffness = Spring.StiffnessMediumLow))
        } else {
            hidden.animateTo(1f, tween(MotionMs))
            shown = null
        }
    }

    val content = shown ?: return
    BoxWithConstraints(modifier) {
        // Ana panodaki sütunlarla aynı hesap (harita 1,25 · araç 1 · sağ 0,9; aralık 18): sağ ekran
        // araç kartının sol kenarından başlar ve ızgaraya tam oturur.
        val unit = (maxWidth - 36.dp) / 3.15f
        val width = maxWidth - unit * 1.25f - 18.dp
        val widthPx = with(LocalDensity.current) { width.toPx() }
        val shape = RoundedCornerShape(32.dp)

        fun dragBy(dx: Float) = scope.launch { hidden.snapTo((hidden.value + dx / widthPx).coerceIn(0f, 1f)) }
        fun settle() = scope.launch {
            if (hidden.value > 0.3f) close()
            else hidden.animateTo(0f, spring(dampingRatio = 0.92f, stiffness = Spring.StiffnessMediumLow))
        }
        val dragToClose = Modifier.pointerInput(native) {
            detectHorizontalDragGestures(onDragEnd = { settle() }, onDragCancel = { settle() }) { change, dx ->
                change.consume()
                dragBy(dx)
            }
        }

        Box(
            Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight()
                .graphicsLayer { translationX = hidden.value * (widthPx + 60f) }
                .clip(shape)
                // Opak yüzey: arkadaki kartlar görünmesin, ama panodaki cam kartlarla aynı ton.
                .background(if (c.dark) Color(0xFF1A1C22) else Color(0xFFF3F4F7))
                .border(1.dp, Brush.verticalGradient(listOf(c.edgeTop, c.edgeBottom)), shape),
        ) {
            when (content) {
                is RightContent.Native -> Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp)) {
                    when (content.screen) {
                        Screen.Apps -> DrivingGate("Uygulamalar", { onNavigate(Screen.Home) }) { AppsScreen { onNavigate(Screen.Home) } }
                        else -> DrivingGate("Ayarlar", { onNavigate(Screen.Home) }) { SettingsScreen { onNavigate(Screen.Home) } }
                    }
                }
                RightContent.Music -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(68.dp).then(dragToClose).padding(start = 28.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Şimdi çalıyor", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        RoundIconButton(PanoIcons.Close, "Kapat", { close() }, Color.Transparent, c.secondary, size = 56.dp, iconSize = 22.dp)
                    }
                    NowPlayingScreen(Modifier.fillMaxSize().padding(start = 36.dp, end = 36.dp, bottom = 32.dp))
                }
                RightContent.Nav -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(68.dp).then(dragToClose).padding(start = 28.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Nereye?", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        RoundIconButton(PanoIcons.Close, "Kapat", { close() }, Color.Transparent, c.secondary, size = 56.dp, iconSize = 22.dp)
                    }
                    NavScreen(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 24.dp)) { close() }
                }
                is RightContent.Web -> Column(Modifier.fillMaxSize()) {
                    val p = content.page
                    Row(
                        Modifier.fillMaxWidth().height(68.dp).then(dragToClose).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundIconButton(PanoIcons.ChevronLeft, "Geri", { web?.let { if (it.canGoBack()) it.goBack() } }, Color.Transparent, c.text, size = 56.dp, iconSize = 24.dp)
                        Text(p.title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
                        RoundIconButton(PanoIcons.Close, "Kapat", { close() }, Color.Transparent, c.secondary, size = 56.dp, iconSize = 22.dp)
                    }
                    Box(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, bottom = 14.dp).clip(RoundedCornerShape(22.dp))) {
                        if (driving) {
                            Column(
                                Modifier.fillMaxSize().background(c.chip),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("${p.title} sürüş sırasında kapalı", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                                Text("Araç durunca kendiliğinden açılır.", color = c.secondary, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        } else {
                            SheetWebView(p.url, Modifier.fillMaxSize()) { web = it }
                        }
                    }
                }
            }
            // Sol kenar: görünmez sürükleme şeridi (yerel ekranlarda da kapatmak için).
            Box(Modifier.align(Alignment.CenterStart).width(24.dp).fillMaxHeight().then(dragToClose))
        }
    }
}

/**
 * Sürüş sırasında dikkat dağıtan ekranların (uygulama ızgarası, uzun ayar listeleri) yerine
 * büyük bir uyarı ve ana panoya dönüş düğmesi gösterir. Araç durunca içerik geri gelir.
 */
@Composable
private fun DrivingGate(title: String, onHome: () -> Unit, content: @Composable () -> Unit) {
    val driving = drivingLocked()
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

/**
 * Haritanın altındaki cam ada: önce favoriler, ince bir çizgiden sonra son kullanılanlar.
 * Dokununca uygulama harita panelinde açılır (destekleniyorsa); basılı tutunca küçük bir menü
 * çıkar: tam ekran aç, favorilere ekle / çıkar.
 */
@Composable
private fun FavoritesIsland(modifier: Modifier) {
    val c = glass()
    val context = LocalContext.current
    FavoritesStore.load(context)
    val favorites by FavoritesStore.state
    val recentAll by RecentApps.state
    val recents = recentAll.filter { r -> favorites.none { it.packageName == r.packageName } }

    Row(
        modifier.glass(c).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Panelde (sağdan gelen) açılan web uygulaması; her zaman başta durur.
        SheetTile(AppSheet.YouTube, Color(0xFFFF0033))
        if (favorites.isNotEmpty() || recents.isNotEmpty()) {
            Box(Modifier.width(1.dp).height(44.dp).background(c.secondary.copy(alpha = 0.3f)))
        }
        if (favorites.isEmpty() && recents.isEmpty()) {
            Text(
                "Uygulamalar'da bir ikona uzun basarak favori ekle",
                color = c.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        } else {
            favorites.forEach { app -> key("f", app.packageName, app.className) { IslandApp(app, favorite = true) } }
            if (favorites.isNotEmpty() && recents.isNotEmpty()) {
                Box(Modifier.width(1.dp).height(44.dp).background(c.secondary.copy(alpha = 0.3f)))
            }
            recents.forEach { app -> key("r", app.packageName, app.className) { IslandApp(app, favorite = false) } }
        }
    }
}

/** Sağdan gelen panelde açılan web uygulaması kutucuğu (uygulama simgesi boyutunda). */
@Composable
private fun SheetTile(page: AppSheet.Page, tint: Color) {
    val open = AppSheet.page == page
    Box(
        Modifier.pressScale().size(64.dp).clip(RoundedCornerShape(23)).background(tint)
            .clickable(role = Role.Button) { if (open) AppSheet.close() else AppSheet.open(page.title, page.url) }
            .semantics { contentDescription = page.title },
        contentAlignment = Alignment.Center,
    ) {
        Icon(PanoIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IslandApp(app: app.pano.launcher.settings.FavoriteApp, favorite: Boolean) {
    val c = glass()
    val context = LocalContext.current
    val source = rememberLaunchSource()
    var menu by remember { mutableStateOf(false) }
    Box {
        AppIcon(
            app.packageName, app.className, 64.dp,
            Modifier.pressScale().launchSource(source).clip(RoundedCornerShape(23)).semantics { contentDescription = app.label }
                .combinedClickable(
                    role = Role.Button,
                    onLongClick = { menu = true },
                ) { Launch.app(context, app.packageName, app.className, source.anchor()) },
        )
        if (menu) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.BottomStart,
                offset = androidx.compose.ui.unit.IntOffset(0, -90),
                onDismissRequest = { menu = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(240.dp).shadow(18.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                        .background(if (c.dark) Color(0xF2232326) else Color(0xF7FFFFFF)).padding(8.dp),
                ) {
                    Text(app.label, color = c.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    IslandMenuItem(if (favorite) "Favorilerden çıkar" else "Favorilere ekle") {
                        menu = false
                        val r = FavoritesStore.toggle(app)
                        if (r == FavoritesStore.Result.Full) {
                            android.widget.Toast.makeText(context, "Favoriler dolu", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IslandMenuItem(text: String, onClick: () -> Unit) {
    val c = glass()
    Text(
        text, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 16.dp),
    )
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

    Box(modifier.clip(shape).background(if (c.dark) Color(0xFF16181C) else Color(0xFFE8E8ED))) {
        LiveMap(Modifier.fillMaxSize(), dark = c.dark, showLocation = granted, bottomInset = 0f)
        // Navigasyon uygulaması dokunulan yerden (harita, manevra kartı ya da arama çubuğu) büyüyerek açılır.
        val mapSrc = rememberLaunchSource()
        val barSrc = rememberLaunchSource()
        LaunchedEffect(Unit) { PanoNav.init(context) }
        val own by PanoNav.state.collectAsState()
        val session = own?.takeIf { it.phase != NavPhase.Preview && it.route != null }
        // Pano navigasyonu sürerken haritaya dokunmak Nereye? ekranını açar; yoksa navigasyon uygulamasını.
        Box(
            Modifier.fillMaxSize().launchSource(mapSrc).clickable(role = Role.Button) {
                if (session != null) AppSheet.openNav() else Launch.maps(context, mapSrc.anchor())
            },
        )

        val nav by NavHub.state.collectAsState()
        val step = nav
        if (session != null) {
            // Pano'nun kendi yönlendirmesi: sıradaki manevra, altta varış ve "Bitir".
            val route = session.route!!
            val p = session.progress
            val m = route.maneuvers[(p?.nextIndex ?: 0).coerceIn(0, route.maneuvers.lastIndex)]
            val arrived = session.phase == NavPhase.Arrived
            ManeuverCard(
                distance = if (arrived) null else p?.let { formatDistance(it.distanceToNextM) },
                title = if (arrived) "Vardınız: ${session.destination.name}" else m.instruction,
                eta = if (session.rerouting) "Rota yeniden hesaplanıyor…" else null,
                vector = if (arrived) PanoIcons.Flag else maneuverIcon(m.type),
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp).fillMaxWidth(),
            ) { AppSheet.openNav() }
            NavBottomBar(session, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 40.dp).fillMaxWidth())
        } else if (step != null) {
            // Navigasyon uygulaması rota veriyorsa: CarPlay'deki yeşil manevra kartı.
            ManeuverCard(
                distance = step.distance, title = step.street ?: step.maneuver, eta = step.eta, bitmap = step.icon,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp).fillMaxWidth().launchSource(barSrc),
            ) { Launch.maps(context, barSrc.anchor()) }
        } else Row(
            Modifier.align(Alignment.TopStart).padding(16.dp).pressScale(0.97f).fillMaxWidth().height(60.dp)
                .clip(RoundedCornerShape(30.dp)).background(c.overMap)
                .clickable(role = Role.Button) { AppSheet.openNav() }
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
private fun ManeuverCard(
    distance: String?,
    title: String,
    eta: String?,
    modifier: Modifier,
    bitmap: android.graphics.Bitmap? = null,
    vector: ImageVector? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier.pressScale(0.97f).shadow(16.dp, shape).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2DBE60), Color(0xFF1E9E4C))))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(52.dp))
            } else {
                Icon(vector ?: PanoIcons.Navigation, contentDescription = null, tint = Color.White, modifier = Modifier.size(52.dp))
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                if (distance != null) {
                    Text(distance, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, lineHeight = 32.sp)
                }
                Text(
                    title, color = Color.White.copy(alpha = 0.92f),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (eta != null) {
            Text(
                eta, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Valhalla manevra türüne göre ok. */
private fun maneuverIcon(type: Int): ImageVector = when (type) {
    4, 5, 6 -> PanoIcons.Flag
    9, 18, 20, 23, 37 -> PanoIcons.SlightRight
    10, 11 -> PanoIcons.TurnRight
    12, 13 -> PanoIcons.UTurn
    14, 15 -> PanoIcons.TurnLeft
    16, 19, 21, 24, 38 -> PanoIcons.SlightLeft
    26, 27 -> PanoIcons.Roundabout
    else -> PanoIcons.Straight
}

/** "15:42" biçiminde varış saati. */
private fun arrivalClock(remainingS: Double): String =
    SimpleDateFormat("HH:mm", tr).format(Date(System.currentTimeMillis() + (remainingS * 1000).toLong()))

/** Yönlendirme sırasında haritanın altında: varış saati, kalan süre/mesafe ve "Bitir". */
@Composable
private fun NavBottomBar(session: NavSession, modifier: Modifier) {
    val c = glass()
    val route = session.route ?: return
    val remainingS = session.progress?.remainingS ?: route.timeS
    val remainingM = session.progress?.remainingM ?: route.lengthM
    Row(
        modifier.height(72.dp).clip(RoundedCornerShape(24.dp)).background(c.overMap).padding(start = 22.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(arrivalClock(remainingS), color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
            Text(
                "${formatDuration(remainingS)} · ${formatDistance(remainingM)}", color = c.secondary,
                fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
        }
        Box(
            Modifier.pressScale().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFFF453A))
                .clickable(role = Role.Button) { PanoNav.stop() }.padding(horizontal = 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Bitir", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Sağ ekrandaki "Nereye?": üstte arama kutusu; yazarken sonuçlar, boşken son gidilenler.
 * Bir yer seçilince rota önizlemesi (süre, mesafe, varış) ve "Başlat". Yönlendirme sürerken
 * hedef özeti, sıradaki manevralar ve "Navigasyonu bitir".
 */
@Composable
private fun NavScreen(modifier: Modifier, onClose: () -> Unit) {
    val c = glass()
    val context = LocalContext.current
    LaunchedEffect(Unit) { PanoNav.init(context) }
    val session by PanoNav.state.collectAsState()
    val recents by PanoNav.recents.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<app.pano.launcher.navigation.Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // Önizleme ya da yönlendirme varken arama kutusuna yazılırsa yeniden arama moduna geçilir.
    var forceSearch by remember { mutableStateOf(false) }
    val here = remember { PanoNav.bestLastKnown(context) ?: PanoNav.lastLocation }

    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(350) // yazarken her harfte istek atılmasın
        results = RouteEngine.search(query, here?.latitude, here?.longitude)
        searching = false
    }

    val s = session
    Column(modifier) {
        if (s == null || forceSearch || query.isNotEmpty()) {
            SearchField(query, onChange = { query = it; forceSearch = true }, autoFocus = s == null)
            val list = if (query.trim().length >= 2) results else recents
            Text(
                when {
                    query.trim().length >= 2 && searching -> "Aranıyor…"
                    query.trim().length >= 2 && results.isEmpty() -> "Sonuç bulunamadı"
                    query.trim().length >= 2 -> "Sonuçlar"
                    recents.isEmpty() -> "Adres, yer ya da işletme adı yaz"
                    else -> "Son gidilenler"
                },
                color = c.secondary, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp),
            )
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(list.size) { i ->
                    val place = list[i]
                    PlaceRow(place, here, recent = query.trim().length < 2) {
                        query = ""
                        forceSearch = false
                        PanoNav.preview(context, place)
                    }
                }
            }
        } else {
            RouteSummary(s, onSearch = { forceSearch = true }, onStarted = onClose)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, autoFocus: Boolean) {
    val c = glass()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp)).background(c.chip).padding(start = 22.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PanoIcons.Search, contentDescription = null, tint = c.secondary, modifier = Modifier.size(24.dp))
        Box(Modifier.weight(1f).padding(start = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Nereye gidiyoruz?", color = c.secondary, fontSize = 21.sp, fontWeight = FontWeight.Medium)
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 21.sp, fontWeight = FontWeight.Medium),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(linkColor()),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) {
            RoundIconButton(PanoIcons.Close, "Temizle", { onChange("") }, Color.Transparent, c.secondary, size = 56.dp, iconSize = 20.dp)
        }
    }
}

@Composable
private fun PlaceRow(place: app.pano.launcher.navigation.Place, here: android.location.Location?, recent: Boolean, onClick: () -> Unit) {
    val c = glass()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) {
            Icon(if (recent) PanoIcons.Clock else PanoIcons.Pin, contentDescription = null, tint = c.text, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(place.name, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (place.detail.isNotBlank()) {
                Text(place.detail, color = c.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (here != null) {
            val d = app.pano.launcher.navigation.distanceM(here.latitude, here.longitude, place.lat, place.lon)
            Text(formatDistance(d), color = c.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** Rota önizlemesi ya da süren yönlendirmenin özeti. */
@Composable
private fun RouteSummary(s: NavSession, onSearch: () -> Unit, onStarted: () -> Unit) {
    val c = glass()
    val context = LocalContext.current
    val route = s.route
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Text(s.destination.name, color = c.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp)
        if (s.destination.detail.isNotBlank()) {
            Text(s.destination.detail, color = c.secondary, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(22.dp))
        when {
            s.loading -> Text("Rota hesaplanıyor…", color = c.secondary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            s.error != null -> Text(s.error, color = Color(0xFFFF6961), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            route != null -> {
                val remainingS = s.progress?.remainingS ?: route.timeS
                val remainingM = s.progress?.remainingM ?: route.lengthM
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatDuration(remainingS), color = Accent.Green, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
                    Text(
                        "${formatDistance(remainingM)} · varış ${arrivalClock(remainingS)}", color = c.secondary, fontSize = 19.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 14.dp, bottom = 5.dp),
                    )
                }
                if (s.phase != NavPhase.Preview) {
                    // Sıradaki birkaç manevra.
                    val from = s.progress?.nextIndex ?: 0
                    Column(Modifier.padding(top = 16.dp)) {
                        route.maneuvers.drop(from).take(4).forEach { m ->
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(maneuverIcon(m.type), contentDescription = null, tint = c.text, modifier = Modifier.size(28.dp))
                                Text(m.instruction, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.phase == NavPhase.Preview) {
                NavButton("Başlat", Accent.Green, Color.White, Modifier.weight(1f), enabled = route != null) {
                    PanoNav.start(context)
                    onStarted()
                }
                if (s.error != null) {
                    NavButton("Tekrar dene", c.chip, c.text) { PanoNav.preview(context, s.destination) }
                }
            } else {
                NavButton("Navigasyonu bitir", Color(0xFFFF453A), Color.White, Modifier.weight(1f)) { PanoNav.stop() }
            }
            NavButton("Başka yer", c.chip, c.text, onClick = onSearch)
            NavButton("Haritalar'da aç", c.chip, c.text) {
                val d = s.destination
                Launch.intent(
                    context,
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:${d.lat},${d.lon}?q=${d.lat},${d.lon}(${android.net.Uri.encode(d.name)})")),
                )
            }
        }
        if (s.phase == NavPhase.Preview) {
            Text(
                "Vazgeç", color = c.secondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable { PanoNav.stop() }.padding(horizontal = 12.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun NavButton(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.pressScale().height(72.dp).clip(RoundedCornerShape(36.dp)).background(if (enabled) bg else bg.copy(alpha = 0.35f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
            visible = !drivingLocked(),
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
    // Lastik kartına yer açmak için tek satırlık sıkı düzen: solda tarih, sağda hava ya da saat.
    Row(modifier.glass(c).padding(horizontal = 26.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                SimpleDateFormat("EEEE", tr).format(date).uppercase(tr),
                color = Accent.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(SimpleDateFormat("d", tr).format(date), color = c.text, fontSize = 44.sp, fontWeight = FontWeight.Light, lineHeight = 46.sp)
                Text(
                    SimpleDateFormat("MMMM", tr).format(date), color = c.secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp, bottom = 7.dp),
                )
            }
        }
        if (w != null) {
            Icon(weatherIcon(w), contentDescription = null, tint = weatherTint(w), modifier = Modifier.size(34.dp))
            Text(
                deg(w.temperatureC), color = c.text, fontSize = 44.sp, fontWeight = FontWeight.Light,
                letterSpacing = (-1.5).sp, modifier = Modifier.padding(start = 8.dp),
            )
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp).widthIn(max = 120.dp)) {
                Text(w.description, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Y:${deg(w.highC)}  D:${deg(w.lowC)}", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        } else {
            Text(
                SimpleDateFormat("HH:mm", tr).format(date),
                color = c.text, fontSize = 46.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp,
            )
        }
    }
}

/**
 * Lastik basınçları (üstten araç şeması, köşelerde kPa) ve batarya. Veri yolundan gelmeyen
 * değer "—" kalır; BYD yokken lastik değeri hiç yoktur, batarya ise "Önizleme" etiketlidir.
 */
@Composable
private fun TyreBatteryWidget(modifier: Modifier) {
    val c = glass()
    val v by VehicleHub.state.collectAsState()
    val t = v.tyresKpa
    Row(modifier.glass(c).padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LASTİKLER", color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("kPa", color = c.secondary.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            }
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TyreValue(t?.frontLeft, c)
                    TyreValue(t?.rearLeft, c)
                }
                CarTopView(c, Modifier.padding(horizontal = 12.dp).width(34.dp).height(70.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TyreValue(t?.frontRight, c)
                    TyreValue(t?.rearRight, c)
                }
            }
        }
        Box(Modifier.padding(horizontal = 16.dp).width(1.dp).fillMaxHeight(0.75f).background(c.chip))
        Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("BATARYA", color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            BatteryGlyph(v.batteryPercent, c, Modifier.padding(top = 10.dp).width(58.dp).height(26.dp))
            Text(
                v.batteryPercent?.let { "%$it" } ?: "—", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.Medium,
                letterSpacing = (-0.5).sp, modifier = Modifier.padding(top = 6.dp),
            )
            if (v.isPreview) {
                Text("Önizleme", color = c.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TyreValue(kpa: Int?, c: GlassColors) {
    Text(kpa?.toString() ?: "—", color = if (kpa != null) c.text else c.secondary, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp)
}

/** Üstten bakışta sade araç: gövde çizgisi ve dört tekerlek. */
@Composable
private fun CarTopView(c: GlassColors, modifier: Modifier) {
    Box(modifier.drawWithCache {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx())
        val wheelW = size.width * 0.2f
        val wheelH = size.height * 0.2f
        val body = androidx.compose.ui.geometry.Size(size.width - wheelW, size.height)
        val r = androidx.compose.ui.geometry.CornerRadius(size.width * 0.35f)
        onDrawBehind {
            val wheel = androidx.compose.ui.geometry.Size(wheelW, wheelH)
            val wr = androidx.compose.ui.geometry.CornerRadius(wheelW / 2)
            for (y in listOf(size.height * 0.16f, size.height * 0.66f)) {
                drawRoundRect(c.secondary, Offset(0f, y), wheel, wr)
                drawRoundRect(c.secondary, Offset(size.width - wheelW, y), wheel, wr)
            }
            drawRoundRect(c.secondary, Offset(wheelW / 2, 0f), body, r, style = stroke)
            // Ön cam çizgisi: hangi ucun ön olduğu anlaşılsın.
            drawLine(c.secondary, Offset(wheelW, size.height * 0.3f), Offset(size.width - wheelW, size.height * 0.3f), 1.6.dp.toPx())
        }
    })
}

/** Batarya simgesi; doluluk yüzdeye göre (bilinmiyorsa boş). */
@Composable
private fun BatteryGlyph(percent: Int?, c: GlassColors, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                .border(1.6.dp, c.secondary, RoundedCornerShape(7.dp)).padding(3.dp),
        ) {
            val p = (percent ?: 0) / 100f
            val fill = if ((percent ?: 100) <= 20) listOf(Color(0xFFFF9F0A), Color(0xFFFF453A)) else listOf(Color(0xFF30D158), Color(0xFF63E6A0))
            Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(fill)))
        }
        Box(Modifier.padding(start = 2.dp).width(3.dp).height(10.dp).clip(RoundedCornerShape(1.5.dp)).background(c.secondary))
    }
}

@Composable
private fun NowPlayingWidget(modifier: Modifier) {
    val c = glass()
    val context = LocalContext.current
    val playing by MediaHub.state.collectAsState()
    val now by rememberClock()
    val driving = drivingLocked()
    val p = playing

    // Kartın boş yerine dokununca sağ ekranda büyük "Şimdi çalıyor" açılır; kapak ve düğmeler kendi işini yapar.
    Column(
        modifier.glass(c)
            .clickable(role = Role.Button, onClickLabel = "Şimdi çalıyor ekranını aç") { AppSheet.openMusic() }
            .padding(24.dp),
    ) {
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

/** "3:07" biçiminde süre. */
private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Sağ ekrandaki büyük müzik ekranı: solda kapak, sağda başlık / sanatçı / kaynak uygulama,
 * ilerleme çubuğu (uygulama destekliyorsa sürüklenebilir) ve büyük kontroller.
 * Kapağa dokununca kaynak uygulama tam ekran açılır.
 */
@Composable
private fun NowPlayingScreen(modifier: Modifier) {
    val c = glass()
    val context = LocalContext.current
    val playing by MediaHub.state.collectAsState()
    val now by rememberClock()
    val p = playing
    val appName = remember(p?.packageName) {
        p?.packageName?.let { pkg ->
            runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrNull()
        }
    }

    BoxWithConstraints(modifier) {
        val art = minOf(maxHeight, maxWidth * 0.46f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val artSrc = rememberLaunchSource()
            Box(
                Modifier.pressScale(0.97f).size(art).launchSource(artSrc).shadow(24.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFF6482), Color(0xFFBF5AF2))))
                    .clickable(role = Role.Button, onClickLabel = "Müzik uygulamasını aç") { Launch.music(context, p?.packageName, artSrc.anchor()) },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = p?.art
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(PanoIcons.Music, contentDescription = null, tint = Color.White, modifier = Modifier.size(art * 0.3f))
                }
            }

            Column(Modifier.weight(1f).padding(start = 40.dp)) {
                Text(
                    (appName ?: if (p?.isPlaying == true) "ŞİMDİ ÇALIYOR" else "MÜZİK").uppercase(tr),
                    color = c.secondary, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, maxLines = 1,
                )
                Text(
                    p?.title ?: "Müzik çalmıyor", color = c.text, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp,
                    letterSpacing = (-0.6).sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    p?.artist?.ifBlank { null } ?: if (p == null) "Kapağa dokunup müzik uygulamasını aç" else "",
                    color = c.secondary, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )

                if (p != null) {
                    val position = now.let {
                        if (p.isPlaying && p.positionUpdatedAt > 0) {
                            p.positionMs + ((SystemClock.elapsedRealtime() - p.positionUpdatedAt) * p.speed).toLong()
                        } else p.positionMs
                    }.coerceIn(0L, if (p.durationMs > 0) p.durationMs else Long.MAX_VALUE)
                    // Sürüklerken çubuk parmağı izler; bırakınca tek seek komutu gider.
                    var dragFraction by remember { mutableStateOf<Float?>(null) }
                    val seekable = p.durationMs > 0 && MediaHub.canSeek()
                    val progress = dragFraction ?: if (p.durationMs > 0) position.toFloat() / p.durationMs else 0f
                    Box(
                        Modifier.padding(top = 36.dp).fillMaxWidth().height(40.dp)
                            .then(
                                if (seekable) Modifier.pointerInput(p.durationMs) {
                                    fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        dragFraction = at(down.position.x)
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val ch = ev.changes.firstOrNull() ?: break
                                            if (!ch.pressed) break
                                            ch.consume()
                                            dragFraction = at(ch.position.x)
                                        }
                                        dragFraction?.let { MediaHub.seekTo((it * p.durationMs).toLong()) }
                                        dragFraction = null
                                    }
                                } else Modifier,
                            ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        val h by animateDpAsState(if (dragFraction != null) 12.dp else 7.dp, tween(MotionMs / 2), label = "ilerleme-kalınlık")
                        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(6.dp)).background(c.chip)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(RoundedCornerShape(6.dp)).background(c.text))
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        val shownPos = dragFraction?.let { (it * p.durationMs).toLong() } ?: position
                        Text(duration(shownPos), color = c.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (p.durationMs > 0) {
                            Text("-" + duration(p.durationMs - shownPos), color = c.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 28.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val enabled = p != null
                    val tint = if (enabled) c.text else c.tertiary
                    RoundIconButton(PanoIcons.Previous, "Önceki", { MediaHub.previous() }, Color.Transparent, tint, size = 88.dp, iconSize = 40.dp)
                    RoundIconButton(
                        if (p?.isPlaying == true) PanoIcons.Pause else PanoIcons.Play,
                        if (p?.isPlaying == true) "Duraklat" else "Oynat",
                        { if (enabled) MediaHub.playPause() else Launch.music(context, null) },
                        c.chip, c.text, size = 108.dp, iconSize = 46.dp,
                    )
                    RoundIconButton(PanoIcons.Next, "Sonraki", { MediaHub.next() }, Color.Transparent, tint, size = 88.dp, iconSize = 40.dp)
                }
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
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text("Tüketim", color = c.secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            // Değerin hangi pencereye ait olduğu; önizlemede açıkça "Önizleme".
            val scope = when {
                v.isPreview -> "Önizleme"
                v.consumption?.recentWindow == true -> "Son 50 km"
                v.consumption != null -> "Ortalama"
                else -> null
            }
            if (scope != null) {
                Text(scope, color = c.secondary.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
            }
        }
        Text(v.consumptionKwh100?.let(::decimal) ?: "—", color = c.text, fontSize = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp)
        Text("kWh/100", color = c.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
    }
}

// ───────────────────────── Alt klima şeridi ─────────────────────────

/** Dock düzenleme paneli açık mı (şeride basılı tutunca açılır). */
private object DockEditing {
    var open by mutableStateOf(false)
}

/**
 * Alt şerit (dock): aracın alt sistem çubuğunun yerini tutar. Hangi kontrollerin görüneceğini
 * kullanıcı seçer ([DockStore]); şeride basılı tutunca düzenleme paneli açılır. "Alt sistem
 * çubuğunu gizle" açıkken launcher'dan çıkmadan her şey buradan yönetilir.
 */
@Composable
private fun ClimateStrip(climate: ClimateState, onNavigate: (Screen) -> Unit, modifier: Modifier) {
    val c = glass()
    val context = LocalContext.current
    DockStore.load(context)
    val selected by DockStore.state

    BoxWithConstraints(
        modifier.glass(c, RoundedCornerShape(30.dp)).longPressAnywhere { DockEditing.open = true },
        contentAlignment = Alignment.Center,
    ) {
        val minWidth = maxWidth
        // Çok kontrol seçilirse şerit yana kaydırılır; azsa ortalanır.
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Row(
                Modifier.widthIn(min = minWidth).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                DockControl.entries.filter { it in selected }.forEach { control ->
                    DockItemView(control, climate, onNavigate)
                }
            }
        }
    }
}

@Composable
private fun DockItemView(control: DockControl, climate: ClimateState, onNavigate: (Screen) -> Unit) {
    val c = glass()
    val context = LocalContext.current
    val playing by MediaHub.state.collectAsState()
    @Composable
    fun icon(i: ImageVector, label: String, onClick: () -> Unit) =
        Box(Modifier.pressScale()) { RoundIconButton(i, label, onClick, c.chip, c.text, size = 58.dp, iconSize = 24.dp) }

    when (control) {
        DockControl.Home -> icon(PanoIcons.Home, "Ana pano") { AppSheet.close(); onNavigate(Screen.Home) }
        DockControl.Temperature -> Row(verticalAlignment = Alignment.CenterVertically) {
            // Tek bölgede tek, geniş sürgü ve yanında derece derece ‹ ›; çift bölgede sürücü sürgüsü.
            if (!climate.dualZone) {
                RoundIconButton(PanoIcons.ChevronLeft, "Sıcaklığı düşür", { climate.changeDriver(-1f) }, Color.Transparent, c.text, size = 58.dp, iconSize = 22.dp)
            }
            TempSlider(
                climate.driver, climate, if (climate.dualZone) "Sürücü" else "Sıcaklık",
                Modifier.width(if (climate.dualZone) 280.dp else 340.dp),
            ) { climate.setDriverTemp(it) }
            if (!climate.dualZone) {
                RoundIconButton(PanoIcons.ChevronRight, "Sıcaklığı artır", { climate.changeDriver(1f) }, Color.Transparent, c.text, size = 58.dp, iconSize = 22.dp)
            }
        }
        DockControl.PassengerTemperature -> if (climate.dualZone) {
            TempSlider(climate.passenger, climate, "Yolcu", Modifier.width(280.dp)) { climate.setPassengerTemp(it) }
        }
        DockControl.Auto -> GlassPill("Otomatik", climate.auto) { climate.auto = !climate.auto }
        DockControl.AC -> GlassPill("A/C", climate.ac) { climate.ac = !climate.ac }
        DockControl.Fan -> Row(
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
        DockControl.Power -> DockToggle(PanoIcons.Power, "Klima", !climate.isOff) {
            if (climate.isOff) climate.setFanLevel(1) else climate.powerOff()
        }
        DockControl.Recirculation -> DockToggle(PanoIcons.Recirculation, "Devridaim", climate.recirculation) { climate.recirculation = !climate.recirculation }
        DockControl.Defrost -> DockToggle(PanoIcons.Defrost, "Ön cam buğu çözme", climate.defrost) { climate.defrost = !climate.defrost }
        DockControl.RearDefrost -> DockToggle(PanoIcons.RearDefrost, "Arka cam ısıtma", climate.rearDefrost) { climate.rearDefrost = !climate.rearDefrost }
        DockControl.ClimateDetail -> icon(PanoIcons.ChevronUp, "Klima ayrıntıları") { onNavigate(Screen.Climate) }
        DockControl.Navigation -> icon(PanoIcons.Navigation, "Navigasyon") { Launch.maps(context) }
        DockControl.Music -> icon(PanoIcons.Music, "Müzik") { Launch.music(context, playing?.packageName) }
        DockControl.Phone -> icon(PanoIcons.Phone, "Telefon") { Launch.phone(context) }
        DockControl.Voice -> icon(PanoIcons.Mic, "Sesli asistan") { Launch.voiceAssistant(context) }
        DockControl.Back -> icon(PanoIcons.Back, "Geri") { app.pano.launcher.system.SystemActions.back(context) }
        DockControl.Split -> icon(PanoIcons.Split, "Yarım ekran") { app.pano.launcher.system.SystemActions.splitScreen(context) }
        DockControl.Recents -> icon(PanoIcons.Recents, "Son uygulamalar") { app.pano.launcher.system.SystemActions.recents(context) }
        DockControl.Rotate -> icon(PanoIcons.Rotate, "Ekranı döndür") { app.pano.launcher.system.SystemActions.rotate(context) }
        DockControl.Apps -> icon(PanoIcons.Apps, "Uygulamalar") { onNavigate(Screen.Apps) }
        DockControl.CarSettings -> icon(PanoIcons.Car, "Araç ayarları") { Launch.carSettings(context) }
        DockControl.PanoSettings -> icon(PanoIcons.Sliders, "Pano ayarları") { onNavigate(Screen.Settings) }
    }
}

/** Açık/kapalı durumlu yuvarlak dock düğmesi (devridaim, buğu çözme…): açıkken vurgu renginde. */
@Composable
private fun DockToggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val c = glass()
    Box(Modifier.pressScale()) {
        RoundIconButton(icon, label, onClick, if (on) linkColor() else c.chip, if (on) Color.White else c.text, size = 58.dp, iconSize = 24.dp)
    }
}

/**
 * Şeridin herhangi bir yerine (düğmelerin üstü dahil) basılı tutmayı yakalar. Parmak kaydırılırsa
 * (sürgü) uzun basma sayılmaz. Uzun basma algılanınca dokunuş tüketilir, alttaki düğme çalışmaz.
 */
private fun Modifier.longPressAnywhere(onLongPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis + 150
        var long = false
        var now = down.uptimeMillis
        while (true) {
            val remaining = deadline - now
            if (remaining <= 0) { long = true; break }
            val event = withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Initial) }
            if (event == null) { long = true; break }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            now = change.uptimeMillis
            if (!change.pressed) break
            if ((change.position - down.position).getDistance() > slop) break
        }
        if (long) {
            onLongPress()
            // Parmak kalkana kadar her şeyi tüket: alttaki düğme tıklanmasın.
            do {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                e.changes.forEach { it.consume() }
            } while (e.changes.any { it.pressed })
        }
    }
}

/** Dock düzenleme paneli: kontroller gruplar halinde; dokununca şeride eklenir / çıkarılır. */
@Composable
private fun DockEditor(modifier: Modifier) {
    val c = glass()
    val selected by DockStore.state
    Column(
        modifier.shadow(30.dp, RoundedCornerShape(32.dp)).clip(RoundedCornerShape(32.dp))
            .background(if (c.dark) Color(0xF71A1C22) else Color(0xFAF6F7F9))
            .padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Alt şeridi düzenle", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text("Dokunarak ekle ya da çıkar. Değişiklik hemen uygulanır.", color = c.secondary, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text(
                "Varsayılana dön", color = Accent.Red, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { DockStore.reset() }.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Box(Modifier.padding(start = 8.dp)) {
                GlassPill("Bitti", true) { DockEditing.open = false }
            }
        }
        DockControl.entries.groupBy { it.group }.forEach { (group, controls) ->
            Text(group, color = c.secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
            controls.chunked(5).forEach { row ->
                Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { control ->
                        val on = control in selected
                        Row(
                            Modifier.width(232.dp).height(64.dp).clip(RoundedCornerShape(20.dp))
                                .background(if (on) linkColor() else c.chip)
                                .clickable(role = Role.Switch) { DockStore.toggle(control) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(dockIcon(control), contentDescription = null, tint = if (on) Color.White else c.text, modifier = Modifier.size(24.dp))
                            Text(
                                control.label, color = if (on) Color.White else c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun dockIcon(control: DockControl): ImageVector = when (control) {
    DockControl.Home -> PanoIcons.Home
    DockControl.Temperature, DockControl.PassengerTemperature -> PanoIcons.Sun
    DockControl.Auto -> PanoIcons.Sync
    DockControl.AC -> PanoIcons.Snow
    DockControl.Fan -> PanoIcons.Fan
    DockControl.Power -> PanoIcons.Power
    DockControl.Recirculation -> PanoIcons.Recirculation
    DockControl.Defrost -> PanoIcons.Defrost
    DockControl.RearDefrost -> PanoIcons.RearDefrost
    DockControl.ClimateDetail -> PanoIcons.ChevronUp
    DockControl.Navigation -> PanoIcons.Navigation
    DockControl.Music -> PanoIcons.Music
    DockControl.Phone -> PanoIcons.Phone
    DockControl.Voice -> PanoIcons.Mic
    DockControl.Back -> PanoIcons.Back
    DockControl.Split -> PanoIcons.Split
    DockControl.Recents -> PanoIcons.Recents
    DockControl.Rotate -> PanoIcons.Rotate
    DockControl.Apps -> PanoIcons.Apps
    DockControl.CarSettings -> PanoIcons.Car
    DockControl.PanoSettings -> PanoIcons.Sliders
}

/**
 * Parmakla kaydırılan sıcaklık sürgüsü: soldan sağa soğuktan sıcağa. Sürüklerken yalnızca
 * ekranda, parmağı izleyerek hareket eder; parmak kalkınca son değer araca tek komutla gider
 * (ara değerler araca gönderilmez, araçtan gelen okuma da sürüklerken sürgüyü geri çekmez).
 * Dokunulan yere atlar. Klima kapalıyken de son sıcaklık soluk gösterilir.
 */
@Composable
private fun TempSlider(value: Float, climate: ClimateState, label: String, modifier: Modifier, onChange: (Float) -> Unit) {
    val c = glass()
    val min = climate.minTemp
    val max = climate.maxTemp
    val step = climate.step
    // Sürükleme sırasındaki değer; null = sürüklenmiyor, gerçek değer gösterilir.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val raw = dragging ?: value
    val snapped = (Math.round(raw / step) * step).coerceIn(min, max)
    val fraction = ((raw - min) / (max - min)).coerceIn(0f, 1f)
    // Sürüklerken gecikmesiz, bırakınca yumuşak.
    val shown by animateFloatAsState(fraction, if (dragging != null) snap() else tween(160), label = "sürgü")
    val shape = RoundedCornerShape(29.dp)
    val commit by rememberUpdatedState(onChange)

    fun valueAt(x: Float, width: Int): Float = min + (x / width).coerceIn(0f, 1f) * (max - min)

    Box(
        modifier.height(58.dp).clip(shape).background(c.chip)
            .pointerInput(min, max) {
                detectTapGestures { commit(valueAt(it.x, size.width)) }
            }
            .pointerInput(min, max) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = valueAt(it.x, size.width) },
                    onDragEnd = {
                        dragging?.let { commit(it) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    change.consume()
                    dragging = valueAt(change.position.x, size.width)
                }
            }
            .semantics { contentDescription = "$label sıcaklığı ${shortTemp(snapped)}" },
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
                tempLabel(snapped, climate),
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
