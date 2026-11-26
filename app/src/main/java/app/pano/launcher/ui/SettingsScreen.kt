package app.pano.launcher.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.pano.launcher.Launch
import app.pano.launcher.assistant.AssistantKey
import app.pano.launcher.media.MediaHub
import app.pano.launcher.settings.AccentChoice
import app.pano.launcher.settings.DockStyle
import app.pano.launcher.settings.FontChoice
import app.pano.launcher.settings.LayoutStyle
import app.pano.launcher.settings.LocalStyle
import app.pano.launcher.settings.MapContrast
import app.pano.launcher.settings.MapNightStyle
import app.pano.launcher.settings.MapStyle
import app.pano.launcher.settings.MapTextSize
import app.pano.launcher.settings.RouteColor
import app.pano.launcher.settings.Settings
import app.pano.launcher.settings.ThemeMode
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano
import app.pano.launcher.ui.theme.linkColor

private val VERSION get() = "${app.pano.launcher.update.Updater.currentName} (${app.pano.launcher.update.Updater.currentCode})"

/** Ayarlar ekranının bölümleri. Soldaki listede bu sırayla görünür. */
private enum class SettingsSection(val title: String, val icon: ImageVector, val tile: Color) {
    General("Genel", PanoIcons.Home, Color(0xFF8E8E93)),
    Notifications("Bildirimler", PanoIcons.Message, Color(0xFFFF453A)),
    Appearance("Görünüm", PanoIcons.Sun, Color(0xFF0A84FF)),
    Driving("Sürüş ve araç", PanoIcons.Car, Color(0xFF30D158)),
    Assistant("Pano Asistan", PanoIcons.Assistant, Color(0xFFBF5AF2)),
    Update("Güncelleme", PanoIcons.Sync, Color(0xFF64D2FF)),
    Developer("Geliştirici", PanoIcons.Bolt, Color(0xFFFF9F0A)),
}

