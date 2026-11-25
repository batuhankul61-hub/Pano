package app.pano.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.Launch
import app.pano.launcher.Screen
import app.pano.launcher.media.MediaHub
import app.pano.launcher.navigation.NavHub
import app.pano.launcher.navigation.NavPhase
import app.pano.launcher.navigation.PanoNav
import app.pano.launcher.navigation.Place
import app.pano.launcher.navigation.RouteEngine
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import app.pano.launcher.navigation.formatDistance
import app.pano.launcher.settings.LocalStyle
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano
import app.pano.launcher.ui.theme.PanoColors
import app.pano.launcher.ui.theme.activeColor
import app.pano.launcher.ui.theme.onActiveColor
import app.pano.launcher.vehicle.ChargeState
import app.pano.launcher.vehicle.ClimateState
import app.pano.launcher.vehicle.Gear
import app.pano.launcher.vehicle.Opening
import app.pano.launcher.vehicle.Openings
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

/** Sürüşte ya da yönlendirmede daralan araç sütunu: harita büyür, araç kontrolleri gizlenir. */
private val CarColumnCompactWidth = 400.dp

/** Rota önizlemesinde "Nereye?" kartının kapladığı harita oranı (sağdan). */
private const val NavNarrowInset = 0.56f

/** Alt çubuğun yüksekliği. */
internal val BarHeight = 112.dp
/**
 * Uygulamaların üstündeki ince şeridin boyu (tasarım birimi). Uygulamanın altını Pano'daki şeritten
 * az örtsün diye ince; düğmeler yine ≥60 birim (sürüşte dokunulabilir).
 */
internal val OverlayBarHeight = 72.dp

/** Sürüşte kısıtlama: araç hareket ediyor **ve** ayarlarda açık. */
@Composable
private fun ufukDrivingLocked(): Boolean {
    val driving by VehicleHub.isDriving.collectAsState()
    return driving && LocalStyle.current.drivingLock
}

/** Haritanın üstünde açılan kart. */
private enum class UfukPanel { Climate, Apps, Settings, Music, Nav, Phone }

@Composable
fun UfukApp(screen: Screen, climate: ClimateState, onNavigate: (Screen) -> Unit) {
    val colors = LocalPano.current
    val panel: UfukPanel? = when {
        screen == Screen.Climate -> UfukPanel.Climate
        screen == Screen.Apps -> UfukPanel.Apps
        screen == Screen.Settings -> UfukPanel.Settings
        AppSheet.nav -> UfukPanel.Nav
        AppSheet.music -> UfukPanel.Music
        AppSheet.phone -> UfukPanel.Phone
        else -> null
    }
    // Aynı anda tek kart: yerel ekran açılınca müzik / nereye kapanır; onlar açılınca ekran ana sayfaya döner.
    LaunchedEffect(screen) { if (screen != Screen.Home) AppSheet.close() }
    val sheetOpen = AppSheet.nav || AppSheet.music || AppSheet.phone
    LaunchedEffect(sheetOpen) { if (sheetOpen && screen != Screen.Home) onNavigate(Screen.Home) }

    val volume = rememberUfukVolume()

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
                    // Sürüşte ya da yönlendirmede sütun daralır; harita yer kazanır.
                    val driving by VehicleHub.isDriving.collectAsState()
                    val navActive = navSession?.let { it.phase != NavPhase.Preview && it.route != null } == true
                    val compact = driving || navActive
                    val columnWidth by animateDpAsState(
                        if (compact) CarColumnCompactWidth else CarColumnWidth, tween(UfukMotionMs * 2), label = "araç-sütunu",
                    )
                    Row(Modifier.fillMaxSize()) {
                        CarColumn(compact, Modifier.width(columnWidth).fillMaxHeight())
                        MapCanvas(Modifier.weight(1f).fillMaxHeight(), endInset = if (navNarrow) NavNarrowInset else 0f)
                    }
                    // Kartlar haritanın üstünde açılır; klima geniş olduğu için araç sütununu da örter.
                    PanelHost(panel, navNarrow, columnWidth, climate, onNavigate, onClose = ::closePanel)
                    // Görüşme kartı: açık kartların da üstünde, sağ üstte. Erişilebilirlik katmanı
                    // açıksa (BYD penceresini örtüyorsa) kartı o çizer, burada çizilmez.
                    val call by app.pano.launcher.phone.CallHub.state.collectAsState()
                    val shown = call?.takeIf { !app.pano.launcher.system.CallOverlay.covering.value }
                    androidx.compose.animation.AnimatedVisibility(
                        shown != null,
                        enter = fadeIn(tween(UfukMotionMs)),
                        exit = fadeOut(tween(UfukMotionMs)),
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 24.dp),
                    ) {
                        var lastCall by remember { mutableStateOf(shown) }
                        if (shown != null) lastCall = shown
                        lastCall?.let { CallCard(it, Modifier.width(CallCardWidth)) }
                    }
                    // Ses göstergesi ses düğmelerinin üstünde; harita düğmeleri (72 dp) ve alttaki
                    // OpenStreetMap atfı açıkta kalır.
                    VolumeHud(volume, Modifier.align(Alignment.BottomEnd).padding(end = 112.dp, bottom = 40.dp))
                }
                BottomBar(climate, screen, panel, volume, onNavigate, Modifier.fillMaxWidth().height(BarHeight))
            }
        }
    }
}

