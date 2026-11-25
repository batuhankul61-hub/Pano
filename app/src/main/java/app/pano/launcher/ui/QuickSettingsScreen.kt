package app.pano.launcher.ui

import android.content.Context
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.pano.launcher.settings.Settings
import app.pano.launcher.settings.ThemeMode
import app.pano.launcher.system.QuickControls
import app.pano.launcher.system.ScreenOff
import app.pano.launcher.ui.theme.LocalPano
import app.pano.launcher.settings.LocalStyle
import app.pano.launcher.ui.theme.activeColor
import app.pano.launcher.ui.theme.onActiveColor
import app.pano.launcher.vehicle.BydBus
import app.pano.launcher.vehicle.BydComfort
import app.pano.launcher.vehicle.VehicleHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Ufuk'un hızlı ayarlar kartı (saat/hava kutusuna dokununca açılır). BYD'nin üst çubuktan açılan
 * kendi menüsünün (SystemUI `BydQSBar`) Pano içindeki karşılığı.
 *
 * Yalnızca bu cihazda gerçekten okunabilen ya da yapılabilen öğeler çizilir: parlaklık yazılamıyorsa
 * sürgü yok, araç sinyali okunamıyorsa araç bölümü yok. Araca komutlar (koltuk, direksiyon)
 * "Araca komut gönder" ayarı açık ve araç park halindeyken gider; değilse durum yine görünür.
 */

/** Panelde gösterilen her şeyin anlık görüntüsü; arka planda saniyede bir tazelenir. */
private data class QuickSnapshot(
    val brightness: Int? = null,
    val brightnessMin: Int = 1,
    val brightnessMax: Int = QuickControls.BRIGHTNESS_MAX,
    val canBrightness: Boolean = false,
    val bluetooth: QuickControls.Bluetooth? = null,
    val wifi: QuickControls.Wifi? = null,
    val hasBtSettings: Boolean = false,
    val hasWifiSettings: Boolean = false,
    val canScreenOff: Boolean = false,
    val comfort: BydComfort.State = BydComfort.State(),
)

private fun snapshot(context: Context): QuickSnapshot = QuickSnapshot(
    brightness = QuickControls.brightness(context),
    brightnessMin = QuickControls.minBrightness(context),
    brightnessMax = QuickControls.maxBrightness(context),
    canBrightness = QuickControls.canWriteBrightness(context),
    bluetooth = QuickControls.bluetooth(context),
    wifi = QuickControls.wifi(context),
    hasBtSettings = QuickControls.hasSettings(context, AndroidSettings.ACTION_BLUETOOTH_SETTINGS),
    hasWifiSettings = QuickControls.hasSettings(context, AndroidSettings.ACTION_WIFI_SETTINGS),
    canScreenOff = ScreenOff.available(context),
    comfort = BydComfort.read(),
)

private const val QuickPollMs = 1_000L

/** Dokunuştan sonra aracın yeni değeri bildirmesi için beklenen süre (bu sürede okunan eski değer yok sayılır). */
private const val QuickSettleMs = 2_500L