/**
 * Solda bölüm listesi, sağda seçili bölümün ayarları. Klasik düzende tam ekran, CarPlay'de
 * sağ ekranda (daha dar) açılır; liste genişliği alana göre ayarlanır.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.General) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        ScreenHeader("Ayarlar", onBack)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val navWidth = if (maxWidth > 1000.dp) 300.dp else 214.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                SectionList(section, { section = it }, Modifier.width(navWidth).fillMaxHeight())

                // Her bölüm kendi kaydırma durumuyla açılır (bölüm değişince en üstten başlar).
                key(section) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        when (section) {
                            SettingsSection.General -> GeneralSection()
                            SettingsSection.Notifications -> NotificationSection()
                            SettingsSection.Appearance -> AppearanceSection { section = SettingsSection.Developer }
                            SettingsSection.Driving -> DrivingSection()
                            SettingsSection.Assistant -> {
                                Group("Gemini Live") {
                                    AssistantKeySettings()
                                    Divider()
                                    VoiceKeySetting()
                                }
                                Group("Konuşma günlüğü") { AssistantLogSettings() }
                            }
                            SettingsSection.Update -> Group("Pano güncellemesi") { UpdateSettings() }
                            SettingsSection.Developer -> DeveloperSection()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionList(selected: SettingsSection, onSelect: (SettingsSection) -> Unit, modifier: Modifier) {
    val colors = LocalPano.current
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsSection.entries.forEach { s ->
            val isSel = s == selected
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier.fillMaxWidth().height(64.dp)
                    .then(if (isSel) Modifier.surface(shape, colors.card, colors.isDark, colors.hairline, elevation = 8.dp) else Modifier.clip(shape))
                    .clickable(role = Role.Tab) { onSelect(s) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(s.tile),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(s.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Text(
                    s.title, color = if (isSel) colors.text else colors.secondary,
                    fontSize = 18.sp, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
        Text(
            "Pano $VERSION",
            color = colors.tertiary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 14.dp, top = 12.dp),
        )
    }
}

@Composable
private fun GeneralSection() {
    val style = LocalStyle.current
    val context = LocalContext.current
    // Hızlı ayarlar kartının aynısı (parlaklık, tema, ekranı kapat, bağlantılar, koltuk/direksiyon).
    Column {
        Text(
            "Hızlı ayarlar", color = LocalPano.current.secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
        )
        QuickSettingsScreen(scroll = false, onClose = {})
    }
    Group("Sistem") {
        LinkRow("Android ayarları", "Wi-Fi, Bluetooth, ses, ekran") { Launch.settings(context) }
        Divider()
        LinkRow("Varsayılan ana ekran", "Pano'yu ana ekran olarak seç") {
            Launch.intent(context, Intent(AndroidSettings.ACTION_HOME_SETTINGS))
        }
    }
    Group("Ekran") {
        SwitchRowSetting(
            "Sistem çubuklarını gizle",
            if (app.pano.launcher.system.SystemBars.canApplyGlobal(context)) "Üst ve alt çubuk her yerde gizli; görmek için ekran kenarından kaydır"
            else "Yalnızca Pano'da. Uygulamalarda da gizlemek için bir kez: adb shell pm grant app.pano.launcher android.permission.WRITE_SECURE_SETTINGS",
            style.hideSystemBar,
        ) { on -> Settings.update { it.copy(hideSystemBar = on) } }
    }
}

@Composable
private fun NotificationSection() {
    val style = LocalStyle.current
    val context = LocalContext.current
    val access = MediaHub.hasAccess(context)
    Group("İzin") {
        // Erişim açıkken ayar ekranına gitmeye gerek yok; BYD o ekranı açmıyor ("IVI sistemi bu işlemi desteklemiyor").
        if (access) InfoRow("Bildirim erişimi", "Açık · çalan şarkı gösteriliyor")
        else LinkRow(
            "Bildirim erişimi",
            "Kapalı · çalan şarkı ve mesajlar görünmez. Ayar ekranı açılmazsa bir kez: " +
                "adb shell cmd notification allow_listener app.pano.launcher/app.pano.launcher.media.MediaListenerService",
        ) { Launch.notificationAccess(context) }
    }
    Group("Mesajlar") {
        SwitchRowSetting(
            "Mesaj bildirimlerini göster",
            "WhatsApp ve SMS mesajları ekranın üstünde kısa süre görünür",
            style.showMessages,
        ) { on -> Settings.update { it.copy(showMessages = on) } }
        Divider()
        val apps = remember { app.pano.launcher.messages.MessageSources.installedApps(context) }
        InfoRow("Mesaj kaynağı", app.pano.launcher.messages.MessageSources.statusText(access, apps))
    }
}

/** Stil seçenekleri yalnızca geliştirici modunda açılır; kapalıyken nasıl açılacağı anlatılır. */
@Composable
private fun AppearanceSection(openDeveloper: () -> Unit) {
    val style = LocalStyle.current
    val colors = LocalPano.current
    if (!style.developerMode) {
        PanoCard(Modifier.fillMaxWidth(), padding = 32.dp) {
            Text("Stil seçenekleri kapalı", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Arayüzü (Klasik / CarPlay), temayı, dock ikonlarını, vurgu rengini, yazı tipini ve harita görünümünü değiştirmek için geliştirici modunu aç. " +
                    "Bir şey ters giderse buradan tek dokunuşla varsayılan stile dönebilirsin.",
                color = colors.secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Box(Modifier.padding(top = 18.dp)) {
                SmallButton("Geliştirici bölümüne git", enabled = true, color = linkColor(), onClick = openDeveloper)
            }
        }
        return
    }
    Group("Düzen") {
        OptionRow("Arayüz") {
            Segmented(LayoutStyle.entries.map { it.label }, style.layout.ordinal, { i ->
                Settings.update { it.copy(layout = LayoutStyle.entries[i]) }
            }, Modifier.width(440.dp))
        }
        Divider()
        OptionRow("Dock ikonları") {
            Segmented(DockStyle.entries.map { it.label }, style.dock.ordinal, { i ->
                Settings.update { it.copy(dock = DockStyle.entries[i]) }
            }, Modifier.width(440.dp))
        }
    }
    Group("Tema ve renk") {
        OptionRow("Tema") {
            Segmented(ThemeMode.entries.map { it.label }, style.theme.ordinal, { i ->
                Settings.update { it.copy(theme = ThemeMode.entries[i]) }
            }, Modifier.width(440.dp))
        }
        Divider()
        OptionRow("Vurgu rengi") { AccentPicker(style.accent) }
        Divider()
        OptionRow("Yazı tipi") {
            Segmented(FontChoice.entries.map { it.label }, style.font.ordinal, { i ->
                Settings.update { it.copy(font = FontChoice.entries[i]) }
            }, Modifier.width(440.dp))
        }
    }
    Group("Harita") {
        OptionRow("Gündüz haritası") {
            Segmented(MapStyle.entries.map { it.label }, style.map.ordinal, { i ->
                Settings.update { it.copy(map = MapStyle.entries[i]) }
            }, Modifier.width(440.dp))
        }
        Divider()
        OptionRow("Gece haritası") {
            Segmented(MapNightStyle.entries.map { it.label }, style.mapNight.ordinal, { i ->
                Settings.update { it.copy(mapNight = MapNightStyle.entries[i]) }
            }, Modifier.width(440.dp))
        }
        Divider()
        OptionRow("Kontrast") {
            Segmented(MapContrast.entries.map { it.label }, style.mapContrast.ordinal, { i ->
                Settings.update { it.copy(mapContrast = MapContrast.entries[i]) }
            }, Modifier.width(440.dp))
        }
        Divider()
        OptionRow("Yazı boyutu") {
            Segmented(MapTextSize.entries.map { it.label }, style.mapText.ordinal, { i ->
                Settings.update { it.copy(mapText = MapTextSize.entries[i]) }
            }, Modifier.width(440.dp))
        }
        Divider()
        OptionRow("Rota rengi") {
            Segmented(RouteColor.entries.map { it.label }, style.routeColor.ordinal, { i ->
                Settings.update { it.copy(routeColor = RouteColor.entries[i]) }
            }, Modifier.width(440.dp))
        }
    }
    Box(
        Modifier.fillMaxWidth().height(64.dp)
            .surface(RoundedCornerShape(22.dp), colors.card, colors.isDark, colors.hairline)
            .clickable(role = Role.Button) { Settings.resetStyles() },
        contentAlignment = Alignment.Center,
    ) {
        Text("Varsayılan stile dön", color = Accent.Red, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DrivingSection() {
    val style = LocalStyle.current
    Group("Sürüş güvenliği") {
        SwitchRowSetting(
            "Sürüşte kısıtla",
            "Araç hareket ederken kartları gizler, Uygulamalar/Ayarlar ve videoyu kilitler",
            style.drivingLock,
        ) { on -> Settings.update { it.copy(drivingLock = on) } }
    }
    Group("Araç komutları") {
        SwitchRowSetting(
            "Araca komut gönder (deneysel)",
            "Sürüş modu ve rejen seviyesini değiştirir; yalnızca park halinde. Araçta henüz denenmedi",
            style.vehicleCommands,
        ) { on -> Settings.update { it.copy(vehicleCommands = on) } }
        if (style.vehicleCommands) DriveCommandRows()
    }
}

@Composable
private fun DeveloperSection() {
    val style = LocalStyle.current
    Group("Geliştirici") {
        SwitchRowSetting(
            "Geliştirici modu",
            "Görünümü değiştirmek için stil seçeneklerini açar (Görünüm bölümü)",
            style.developerMode,
        ) { on -> Settings.update { it.copy(developerMode = on) } }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalPano.current
    Column {
        Text(
            title, color = colors.secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
        )
        Column(
            Modifier.fillMaxWidth().surface(RoundedCornerShape(26.dp), colors.card, colors.isDark, colors.hairline),
            content = content,
        )
    }
}

/**
 * Gemini API anahtarı. Kullanıcı kendisi girer; anahtar yalnızca bu cihazda şifreli saklanır
 * ([AssistantKey]) ve ekranda hiçbir zaman tamamı gösterilmez.
 */
@Composable
private fun AssistantKeySettings() {
    val colors = LocalPano.current
    val context = LocalContext.current
    AssistantKey.load(context)
    val hasKey by AssistantKey.hasKey
    val hint by AssistantKey.hint
    var draft by remember { mutableStateOf("") }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
        Text("Gemini API anahtarı", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(
            if (hasKey) "Kayıtlı ($hint). Dock'taki Pano Asistan düğmesiyle konuş."
            else "Anahtar yok. aistudio.google.com → Get API key ile al, kopyala ve Yapıştır'a bas.",
            color = colors.secondary, fontSize = 15.sp, modifier = Modifier.padding(top = 3.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.trim() },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                textStyle = TextStyle(color = colors.text, fontSize = 18.sp),
                cursorBrush = SolidColor(colors.text),
                modifier = Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(16.dp)).background(colors.raised)
                    .semantics { contentDescription = "Gemini API anahtarı" },
                decorationBox = { field ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) Text(if (hasKey) "Değiştir" else "Anahtarı yapıştır", color = colors.tertiary, fontSize = 18.sp, maxLines = 1)
                        field()
                    }
                },
            )
            // Bazı araç klavyelerinde (BYD) alanın kendi "Yapıştır" menüsü çıkmıyor: panodan doğrudan al.
            if (draft.isEmpty()) SmallButton("Yapıştır", enabled = true, color = colors.text) {
                val key = clipboardText(context)?.filterNot { it.isWhitespace() }.orEmpty()
                when {
                    key.isEmpty() ->
                        android.widget.Toast.makeText(context, "Panoda metin yok. Anahtarı önce bu cihazda kopyala.", android.widget.Toast.LENGTH_LONG).show()
                    else -> {
                        AssistantKey.set(context, key)
                        focus.clearFocus()
                        val warn = if (!key.startsWith("AIza")) " Bu bir Gemini anahtarına benzemiyor, kontrol et." else ""
                        android.widget.Toast.makeText(context, "Anahtar kaydedildi (••••${key.takeLast(4)}).$warn", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            else SmallButton("Kaydet", enabled = true, color = colors.text) {
                AssistantKey.set(context, draft)
                focus.clearFocus()
                draft = ""
            }
            if (hasKey) SmallButton("Sil", enabled = true, color = Accent.Red) { AssistantKey.clear(context) }
        }
        Text(
            "Ses yalnızca düğmeye basınca açılır ve Google'a (Gemini Live) gider. Konuşma günlüğü açık değilse kayıt tutulmaz. " +
                "Kullanım Google hesabına ücretlendirilir. Anahtar bu cihazda şifreli saklanır.",
            color = colors.secondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * Direksiyondaki sesli asistan tuşu Pano Asistan'ı açsın mı. Kapalıyken (ya da API anahtarı yokken)
 * tuş aracın kendi asistanını açar. Ayrıntı: [app.pano.launcher.assistant.VoiceKey].
 */
@Composable
private fun VoiceKeySetting() {
    val context = LocalContext.current
    app.pano.launcher.assistant.VoiceKey.init(context)
    val on by app.pano.launcher.assistant.VoiceKey.enabled
    val hasKey by AssistantKey.hasKey
    SwitchRowSetting(
        "Direksiyondaki sesli asistan tuşu",
        when {
            !on -> "Kapalı: tuş aracın kendi asistanını açar."
            !hasKey -> "Açık, ama API anahtarı girilene kadar tuş aracın kendi asistanını açar."
            else -> "Tuş Pano Asistan'ı açar ve kapatır. Aracın kendi asistanı ekrandaki mikrofon düğmesinden açılır."
        },
        on,
    ) { app.pano.launcher.assistant.VoiceKey.setEnabled(context, it) }
}

/**
 * Konuşma günlüğü: açılıp kapatılır, son konuşmalar burada okunur. Asistanın neden "bilmiyorum /
 * erişimim yok" dediğini görmek için araç çağrılarının sonuçları da yazılır. Yalnızca bu cihazda.
 */
@Composable
private fun AssistantLogSettings() {
    val colors = LocalPano.current
    val context = LocalContext.current
    app.pano.launcher.assistant.ConversationLog.init(context)
    val enabled by app.pano.launcher.assistant.ConversationLog.enabled
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    SwitchRowSetting(
        "Konuşmaları kaydet",
        "Söylediklerin, cevaplar ve asistanın kullandığı araçların sonuçları yalnızca bu cihazda saklanır.",
        enabled,
    ) { app.pano.launcher.assistant.ConversationLog.setEnabled(context, it) }
    Divider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Son konuşmalar", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        SmallButton(if (open) "Gizle" else "Göster", enabled = true, color = colors.text) { open = !open; reload++ }
        SmallButton("Temizle", enabled = true, color = Accent.Red) {
            app.pano.launcher.assistant.ConversationLog.clear { reload++ }
            text = ""
        }
    }
    if (open) {
        // Dosya okuma arka planda; açıkken birkaç saniyede bir tazelenir (yeni oturum düşsün diye).
        androidx.compose.runtime.LaunchedEffect(reload) {
            while (true) {
                text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.pano.launcher.assistant.ConversationLog.tail()
                }
                kotlinx.coroutines.delay(3_000)
            }
        }
        val scroll = rememberScrollState()
        // Yeni satırlar altta: açılınca ve metin değişince en alta in.
        androidx.compose.runtime.LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
        Box(
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 20.dp).fillMaxWidth().height(380.dp)
                .clip(RoundedCornerShape(16.dp)).background(colors.raised)
                .verticalScroll(scroll).padding(16.dp),
        ) {
            Text(
                text?.takeIf { it.isNotBlank() } ?: if (text == null) "Yükleniyor…"
                else if (enabled) "Henüz kayıt yok. Asistanla konuşunca burada görünür."
                else "Kayıt yok. Yukarıdan \"Konuşmaları kaydet\"i aç.",
                color = colors.text, fontSize = 14.sp, lineHeight = 20.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

/** Panodaki düz metin; yoksa ya da okunamazsa null. Değer loglanmaz (API anahtarı olabilir). */
private fun clipboardText(context: android.content.Context): String? = runCatching {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    val clip = cm?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    clip.getItemAt(0).coerceToText(context)?.toString()
}.getOrNull()

@Composable
private fun SmallButton(label: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    val colors = LocalPano.current
    Box(
        Modifier.height(60.dp).clip(RoundedCornerShape(16.dp)).background(colors.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) color else colors.tertiary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 24.dp).fillMaxWidth().height(1.dp).background(LocalPano.current.hairline))
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = LocalPano.current
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = colors.secondary, fontSize = 15.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(PanoIcons.ChevronRight, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(20.dp))
    }
}

/** Dokunulmayan bilgi satırı (durum açıklaması). */
@Composable
private fun InfoRow(title: String, subtitle: String) {
    val colors = LocalPano.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
        Text(title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = colors.secondary, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun SwitchRowSetting(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalPano.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = colors.secondary, fontSize = 15.sp, modifier = Modifier.padding(top = 3.dp))
        }
        PanoSwitch(checked, title, onChange)
    }
}

/**
 * Sürüş modu ve rejen seçimi. Seçili olan araçtan okunan değerdir (okunamıyorsa hiçbiri seçili değil);
 * dokununca araca komut gider. BYD yoksa ya da araç hareket ediyorsa yalnızca sebep yazılır.
 */
@Composable
private fun DriveCommandRows() {
    val v by app.pano.launcher.vehicle.VehicleHub.state.collectAsState()
    val driving by app.pano.launcher.vehicle.VehicleHub.isDriving.collectAsState()
    Divider()
    if (v.isPreview || driving) {
        Text(
            if (v.isPreview) "Araç verisi yok (önizleme); komut gönderilemez" else "Araç hareket halinde; komutlar park halinde açılır",
            color = LocalPano.current.secondary, fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
        )
        return
    }
    val modes = app.pano.launcher.vehicle.DriveMode.entries
    OptionRow("Sürüş modu") {
        Segmented(modes.map { it.label }, modes.indexOf(v.driveMode), { i ->
            app.pano.launcher.vehicle.BydDrive.setMode(modes[i])
        })
    }
    Divider()
    val levels = app.pano.launcher.vehicle.RegenLevel.entries
    OptionRow("Rejen") {
        Segmented(levels.map { it.label }, levels.indexOf(v.regen), { i ->
            app.pano.launcher.vehicle.BydDrive.setRegen(levels[i])
        })
    }
}

@Composable
private fun OptionRow(title: String, control: @Composable () -> Unit) {
    val colors = LocalPano.current
    Row(
        Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Etiket sabit genişlikte; kontrol kalan alana sığar (sağ ekran gibi dar alanlarda ezilmez).
        Text(
            title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.widthIn(min = 130.dp).padding(end = 16.dp),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { control() }
    }
}

/** Vurgu rengi için yuvarlak renk örnekleri. Seçili olanın etrafında halka var. */
@Composable
private fun AccentPicker(selected: AccentChoice) {
    val colors = LocalPano.current
    // Dar alanda (CarPlay sağ ekranı) aralık daralır, örnekler sığar.
    BoxWithConstraints(contentAlignment = Alignment.CenterEnd) {
    val n = AccentChoice.entries.size
    val gap = ((maxWidth - 48.dp * n) / (n - 1)).coerceIn(4.dp, 14.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        AccentChoice.entries.forEach { choice ->
            val isSel = choice == selected
            Box(
                Modifier
                    .size(48.dp)
                    .border(2.5.dp, if (isSel) colors.text else Color.Transparent, CircleShape)
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(choice.color ?: colors.text)
                    .clickable(role = Role.RadioButton) { Settings.update { it.copy(accent = choice) } }
                    .semantics { contentDescription = "Vurgu rengi: ${choice.label}" },
            )
        }
    }
    }
}

/**
 * Pano'nun kendini güncellemesi: "Denetle" ve varsa "Güncelle"; isteğe bağlı GitHub erişim anahtarı
 * (depo herkese açık, anahtarsız da çalışır). Kurulum Android'in yükleyicisiyle olur; kullanıcı onaylar.
 */
@Composable
private fun UpdateSettings() {
    val colors = LocalPano.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val up = app.pano.launcher.update.Updater
    up.token.load(context)
    val hasToken by up.token.hasValue
    val hint by up.token.hint
    val status by up.status.collectAsState()
    var draft by remember { mutableStateOf("") }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

    // Ayarlar açılınca bir kez kendiliğinden denetle.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (status is app.pano.launcher.update.Updater.Status.Idle) up.check(context)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
        Text("Bu sürüm: $VERSION", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        val line = when (val s = status) {
            app.pano.launcher.update.Updater.Status.Idle -> "Denetlenmedi"
            app.pano.launcher.update.Updater.Status.Checking -> "Denetleniyor…"
            app.pano.launcher.update.Updater.Status.UpToDate -> "Güncel"
            is app.pano.launcher.update.Updater.Status.Available -> "Yeni sürüm var: ${s.release.name}"
            is app.pano.launcher.update.Updater.Status.Downloading -> "İndiriliyor… %${(s.progress * 100).toInt()}"
            app.pano.launcher.update.Updater.Status.Installing -> "Yükleniyor… Ekrandaki onayı ver."
            is app.pano.launcher.update.Updater.Status.Failed -> s.message
        }
        Text(line, color = colors.secondary, fontSize = 15.sp, modifier = Modifier.padding(top = 3.dp))

        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = status
            if (s is app.pano.launcher.update.Updater.Status.Available) {
                SmallButton("Güncelle", enabled = true, color = Accent.Green) {
                    scope.launch { up.downloadAndInstall(context, s.release) }
                }
            }
            SmallButton(
                "Denetle",
                enabled = s !is app.pano.launcher.update.Updater.Status.Checking &&
                    s !is app.pano.launcher.update.Updater.Status.Downloading,
                color = colors.text,
            ) { scope.launch { up.check(context) } }
        }
        if (status is app.pano.launcher.update.Updater.Status.Available) {
            val notes = (status as app.pano.launcher.update.Updater.Status.Available).release.notes
            if (notes.isNotBlank()) {
                Text(notes.take(400), color = colors.secondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }

        Text(
            if (hasToken) "GitHub erişim anahtarı kayıtlı ($hint)" else "GitHub erişim anahtarı (isteğe bağlı)",
            color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 20.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.trim() },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                textStyle = TextStyle(color = colors.text, fontSize = 18.sp),
                cursorBrush = SolidColor(colors.text),
                modifier = Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(16.dp)).background(colors.raised)
                    .semantics { contentDescription = "GitHub erişim anahtarı" },
                decorationBox = { field ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) Text(if (hasToken) "Değiştir" else "github_pat_… yapıştır", color = colors.tertiary, fontSize = 18.sp, maxLines = 1)
                        field()
                    }
                },
            )
            SmallButton("Kaydet", enabled = draft.isNotEmpty(), color = colors.text) {
                up.token.set(context, draft)
                focus.clearFocus()
                draft = ""
                scope.launch { up.check(context) }
            }
            if (hasToken) SmallButton("Sil", enabled = true, color = Accent.Red) { up.token.clear(context) }
        }
        Text(
            "Güncelleme anahtarsız çalışır. Yalnızca GitHub sınıra takılırsa ya da depo özel olursa gerekir: " +
                "GitHub → Settings → Developer settings → Fine-grained tokens, yalnızca \"Pano\" deposu, " +
                "Contents: Read-only. Anahtar bu cihazda şifreli saklanır.",
            color = colors.secondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp),
        )
    }
}