// ───────────────────────── Araç sütunu ─────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CarColumn(compact: Boolean, modifier: Modifier) {
    val c = LocalPano.current
    val context = LocalContext.current
    val v by VehicleHub.state.collectAsState()
    val driving by VehicleHub.isDriving.collectAsState()
    val locked = ufukDrivingLocked()
    // Sürüşte şarj görünümü hiç açılmaz (hatalı bir sinyal hızı gizlemesin).
    val charge = v.charge?.takeIf { it.isActive && !driving }

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
            // Şarjda yüzde aşağıda büyük yazar; burada yalnızca menzil kalır (bilgi tek yerde).
            BatteryReadout(v.batteryPercent, v.rangeKm, c, showPercent = charge == null)
        }

        // Şarjda (park halinde) hızın yerini şarj görünümü alır; kablo çıkınca/şarj bitince hız geri gelir.
        if (charge != null) {
            ChargingReadout(charge, v.batteryPercent, Modifier.padding(top = 18.dp, bottom = 14.dp))
        } else
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
            // Dar sütunda (sürüş) üçü tek satıra sığmıyor, tüketim kesiliyordu; sığmayan alt satıra iner.
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                chips.forEach { label ->
                    Text(
                        label, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.raised).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // Araç görseli ve hemen altında, ona ortalı açıklık durumu (görselin alt yazısı gibi).
        // Durum satırının yüksekliği sabit: uyarı gelip gidince görsel kıpırdamaz.
        Spacer(Modifier.weight(0.2f))
        CarIllustration(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            dark = c.isDark, background = Color.Transparent,
        )
        OpeningsStatus(v.openings, compact)
        Spacer(Modifier.height(12.dp))
        // Müzik yalnızca bir şey çalıyorsa yer kaplar.
        NowPlayingStrip(compact)
    }
}

@Composable
private fun BatteryReadout(percent: Int?, rangeKm: Int?, c: PanoColors, showPercent: Boolean = true) {
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(rangeKm?.toString() ?: "—", color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp)
            Text("km", color = c.secondary, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
        }
        if (showPercent) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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

/**
 * Şarj görünümü: durum satırı (şimşek + "Şarj oluyor" + AC/DC), büyük yüzde, hedef işaretli
 * ilerleme çubuğu ve bilinen ölçümler (güç, kalan süre, hedef). Bilinmeyen ölçüm yazılmaz.
 * Renk yalnızca durum anlatır: şarj ederken yeşil, bitince/beklerken nötr. Önizlemede ayrı etiket
 * yok; sütunun başındaki "Önizleme" yeterli (Ufuk ilkesi: tek yerde).
 */
@Composable
private fun ChargingReadout(charge: ChargeState, percent: Int?, modifier: Modifier = Modifier) {
    val c = LocalPano.current
    val flowing = charge.charging == true || (charge.charging == null && (charge.powerKw ?: 0f) >= ChargeState.MIN_ACTIVE_KW)
    val tone = if (flowing) Accent.Green else c.secondary
    val title = when {
        charge.finished == true -> "Şarj tamamlandı"
        flowing -> "Şarj oluyor"
        else -> "Şarj kablosu takılı"
    }
    Column(modifier.fillMaxWidth().semantics { contentDescription = "$title, yüzde ${percent ?: "bilinmiyor"}" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PanoIcons.Bolt, contentDescription = null, tint = tone, modifier = Modifier.size(26.dp))
            Text(
                title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
            charge.kind?.let {
                Text(
                    it.name, color = c.secondary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(8.dp)).background(c.raised)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Row(Modifier.padding(top = 4.dp)) {
            Text(
                percent?.toString() ?: "–", color = if (percent == null) c.tertiary else c.text, fontSize = 112.sp,
                fontWeight = FontWeight.Light, letterSpacing = (-4).sp, lineHeight = 112.sp,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                "%", color = c.secondary, fontSize = 34.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.alignByBaseline().padding(start = 6.dp),
            )
        }
        // İlerleme: dolu kısım şu anki yüzde, ince çizgi hedef.
        val target = charge.targetPercent
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.raised)) {
            val p = (percent ?: 0).coerceIn(0, 100) / 100f
            Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(7.dp)).background(if (flowing) Accent.Green else c.text))
            if (target != null && target < 100) {
                Box(Modifier.offset(x = maxWidth * (target / 100f) - 1.5.dp).width(3.dp).fillMaxHeight().background(c.text.copy(alpha = 0.6f)))
            }
        }
        val stats = listOfNotNull(
            charge.powerKw?.takeIf { flowing }?.let { "Güç" to "${decimal(it)} kW" },
            charge.remainingMin?.takeIf { flowing && it > 0 }?.let { "Kalan" to durationText(it) },
            target?.let { "Hedef" to "%$it" },
        )
        if (stats.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                stats.forEach { (label, value) ->
                    Column {
                        Text(label, color = c.secondary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(value, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = tabular())
                    }
                }
            }
        }
    }
}

/** 869 → "14 sa 29 dk", 45 → "45 dk". */
internal fun durationText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m dk"
        m == 0 -> "$h sa"
        else -> "$h sa $m dk"
    }
}

/**
 * Araç görselinin altındaki, ona ortalı durum satırı. Açık kalan her parça kendi renkli etiketinde
 * (kapı/bagaj/kaput kırmızı, cam turuncu); hiçbiri açık değilse sakin bir "kapalı" satırı.
 * Açıklıklar araçtan okunamıyorsa (önizleme) satır boş kalır, uydurma "kapalı" yazılmaz.
 * Yükseklik sabit: içerik değişince araç görseli yerinden oynamaz.
 */