@Composable
fun QuickSettingsScreen(modifier: Modifier = Modifier, onClose: () -> Unit) {
    val c = LocalPano.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf<QuickSnapshot?>(null) }
    var touchedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val s = withContext(Dispatchers.IO) { snapshot(context) }
            if (System.currentTimeMillis() - touchedAt > QuickSettleMs) snap = s
            delay(QuickPollMs)
        }
    }
    val s = snap ?: return
    val vehicle by VehicleHub.state.collectAsState()
    val driving by VehicleHub.isDriving.collectAsState()
    val canCommand = LocalStyle.current.vehicleCommands && BydBus.isAvailable && !driving

    /** Araca yazar, yerel durumu hemen günceller (araç onaylayınca yoklama düzeltir). */
    fun command(update: (BydComfort.State) -> BydComfort.State, send: () -> Int?) {
        touchedAt = System.currentTimeMillis()
        snap = s.copy(comfort = update(s.comfort))
        scope.launch(Dispatchers.IO) { send() }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Ekran ──
        if (s.canBrightness && s.brightness != null) {
            BrightnessRow(s.brightness, s.brightnessMin, s.brightnessMax) { value ->
                touchedAt = System.currentTimeMillis()
                snap = s.copy(brightness = value)
                QuickControls.setBrightness(context, value)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            QuickCard(Modifier.weight(2f)) {
                CardLabel(PanoIcons.Moon, "Tema", modifier = Modifier.padding(bottom = 12.dp))
                val theme = LocalStyle.current.theme
                QuickSegmented(ThemeMode.entries.map { it.label }, theme.ordinal, enabled = true) { i ->
                    Settings.update { it.copy(theme = ThemeMode.entries[i]) }
                }
            }
            if (s.canScreenOff) {
                QuickTile(
                    PanoIcons.Power, "Ekranı kapat", "Dokununca açılır", Modifier.weight(1f),
                ) {
                    onClose()
                    ScreenOff.turnOff(context)
                }
            }
        }

        // ── Bağlantı ──
        if (s.bluetooth != null || s.wifi != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                s.bluetooth?.let { bt ->
                    QuickTile(
                        PanoIcons.Bluetooth, "Bluetooth",
                        when {
                            !bt.on -> "Kapalı"
                            bt.connected != null -> bt.connected
                            else -> "Açık"
                        },
                        Modifier.weight(1f), on = bt.on, chevron = s.hasBtSettings,
                        onClick = if (s.hasBtSettings) {
                            { QuickControls.openSettings(context, AndroidSettings.ACTION_BLUETOOTH_SETTINGS) }
                        } else null,
                    )
                }
                s.wifi?.let { w ->
                    QuickTile(
                        PanoIcons.Wifi, "Wi-Fi",
                        when {
                            !w.on -> "Kapalı"
                            w.network != null -> w.network
                            w.connected -> "Bağlı"
                            else -> "Açık, bağlı değil"
                        },
                        Modifier.weight(1f), on = w.on, chevron = s.hasWifiSettings,
                        onClick = if (s.hasWifiSettings) {
                            { QuickControls.openSettings(context, AndroidSettings.ACTION_WIFI_SETTINGS) }
                        } else null,
                    )
                }
            }
        }

        // ── Araç (yalnızca okunabilen sinyaller) ──
        val locked = vehicle.locked.takeUnless { vehicle.isPreview }
        val comfort = s.comfort
        if (locked != null || !comfort.isEmpty) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text("Araç", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!comfort.isEmpty && !canCommand) {
                    Text(
                        if (driving) "Sürüşte yalnızca durum gösterilir" else "Değiştirmek için: Ayarlar › Araca komut gönder",
                        color = c.secondary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                locked?.let {
                    QuickTile(
                        if (it) PanoIcons.Lock else PanoIcons.Unlock, "Kapılar",
                        if (it) "Kilitli" else "Kilit açık", Modifier.weight(1f),
                    )
                }
                comfort.steeringHeat?.let { on ->
                    QuickTile(
                        PanoIcons.SteeringWheel, "Direksiyon ısıtma", if (on) "Açık" else "Kapalı",
                        Modifier.weight(1f), on = on,
                        onClick = if (canCommand) {
                            { command({ st -> st.copy(steeringHeat = !on) }) { BydComfort.setSteeringHeat(!on) } }
                        } else null,
                    )
                }
            }
            for (seat in BydComfort.Seat.entries) {
                val st = comfort.seat(seat)
                if (st.heat == null && st.vent == null) continue
                QuickCard(Modifier.fillMaxWidth()) {
                    Text("${seat.label} koltuğu", color = c.secondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        st.heat?.let { level ->
                            SeatLevels(PanoIcons.Heat, "Isıtma", level, canCommand, Modifier.weight(1f)) { new ->
                                command({ x -> x.withSeat(seat) { it.copy(heat = new) } }) { BydComfort.setSeatHeat(seat, new) }
                            }
                        }
                        st.vent?.let { level ->
                            SeatLevels(PanoIcons.Fan, "Havalandırma", level, canCommand, Modifier.weight(1f)) { new ->
                                command({ x -> x.withSeat(seat) { it.copy(vent = new) } }) { BydComfort.setSeatVent(seat, new) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun BydComfort.State.withSeat(seat: BydComfort.Seat, f: (BydComfort.SeatState) -> BydComfort.SeatState) =
    if (seat == BydComfort.Seat.Driver) copy(driver = f(driver)) else copy(passenger = f(passenger))

/** Isıtma / havalandırma kademesi: Kapalı, Düşük, Yüksek (aracın bildirdiği 3. kademe varsa o da). */
@Composable
private fun SeatLevels(icon: ImageVector, label: String, level: Int, enabled: Boolean, modifier: Modifier, onSelect: (Int) -> Unit) {
    Column(modifier) {
        CardLabel(icon, label, modifier = Modifier.padding(bottom = 10.dp))
        val options = listOf("Kapalı", "Düşük", "Yüksek") + if (level >= 3) listOf("En yüksek") else emptyList()
        QuickSegmented(options, level, enabled, onSelect)
    }
}

@Composable
private fun QuickCard(modifier: Modifier, content: @Composable () -> Unit) {
    val c = LocalPano.current
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(c.raised).padding(horizontal = 22.dp, vertical = 18.dp),
    ) { content() }
}

/**
 * Durum karosu: ikon, başlık, altta durum. [onClick] yoksa yalnızca gösterir. [on] açıkken ikon
 * vurgu renginde dolu dairede.
 */
@Composable
private fun QuickTile(
    icon: ImageVector,
    title: String,
    status: String,
    modifier: Modifier,
    on: Boolean? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalPano.current
    Row(
        modifier.height(112.dp).clip(RoundedCornerShape(24.dp)).background(c.raised)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "$title: $status" }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(if (on == true) activeColor() else c.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (on == true) onActiveColor() else c.text, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = c.secondary, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (chevron) Icon(PanoIcons.ChevronRight, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(24.dp))
    }
}

/** Segment kontrolü (72 dp, sürüşte dokunulabilir); kapalıyken yalnızca seçili değeri gösterir. */
@Composable
private fun QuickSegmented(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val c = LocalPano.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (sel) activeColor() else Color.Transparent)
                    .then(if (enabled && !sel) Modifier.clickable(role = Role.RadioButton) { onSelect(i) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (sel) onActiveColor() else if (enabled) c.text else c.tertiary,
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Parlaklık sürgüsü: dokun ya da sürükle. Yüzde BYD'nin sürgüsü gibi min–maks aralığına göre. */
@Composable
private fun BrightnessRow(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val c = LocalPano.current
    var percent by remember { mutableIntStateOf(QuickControls.percentOf(value, min, max)) }
    LaunchedEffect(value) { percent = QuickControls.percentOf(value, min, max) }
    val shown by animateFloatAsState(percent / 100f, tween(120), label = "parlaklık")
    val shape = RoundedCornerShape(24.dp)
    fun set(p: Int) {
        val np = p.coerceIn(0, 100)
        if (np == percent) return
        percent = np
        onChange(QuickControls.valueOf(np, min, max))
    }
    Row(
        Modifier.fillMaxWidth().height(112.dp).clip(shape).background(c.raised).padding(start = 24.dp, end = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PanoIcons.Sun, contentDescription = null, tint = c.text, modifier = Modifier.size(32.dp))
        Text("Parlaklık", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp).width(110.dp))
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight().padding(horizontal = 18.dp)
                .semantics { contentDescription = "Ekran parlaklığı yüzde $percent" }
                .pointerInput(min, max) { detectTapGestures { set(Math.round(it.x / size.width * 100)) } }
                .pointerInput(min, max) {
                    detectHorizontalDragGestures(onDragStart = { set(Math.round(it.x / size.width * 100)) }) { change, _ ->
                        change.consume()
                        set(Math.round(change.position.x / size.width * 100))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.card)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(RoundedCornerShape(7.dp)).background(c.text))
            }
            Box(
                Modifier.offset(x = (maxWidth - 32.dp) * shown).size(32.dp).clip(CircleShape)
                    .background(c.text).border(3.dp, c.raised, CircleShape),
            )
        }
        Text(
            "%$percent", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End, modifier = Modifier.width(76.dp),
        )
    }
}