@Composable
private fun OpeningsStatus(openings: Openings?, compact: Boolean) {
    val c = LocalPano.current
    val open = openings?.open.orEmpty()
    val known = openings != null && !openings.isEmpty
    // Bir etiket ~195 dp: geniş sütuna iki, dar sütuna (sürüş) bir tane sığıyor; kalanı sayı olarak.
    val shown = if (compact) 1 else 2
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = when { open.isNotEmpty() -> open; known -> emptyList(); else -> null },
            transitionSpec = { fadeIn(tween(UfukMotionMs)) togetherWith fadeOut(tween(UfukMotionMs)) },
            label = "açıklıklar",
        ) { items ->
            when {
                items == null -> Spacer(Modifier.fillMaxWidth())
                items.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PanoIcons.Lock, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(18.dp))
                    Text(
                        "Kapılar ve camlar kapalı", color = c.tertiary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    items.take(shown).forEach { OpeningChip(it) }
                    if (items.size > shown) {
                        Text(
                            "+${items.size - shown}", color = c.secondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpeningChip(item: Opening) {
    val c = LocalPano.current
    val tone = if (item.isMajor) Accent.Red else Color(0xFFFF9F0A)
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(tone.copy(alpha = if (c.isDark) 0.18f else 0.12f))
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(tone))
        Text(
            "${item.label} açık", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Sütunun altındaki müzik şeridi. Dar sütunda (sürüş, yönlendirme) üç 72 dp düğme şarkı adına
 * yer bırakmıyordu; o zaman "Önceki" düşer, düğmeler 64 dp olur ve ad park halindeki kadar yer bulur.
 */
@Composable
private fun NowPlayingStrip(compact: Boolean) {
    val c = LocalPano.current
    val button = if (compact) 64.dp else 72.dp
    val playing by MediaHub.state.collectAsState()
    val p = playing
    AnimatedVisibility(p != null, enter = fadeIn(tween(UfukMotionMs)), exit = fadeOut(tween(UfukMotionMs))) {
        if (p == null) return@AnimatedVisibility
        Row(
            // Sağa/sola kaydırınca önceki/sonraki parça.
            Modifier.fillMaxWidth().height(96.dp).trackSwipe().clip(RoundedCornerShape(24.dp)).background(c.raised)
                // Dokunuş büyük "Şimdi çalıyor"u açar; açıkken yeniden dokunmak kapatır (dock'taki müzik gibi).
                .clickable(role = Role.Button, onClickLabel = if (AppSheet.music) "Şimdi çalıyor ekranını kapat" else "Şimdi çalıyor") {
                    if (AppSheet.music) AppSheet.close() else AppSheet.openMusic()
                }
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(c.card), contentAlignment = Alignment.Center) {
                val art = p.art
                if (art != null) {
                    Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(if (p.radio != null) PanoIcons.Radio else PanoIcons.Music, contentDescription = null, tint = c.secondary, modifier = Modifier.size(30.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(p.displayTitle, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.displaySubtitle.isNotBlank()) {
                    Text(p.displaySubtitle, color = c.secondary, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val unit = if (p.radio != null) " istasyon" else ""
            if (!compact) {
                RoundIconButton(PanoIcons.Previous, "Önceki$unit", { MediaHub.previous() }, Color.Transparent, c.text, size = button, iconSize = 26.dp)
            }
            RoundIconButton(
                if (p.isPlaying) PanoIcons.Pause else PanoIcons.Play, if (p.isPlaying) "Duraklat" else "Çal",
                { MediaHub.playPause() }, Color.Transparent, c.text, size = button, iconSize = 30.dp,
            )
            RoundIconButton(PanoIcons.Next, "Sonraki$unit", { MediaHub.next() }, Color.Transparent, c.text, size = button, iconSize = 26.dp)
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
        // Yönlendirmede hıza göre yakınlık: şehir içinde yakın, otoyolda daha geniş görüş.
        val speed = VehicleHub.state.collectAsState().value.speedKmh
        val speedZoom = when {
            speed == null || speed < 30 -> 16.5
            speed < 60 -> 16.0
            speed < 90 -> 15.2
            else -> 14.4
        }
        LaunchedEffect(speedZoom, session != null) { if (session != null) controls.setTrackingZoom(speedZoom) }
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

        // Uzun basılan nokta: iğne ve "Buraya git" kartı.
        PressedPlace(controls, session == null, Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 24.dp).width(600.dp))

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

/**
 * Haritada uzun basılan yer: noktada küçük bir iğne, altta adı (Photon ters arama) ve
 * "Buraya git". Yönlendirme sürerken kart gösterilmez (alttaki varış çubuğuyla çakışmasın).
 */
@Composable
private fun BoxScope.PressedPlace(controls: MapControls, allowCard: Boolean, cardModifier: Modifier) {
    val c = LocalPano.current
    val context = LocalContext.current
    val point = controls.pressed ?: return
    var place by remember(point) { mutableStateOf<Place?>(null) }
    var loading by remember(point) { mutableStateOf(true) }
    LaunchedEffect(point) {
        place = RouteEngine.reverse(point.latitude, point.longitude)
        loading = false
    }

    // İğne: kamera kaydıkça yeri güncellenir.
    val screen = controls.screenPosition(point)
    if (screen != null) {
        val half = with(LocalDensity.current) { 14.dp.toPx() }
        Box(
            Modifier.offset { IntOffset((screen.x - half).toInt(), (screen.y - half).toInt()) }
                .size(28.dp).clip(CircleShape).background(Color.White).padding(4.dp).clip(CircleShape).background(Accent.Red),
        )
    }
    if (!allowCard) return

    val coords = String.format(trLocale, "%.5f, %.5f", point.latitude, point.longitude)
    val target = place ?: Place("Seçilen yer", coords, point.latitude, point.longitude)
    Row(
        Modifier.align(Alignment.BottomStart).then(cardModifier).height(96.dp).clip(RoundedCornerShape(28.dp)).background(c.glass)
            .border(1.dp, c.hairline, RoundedCornerShape(28.dp)).padding(start = 22.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PanoIcons.Pin, contentDescription = null, tint = c.text, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                if (loading) "Aranıyor…" else target.name, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                target.detail.ifBlank { coords }, color = c.secondary, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.padding(start = 12.dp).height(72.dp).clip(RoundedCornerShape(36.dp)).background(c.text)
                .clickable(role = Role.Button) {
                    PanoNav.preview(context, target)
                    AppSheet.openNav()
                    controls.clearPressed()
                }
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Buraya git", color = c.inverseText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        RoundIconButton(PanoIcons.Close, "Kapat", { controls.clearPressed() }, Color.Transparent, c.secondary, size = 72.dp, iconSize = 24.dp)
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
private fun BoxScope.PanelHost(panel: UfukPanel?, navNarrow: Boolean, columnWidth: Dp, climate: ClimateState, onNavigate: (Screen) -> Unit, onClose: () -> Unit) {
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
        // Kart sağa sürüklenip bırakılınca kapanır; az çekilirse yerine yaylanır. İçerideki kendi
        // yatay hareketler (kapak kaydırma, ilerleme çubuğu) önce davranır ve sürüklemeyi tüketir.
        val drag = remember { androidx.compose.animation.core.Animatable(0f) }
        val dragScope = androidx.compose.runtime.rememberCoroutineScope()
        val closeAt = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
        androidx.compose.runtime.LaunchedEffect(panel) { if (panel != null) drag.snapTo(0f) }
        Box(
            // Sürükleme yalnızca kartın kendi alanında: soldaki sütun (müzik şeridi) dokunulabilir kalsın.
            Modifier.fillMaxSize()
                .padding(
                    start = when {
                        p == UfukPanel.Climate -> 0.dp
                        navNarrow -> columnWidth + (1600.dp - columnWidth) * (1f - NavNarrowInset)
                        else -> columnWidth
                    },
                )
                .padding(16.dp)
                .graphicsLayer {
                    translationX = drag.value
                    alpha = 1f - (drag.value / (closeAt * 4f)).coerceIn(0f, 0.4f)
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (drag.value > closeAt) onClose()
                            else dragScope.launch { drag.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.75f)) }
                        },
                        onDragCancel = { dragScope.launch { drag.animateTo(0f) } },
                    ) { change, dx ->
                        change.consume()
                        // Yalnızca sağa (kapatma yönüne) gider; sola çekmek kartı yerinden oynatmaz.
                        dragScope.launch { drag.snapTo((drag.value + dx).coerceAtLeast(0f)) }
                    }
                }
                .clip(shape).background(c.card).border(1.dp, c.hairline, shape),
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
                    UfukPanel.Phone -> PanelWithTitle("Telefon", onClose) {
                        PhoneScreen(Modifier.fillMaxSize().padding(start = 28.dp, end = 32.dp, bottom = 28.dp))
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

/** Pano'daki alt çubuk. Uygulamaların üstündeki ince kopyası [OverlayStrip]. */
@Composable
private fun BottomBar(
    climate: ClimateState, screen: Screen, panel: UfukPanel?, volume: UfukVolume, onNavigate: (Screen) -> Unit, modifier: Modifier,
) {
    val c = LocalPano.current
    val context = LocalContext.current
    val playing by MediaHub.state.collectAsState()
    val locked = ufukDrivingLocked()
    fun toggle(target: Screen) = onNavigate(if (screen == target) Screen.Home else target)
    val openClimate = { toggle(Screen.Climate) }
    val edge = c.hairline

    Row(
        modifier.background(c.background).drawBehind {
            drawRect(edge, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
        }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarTemp(
            "Sürücü", climate.driver, climate.step, climate.isOff,
            onDown = { climate.changeDriver(-0.5f) }, onUp = { climate.changeDriver(0.5f) },
            onOpen = openClimate,
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            BarApp(
                PanoIcons.Music, if (playing?.isPlaying == true) "Müzik, çalıyor" else "Müzik",
                active = panel == UfukPanel.Music, playing = playing?.isPlaying == true,
            ) {
                when {
                    AppSheet.music -> AppSheet.close()
                    // Hiçbir şey çalmasa da araçta radyo varsa panel açılır: oradan radyo ya da müzik seçilir.
                    playing != null || Launch.hasRadio(context) -> AppSheet.openMusic()
                    else -> Launch.music(context, null)
                }
            }
            BarApp(PanoIcons.Phone, "Telefon", active = panel == UfukPanel.Phone) {
                if (AppSheet.phone) AppSheet.close() else AppSheet.openPhone()
            }
            BarApp(PanoIcons.Fan, "Klima", active = panel == UfukPanel.Climate) { openClimate() }
            BarApp(PanoIcons.Apps, "Uygulamalar", active = panel == UfukPanel.Apps, dimmed = locked) { toggle(Screen.Apps) }
            BarApp(PanoIcons.Sliders, "Ayarlar", active = panel == UfukPanel.Settings, dimmed = locked) { toggle(Screen.Settings) }
        }
        if (climate.dualZone) {
            BarTemp(
                "Yolcu", climate.passenger, climate.step, climate.isOff,
                onDown = { climate.changePassenger(-0.5f) }, onUp = { climate.changePassenger(0.5f) },
                onOpen = openClimate,
            )
        }
        Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(48.dp).background(edge))
        VolumeControl(volume)
    }
}

/** Uygulamaların üstündeki şeridin zemini: gündüz uygulamaların beyazından ayrılan gri, gece neredeyse siyah. */
private fun overlayBarColor(c: PanoColors): Color = if (c.isDark) Color(0xFF0A0A0B) else Color(0xFFE6E6EB)

/** Şerit ile uygulama arasındaki kalın kenar ve ayırıcılar. */
private fun overlayEdgeColor(c: PanoColors): Color = if (c.isDark) Color(0xFF3A3A3F) else Color(0xFFB8B8C0)

/** Pano'ya (ana sayfaya) dönüş: şeridin en solunda, en büyük düğme. */
@Composable
private fun HomeButton(onClick: () -> Unit, compact: Boolean = false) {
    val c = LocalPano.current
    Box(
        Modifier.width(if (compact) 92.dp else 104.dp).height(if (compact) 60.dp else 80.dp)
            .clip(RoundedCornerShape(if (compact) 18.dp else 22.dp))
            .background(if (c.isDark) c.raised else c.card)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Pano ana sayfası" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(PanoIcons.Home, contentDescription = null, tint = c.text, modifier = Modifier.size(if (compact) 32.dp else 38.dp))
    }
}

/**
 * Alt çubuktaki sıcaklık yazısı; klima kartıyla aynı biçim ([shortTemp]): "22,5°", tam derecede "21°".
 * Kutu sabit genişlikte ve rakamlar eşit genişlikte olduğu için biçim değişince şerit oynamaz.
 * Araç yalnızca tam derece kabul ediyorsa değer en yakın dereceye yuvarlanır.
 */
internal fun barTempText(t: Float, step: Float): String =
    if (step >= 1f) "${Math.round(t)}°" else app.pano.launcher.vehicle.shortTemp(t)

/** Rakamlar eşit genişlikte (Inter "tnum"): sıcaklık değişince şerit oynamaz. */
@Composable
private fun tabular() = androidx.compose.material3.LocalTextStyle.current.copy(fontFeatureSettings = "tnum")

/** Sıcaklık: oklar ≥72 dp, sayıya dokununca klima kartı açılır. Sayının kutusu sabit genişlikte. */
@Composable
private fun BarTemp(
    who: String, value: Float, step: Float, off: Boolean, onDown: () -> Unit, onUp: () -> Unit, onOpen: () -> Unit,
    compact: Boolean = false,
) {
    val c = LocalPano.current
    val button = if (compact) 64.dp else 72.dp
    val icon = if (compact) 26.dp else 30.dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(PanoIcons.ChevronLeft, "$who sıcaklığını düşür", onDown, Color.Transparent, c.secondary, size = button, iconSize = icon)
        Box(
            Modifier.width(if (compact) 112.dp else 128.dp).height(button).clip(RoundedCornerShape(20.dp))
                .clickable(role = Role.Button, onClickLabel = "Klima") { onOpen() }
                .semantics { contentDescription = "$who sıcaklığı" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (off) "Kapalı" else barTempText(value, step), color = c.text,
                fontSize = when { off -> if (compact) 20.sp else 24.sp; compact -> 30.sp; else -> 34.sp },
                fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp, maxLines = 1,
                style = tabular(),
            )
        }
        RoundIconButton(PanoIcons.ChevronRight, "$who sıcaklığını artır", onUp, Color.Transparent, c.secondary, size = button, iconSize = icon)
    }
}

/**
 * Tek renk uygulama düğmesi. Etkin olan vurgu renginde dolu (tek renkte gündüz siyah, gece beyaz):
 * güneşte de seçilir. [playing] ise ikonun altında küçük bir ses dalgası durur.
 */
@Composable
private fun BarApp(
    icon: ImageVector, label: String, active: Boolean = false, dimmed: Boolean = false, playing: Boolean = false,
    compact: Boolean = false, onClick: () -> Unit,
) {
    val c = LocalPano.current
    val fg = when {
        active -> onActiveColor()
        dimmed -> c.tertiary
        playing -> c.text
        else -> c.secondary
    }
    Box(
        Modifier.width(if (compact) 84.dp else 96.dp).height(if (compact) 60.dp else 80.dp)
            .clip(RoundedCornerShape(if (compact) 18.dp else 22.dp))
            .background(if (active) activeColor() else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(if (compact) 28.dp else 34.dp), tint = fg)
        if (playing) PlayingBars(fg, Modifier.align(Alignment.BottomCenter).padding(bottom = if (compact) 4.dp else 7.dp))
    }
}

/** "Çalıyor" işareti: üç kısa çubuk. Hareketsiz; şerit sürekli yeniden çizilmesin. */
@Composable
private fun PlayingBars(color: Color, modifier: Modifier) {
    Row(modifier.height(10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(6.dp, 10.dp, 7.dp).forEach { h ->
            Box(Modifier.width(4.dp).height(h).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

// ───────────────────────── Uygulamaların üstündeki şerit ─────────────────────────

/** Şeridin üstünde açılan küçük pencere. Aynı anda biri. */
internal enum class OverlayPopup { Climate, Volume }

/** Uygulamaların üstündeki şeridin durumu; [app.pano.launcher.system.BarOverlay] dışarı dokununca kapatır. */
internal object UfukOverlayState {
    var popup by mutableStateOf<OverlayPopup?>(null)
    fun dismiss() { popup = null }
}

/** Şeridin üstünde, açılan pencere için ayrılan yükseklik (tasarım birimi). */
private fun popupHeight(p: OverlayPopup?): Dp = when (p) {
    OverlayPopup.Climate -> 252.dp
    OverlayPopup.Volume -> 116.dp
    null -> 0.dp
}

/**
 * Pano arkadayken başka uygulamaların üstünde duran alt şerit ([app.pano.launcher.system.BarOverlay]).
 * Pano'dakiyle aynı çubuk; en solda Pano'ya dönüş. Klima ve ses uygulamadan çıkmadan şeridin
 * üstünde açılır; pencere o sırada [onHeight] ile büyür, kapanınca yine şerit boyuna iner.
 * Klima kartı açıkken pencere tüm ekranı kaplar (null): uygulama hafifçe kararır ve dışarı
 * dokunmak yalnızca kartı kapatır, alttaki uygulamada yanlışlıkla bir şeye basılmaz.
 * Ses göstergesi kısa sürdüğü için yalnızca şeridin üstündeki şerit kadar yer alır.
 * Uygulamalar, Ayarlar ve Müzik Pano'yu o kartla öne getirir ([onOpen]).
 *
 * [scale]: tasarım tuvalinin (1600 birim genişlik) ekrandaki ölçeği; Pano'daki şeritle aynı boy.
 */
@Composable
fun UfukOverlayBar(scale: Float, onOpen: (Screen) -> Unit, onHeight: (designHeight: Float?) -> Unit) {
    val context = LocalContext.current
    val climate = remember { app.pano.launcher.vehicle.ClimateStore.load(context) }
    val volume = rememberUfukVolume()
    val popup = UfukOverlayState.popup

    // Ses düğmesine dokununca gösterge açılır; son dokunuştan bir süre sonra kapanır.
    LaunchedEffect(volume.touchedAt) {
        if (volume.touchedAt == 0L) return@LaunchedEffect
        UfukOverlayState.popup = OverlayPopup.Volume
        delay(VolumeHudMs)
        if (UfukOverlayState.popup == OverlayPopup.Volume) UfukOverlayState.dismiss()
    }
    // Pencere açılırken hemen büyür; kapanırken kayma bitince şerit boyuna iner (dokunuşlar yine uygulamaya).
    LaunchedEffect(popup) {
        if (popup == null) delay(UfukMotionMs.toLong())
        onHeight(if (popup == OverlayPopup.Climate) null else OverlayBarHeight.value + popupHeight(popup).value)
    }
    DisposableEffect(Unit) { onDispose { UfukOverlayState.dismiss() } }

    val density = LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDensity provides androidx.compose.ui.unit.Density(density.density * scale, 1f),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            // Kartın dışındaki alan: dokununca kart kapanır. Klima açıkken uygulama hafifçe kararır.
            val scrim by animateFloatAsState(if (popup == OverlayPopup.Climate) 1f else 0f, tween(UfukMotionMs), label = "karartma")
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .drawBehind { if (scrim > 0f) drawRect(Color.Black.copy(alpha = 0.32f * scrim)) }
                    .pointerInput(Unit) { detectTapGestures { UfukOverlayState.dismiss() } },
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    popup == OverlayPopup.Climate,
                    enter = fadeIn(tween(UfukMotionMs / 2)) + slideInVertically(tween(UfukMotionMs)) { it },
                    exit = fadeOut(tween(UfukMotionMs)) + slideOutVertically(tween(UfukMotionMs)) { it },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                ) {
                    OverlayClimateCard(climate) { UfukOverlayState.dismiss() }
                }
                androidx.compose.animation.AnimatedVisibility(
                    popup == OverlayPopup.Volume,
                    enter = fadeIn(tween(UfukMotionMs / 2)) + slideInVertically(tween(UfukMotionMs)) { it },
                    exit = fadeOut(tween(UfukMotionMs)) + slideOutVertically(tween(UfukMotionMs)) { it },
                    // Ses düğmelerinin hemen üstünde.
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
                ) {
                    VolumeHudBody(volume, Modifier.swallowTaps(), edge = overlayEdgeColor(LocalPano.current))
                }
            }
            OverlayStrip(
                climate, volume, Modifier.fillMaxWidth().height(OverlayBarHeight),
                climateOpen = popup == OverlayPopup.Climate,
                onOpen = onOpen,
                onClimate = {
                    UfukOverlayState.popup = if (UfukOverlayState.popup == OverlayPopup.Climate) null else OverlayPopup.Climate
                },
            )
        }
    }
}

/**
 * Uygulamaların üstündeki ince şerit: yalnızca uygulamadan çıkmadan gereken kontroller.
 * Ev (Pano'ya dönüş) · sürücü sıcaklığı · Müzik, Telefon, Klima · yolcu sıcaklığı · ses.
 * Uygulamalar ve Ayarlar yok: ikisi de Pano'yu açıyordu, ev düğmesi o işi görüyor.
 */
@Composable
private fun OverlayStrip(
    climate: ClimateState, volume: UfukVolume, modifier: Modifier,
    climateOpen: Boolean, onOpen: (Screen) -> Unit, onClimate: () -> Unit,
) {
    val c = LocalPano.current
    val context = LocalContext.current
    val playing by MediaHub.state.collectAsState()
    val edge = overlayEdgeColor(c)
    Row(
        modifier.background(overlayBarColor(c))
            .drawBehind { drawRect(edge, size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())) }
            .padding(start = 8.dp, end = 12.dp, top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeButton({ onOpen(Screen.Home) }, compact = true)
        Box(Modifier.padding(start = 10.dp, end = 2.dp).width(1.dp).height(36.dp).background(edge))
        BarTemp(
            "Sürücü", climate.driver, climate.step, climate.isOff,
            onDown = { climate.changeDriver(-0.5f) }, onUp = { climate.changeDriver(0.5f) },
            onOpen = onClimate, compact = true,
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            val isPlaying = playing?.isPlaying == true
            BarApp(PanoIcons.Music, if (isPlaying) "Müzik, çalıyor" else "Müzik", playing = isPlaying, compact = true) {
                // Müzik kartı Pano'da açılır; çalan ya da radyo yoksa müzik uygulaması.
                if (playing != null || Launch.hasRadio(context)) { AppSheet.openMusic(); onOpen(Screen.Home) }
                else Launch.music(context, null)
            }
            // Telefon kartı Pano'da açılır (kendi telefon ekranımız).
            BarApp(PanoIcons.Phone, "Telefon", compact = true) { AppSheet.openPhone(); onOpen(Screen.Home) }
            BarApp(PanoIcons.Fan, "Klima", active = climateOpen, compact = true) { onClimate() }
        }
        if (climate.dualZone) {
            BarTemp(
                "Yolcu", climate.passenger, climate.step, climate.isOff,
                onDown = { climate.changePassenger(-0.5f) }, onUp = { climate.changePassenger(0.5f) },
                onOpen = onClimate, compact = true,
            )
        }
        Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(36.dp).background(edge))
        VolumeControl(volume, compact = true)
    }
}

/** Kartın boş yerine dokunmak dışarı dokunmak sayılmasın. */
private fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/**
 * Şeridin üstünde açılan kompakt klima kartı: iki bölge sıcaklığı, fan, Otomatik, A/C ve hava yönü.
 * Tüm kontroller 72 dp. Durum Pano'daki tekil [ClimateState]; değişiklik araca da gider.
 */
@Composable
private fun OverlayClimateCard(climate: ClimateState, onClose: () -> Unit) {
    val c = LocalPano.current
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().height(popupHeight(OverlayPopup.Climate) - 24.dp).swallowTaps()
            .clip(shape).background(c.card).border(2.dp, overlayEdgeColor(c), shape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoneTemp("Sürücü", climate.driver, climate.step, climate.isOff, { climate.changeDriver(-0.5f) }, { climate.changeDriver(0.5f) })
        CardDivider()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(PanoIcons.Minus, "Fanı azalt", { climate.setFanLevel(climate.fan - 1) }, c.raised, c.text, size = 72.dp, iconSize = 26.dp)
                Row(
                    Modifier.weight(1f).height(56.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (level in 1..7) {
                        Box(
                            Modifier.weight(1f).height((14 + level * 6f).dp).clip(RoundedCornerShape(6.dp))
                                .background(if (level <= climate.fan) activeColor() else c.raised)
                                .clickable(role = Role.Button) { climate.setFanLevel(level) },
                        )
                    }
                }
                RoundIconButton(PanoIcons.Plus, "Fanı artır", { climate.setFanLevel(climate.fan + 1) }, c.raised, c.text, size = 72.dp, iconSize = 26.dp)
                Text(
                    when {
                        climate.isOff -> "Kapalı"
                        else -> "Fan ${climate.fan}"
                    },
                    color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    textAlign = TextAlign.End, modifier = Modifier.width(104.dp),
                    style = tabular(),
                )
            }
            val air = app.pano.launcher.vehicle.AirMode.of(climate.airMode) ?: app.pano.launcher.vehicle.AirMode.FaceFeet
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ClimateChip("Otomatik", null, climate.auto, Modifier.weight(1.3f)) { climate.auto = !climate.auto }
                ClimateChip("A/C", PanoIcons.Snow, climate.ac, Modifier.weight(1f)) { climate.ac = !climate.ac }
                listOf(
                    Triple(app.pano.launcher.vehicle.AirMode.Outlet.Face, "Yüz", PanoIcons.AirFace),
                    Triple(app.pano.launcher.vehicle.AirMode.Outlet.Feet, "Ayak", PanoIcons.AirFeet),
                    Triple(app.pano.launcher.vehicle.AirMode.Outlet.Windshield, "Cam", PanoIcons.Defrost),
                ).forEach { (outlet, label, icon) ->
                    ClimateChip(label, icon, air.has(outlet), Modifier.weight(1f)) {
                        // BYD'de yön seçmek otomatik klimadan çıkarır (Klima kartındaki gibi).
                        climate.airMode = air.toggle(outlet, climate.airModeCount).code
                        climate.auto = false
                    }
                }
            }
        }
        CardDivider()
        if (climate.dualZone) {
            ZoneTemp("Yolcu", climate.passenger, climate.step, climate.isOff, { climate.changePassenger(-0.5f) }, { climate.changePassenger(0.5f) })
        }
        Column(
            Modifier.padding(start = 12.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundIconButton(PanoIcons.Close, "Klimayı kapat", onClose, c.raised, c.text, size = 72.dp, iconSize = 24.dp)
            // Araca bağlı değilken ayarlar yalnızca Pano'da kalır; uydurma "gönderildi" izlenimi verme.
            if (!climate.linked) Text("Önizleme", color = c.secondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CardDivider() {
    Box(Modifier.padding(horizontal = 18.dp).width(1.dp).fillMaxHeight(0.8f).background(LocalPano.current.hairline))
}

/** Bir bölgenin sıcaklığı: üstte ad, altta − sayı +. */
@Composable
private fun ZoneTemp(who: String, value: Float, step: Float, off: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    val c = LocalPano.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(who, color = c.secondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(PanoIcons.Minus, "$who sıcaklığını düşür", onDown, c.raised, c.text, size = 72.dp, iconSize = 26.dp)
            Text(
                if (off) "Kapalı" else barTempText(value, step), color = c.text,
                fontSize = if (off) 26.sp else 46.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp,
                textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(150.dp),
                style = tabular(),
            )
            RoundIconButton(PanoIcons.Plus, "$who sıcaklığını artır", onUp, c.raised, c.text, size = 72.dp, iconSize = 26.dp)
        }
    }
}

/** Klima kartındaki aç/kapa: açıkken vurgu renginde dolu. */
@Composable
private fun ClimateChip(label: String, icon: ImageVector?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPano.current
    val fg = if (on) onActiveColor() else c.text
    Row(
        modifier.height(72.dp).clip(RoundedCornerShape(20.dp)).background(if (on) activeColor() else c.raised)
            .clickable(role = Role.Switch, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = "$label ${if (on) "açık" else "kapalı"}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp).padding(end = 0.dp))
        Text(
            label, color = fg, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp),
        )
    }
}

/** Ses göstergesi son dokunuştan bu kadar sonra kaybolur. */
private const val VolumeHudMs = 2_500L

/**
 * Medya sesinin durumu. Alt çubuktaki düğmeler ve göstergesi aynı durumu paylaşır.
 * Sistem ses paneli hiçbir yerde açılmaz (FLAG_SHOW_UI yok); yerine Ufuk'un kendi göstergesi çıkar.
 */
private class UfukVolume(private val context: Context, private val audio: AudioManager?) {
    val max: Int = (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1).coerceAtLeast(1)
    var level by mutableIntStateOf(audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0)
        private set
    // BYD'de direksiyon tekeri kendi genel sessizini kullanıyor ([BydMute]); ikisinden biri sessizse sessiz.
    var muted by mutableStateOf(audio?.isStreamMute(AudioManager.STREAM_MUSIC) == true || app.pano.launcher.system.BydMute.muted.value)
        private set
    /** Son dokunuşun zamanı; değişince gösterge açılır ve süresi baştan başlar. */
    var touchedAt by mutableLongStateOf(0L)
        private set

    /** Ses başka yerden (direksiyon, sistem) değişince değerleri tazeler; göstergeyi açmaz. */
    fun refresh() {
        val a = audio ?: return
        level = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        muted = a.isStreamMute(AudioManager.STREAM_MUSIC) || app.pano.launcher.system.BydMute.muted.value
    }

    fun adjust(direction: Int) {
        app.pano.launcher.system.VolumeHudOverlay.markOwnChange()
        // Sessiz düğmesi BYD'nin genel sessizini kullanır (direksiyon tekeriyle aynı durum); yoksa Android'inki.
        val bydMute = direction == AudioManager.ADJUST_TOGGLE_MUTE &&
            app.pano.launcher.system.BydMute.set(context, !muted)
        if (!bydMute) runCatching { audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        refresh()
        touchedAt = SystemClock.uptimeMillis()
    }

    fun set(value: Int) {
        val v = value.coerceIn(0, max)
        if (v != level || muted) {
            app.pano.launcher.system.VolumeHudOverlay.markOwnChange()
            runCatching {
                if (muted && v > 0) {
                    app.pano.launcher.system.BydMute.set(context, false)
                    audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
                audio?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            }
            refresh()
        }
        touchedAt = SystemClock.uptimeMillis()
    }
}

@Composable
private fun rememberUfukVolume(): UfukVolume {
    val context = LocalContext.current
    val volume = remember { UfukVolume(context.applicationContext, context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager) }
    // Direksiyon tekeriyle sessize alma/açma da alt çubuğa yansısın.
    val bydMuted by app.pano.launcher.system.BydMute.muted.collectAsState()
    LaunchedEffect(bydMuted) { volume.refresh() }
    // Direksiyondan ya da başka uygulamadan değişen ses de alt çubuğa yansısın.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) = volume.refresh()
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply {
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return volume
}

/** Ses: − / hoparlör (sessize al) / +. Medya ses akışını değiştirir. */
@Composable
private fun VolumeControl(volume: UfukVolume, compact: Boolean = false) {
    val c = LocalPano.current
    val button = if (compact) 64.dp else 72.dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(PanoIcons.Minus, "Sesi kıs", { volume.adjust(AudioManager.ADJUST_LOWER) }, Color.Transparent, c.secondary, size = button, iconSize = 26.dp)
        Box(
            Modifier.size(button).clip(CircleShape)
                .clickable(role = Role.Button) { volume.adjust(AudioManager.ADJUST_TOGGLE_MUTE) }
                .semantics { contentDescription = if (volume.muted) "Sesi aç" else "Sessize al" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (volume.muted || volume.level == 0) PanoIcons.SpeakerOff else PanoIcons.Speaker, contentDescription = null,
                tint = if (volume.muted) Accent.Red else c.text, modifier = Modifier.size(if (compact) 28.dp else 30.dp),
            )
        }
        RoundIconButton(PanoIcons.Plus, "Sesi aç", { volume.adjust(AudioManager.ADJUST_RAISE) }, Color.Transparent, c.secondary, size = button, iconSize = 26.dp)
    }
}

/**
 * Ses göstergesi: haritanın sağ altında, ses düğmelerinin hemen üstünde açılan opak hap.
 * Son dokunuştan [VolumeHudMs] sonra kaybolur.
 */
@Composable
private fun VolumeHud(volume: UfukVolume, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(volume.touchedAt) {
        if (volume.touchedAt == 0L) return@LaunchedEffect
        visible = true
        delay(VolumeHudMs)
        visible = false
    }
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(UfukMotionMs / 2)) + slideInVertically(tween(UfukMotionMs)) { it / 3 },
        exit = fadeOut(tween(UfukMotionMs)) + slideOutVertically(tween(UfukMotionMs)) { it / 3 },
        modifier = modifier,
    ) {
        VolumeHudBody(volume)
    }
}

/**
 * Ses göstergesinin kendisi: hoparlör, sürüklenebilir çubuk ve seviye. Çubuğa dokunmak ya da
 * sürüklemek sesi ayarlar. Tek renk; yalnızca sessizde kırmızı.
 */
@Composable
private fun VolumeHudBody(volume: UfukVolume, modifier: Modifier = Modifier, edge: Color? = null) {
    val c = LocalPano.current
    val silent = volume.muted || volume.level == 0
    val fraction = if (volume.muted) 0f else volume.level / volume.max.toFloat()
    val shown by animateFloatAsState(fraction, tween(UfukMotionMs / 2), label = "ses-seviyesi")
    val shape = RoundedCornerShape(44.dp)
    Row(
        modifier.height(88.dp).clip(shape).background(c.card)
            .then(if (edge != null) Modifier.border(2.dp, edge, shape) else Modifier.border(1.dp, c.hairline, shape))
            .padding(start = 26.dp, end = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (silent) PanoIcons.SpeakerOff else PanoIcons.Speaker, contentDescription = null,
            tint = if (volume.muted) Accent.Red else c.text, modifier = Modifier.size(30.dp),
        )
        // Dokunma alanı çubuktan yüksek (88 dp); görünen çubuk ince.
        BoxWithConstraints(
            Modifier.padding(horizontal = 22.dp).width(360.dp).fillMaxHeight()
                .semantics { contentDescription = "Ses seviyesi" }
                .pointerInput(volume.max) {
                    fun at(x: Float) = volume.set(Math.round(x / size.width * volume.max))
                    detectTapGestures { at(it.x) }
                }
                .pointerInput(volume.max) {
                    fun at(x: Float) = volume.set(Math.round(x / size.width * volume.max))
                    detectHorizontalDragGestures(onDragStart = { at(it.x) }) { change, _ -> at(change.position.x) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.raised)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(RoundedCornerShape(6.dp)).background(c.text))
            }
            // Tutamak: dolu kısmın ucunda.
            Box(
                Modifier.offset(x = (maxWidth - 28.dp) * shown).size(28.dp).clip(CircleShape)
                    .background(c.text).border(3.dp, c.card, CircleShape),
            )
        }
        Text(
            if (volume.muted) "Sessiz" else volume.level.toString(),
            color = if (volume.muted) Accent.Red else c.text,
            fontSize = if (volume.muted) 22.sp else 30.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(84.dp),
            style = tabular(),
        )
    }
}
