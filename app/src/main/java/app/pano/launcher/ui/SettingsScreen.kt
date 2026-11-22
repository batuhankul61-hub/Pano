package app.pano.launcher.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.pano.launcher.settings.MapStyle
import app.pano.launcher.settings.Settings
import app.pano.launcher.settings.ThemeMode
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano

private const val VERSION = "0.1.0"

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val style = LocalStyle.current
    val context = LocalContext.current

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        ScreenHeader("Ayarlar", onBack)

        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Sol sütun: genel ayarlar ve geliştirici modu anahtarı
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Group("Genel") {
                    LinkRow("Android ayarları", "Wi-Fi, Bluetooth, ses, ekran") { Launch.settings(context) }
                    Divider()
                    LinkRow("Varsayılan ana ekran", "Pano'yu ana ekran olarak seç") {
                        Launch.intent(context, Intent(AndroidSettings.ACTION_HOME_SETTINGS))
                    }
                    Divider()
                    LinkRow(
                        "Bildirim erişimi",
                        if (MediaHub.hasAccess(context)) "Açık · çalan şarkı gösteriliyor" else "Kapalı · çalan şarkı görünmez",
                    ) { Launch.notificationAccess(context) }
                    Divider()
                    SwitchRowSetting(
                        "Mesaj bildirimlerini göster",
                        "WhatsApp ve SMS mesajları ekranın üstünde kısa süre görünür",
                        style.showMessages,
                    ) { on -> Settings.update { it.copy(showMessages = on) } }
                    Divider()
                    SwitchRowSetting(
                        "Sistem çubuklarını gizle",
                        if (app.pano.launcher.system.SystemBars.canApplyGlobal(context)) "Üst ve alt çubuk her yerde gizli; görmek için ekran kenarından kaydır"
                        else "Yalnızca Pano'da. Uygulamalarda da gizlemek için bir kez: adb shell pm grant app.pano.launcher android.permission.WRITE_SECURE_SETTINGS",
                        style.hideSystemBar,
                    ) { on -> Settings.update { it.copy(hideSystemBar = on) } }
                }

                Group("Pano Asistan") { AssistantKeySettings() }

                Group("Geliştirici") {
                    SwitchRowSetting(
                        "Geliştirici modu",
                        "Görünümü değiştirmek için stil seçeneklerini açar",
                        style.developerMode,
                    ) { on -> Settings.update { it.copy(developerMode = on) } }
                    Divider()
                    SwitchRowSetting(
                        "Sürüşte kısıtla",
                        "Araç hareket ederken kartları gizler, Uygulamalar/Ayarlar ve videoyu kilitler",
                        style.drivingLock,
                    ) { on -> Settings.update { it.copy(drivingLock = on) } }
                }

                Text(
                    "Pano $VERSION",
                    color = LocalPano.current.tertiary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // Sağ sütun: stil seçenekleri (sadece geliştirici modunda)
            Column(
                Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (style.developerMode) {
                    Group("Stil") {
                        OptionRow("Arayüz") {
                            Segmented(LayoutStyle.entries.map { it.label }, style.layout.ordinal, { i ->
                                Settings.update { it.copy(layout = LayoutStyle.entries[i]) }
                            }, Modifier.width(440.dp))
                        }
                        Divider()
                        OptionRow("Tema") {
                            Segmented(ThemeMode.entries.map { it.label }, style.theme.ordinal, { i ->
                                Settings.update { it.copy(theme = ThemeMode.entries[i]) }
                            }, Modifier.width(440.dp))
                        }
                        Divider()
                        OptionRow("Dock ikonları") {
                            Segmented(DockStyle.entries.map { it.label }, style.dock.ordinal, { i ->
                                Settings.update { it.copy(dock = DockStyle.entries[i]) }
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
                        Divider()
                        OptionRow("Gündüz haritası") {
                            Segmented(MapStyle.entries.map { it.label }, style.map.ordinal, { i ->
                                Settings.update { it.copy(map = MapStyle.entries[i]) }
                            }, Modifier.width(440.dp))
                        }
                    }

                    Box(
                        Modifier.fillMaxWidth().height(64.dp)
                            .surface(RoundedCornerShape(22.dp), LocalPano.current.card, LocalPano.current.isDark, LocalPano.current.hairline)
                            .clickable(role = Role.Button) { Settings.resetStyles() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Varsayılan stile dön", color = Accent.Red, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    PanoCard(Modifier.fillMaxWidth(), padding = 32.dp) {
                        Text("Stil seçenekleri kapalı", color = LocalPano.current.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Arayüzü (Klasik / CarPlay), temayı, dock ikonlarını, vurgu rengini, yazı tipini ve harita görünümünü değiştirmek için geliştirici modunu aç. " +
                                "Bir şey ters giderse buradan tek dokunuşla varsayılan stile dönebilirsin.",
                            color = LocalPano.current.secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
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
            else "Anahtar yok. aistudio.google.com → Get API key ile al ve buraya yapıştır.",
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
            SmallButton("Kaydet", enabled = draft.isNotEmpty(), color = colors.text) {
                AssistantKey.set(context, draft)
                focus.clearFocus()
                draft = ""
            }
            if (hasKey) SmallButton("Sil", enabled = true, color = Accent.Red) { AssistantKey.clear(context) }
        }
        Text(
            "Ses yalnızca düğmeye basınca açılır ve Google'a (Gemini Live) gider; konuşma kaydı tutulmaz. " +
                "Kullanım Google hesabına ücretlendirilir. Anahtar bu cihazda şifreli saklanır.",
            color = colors.secondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

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

@Composable
private fun OptionRow(title: String, control: @Composable () -> Unit) {
    val colors = LocalPano.current
    Row(
        Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Etiket sabit genişlikte; kontrol kalan alana sığar (sağ ekran gibi dar alanlarda ezilmez).
        Text(title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.width(130.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { control() }
    }
}

/** Vurgu rengi için yuvarlak renk örnekleri. Seçili olanın etrafında halka var. */
@Composable
private fun AccentPicker(selected: AccentChoice) {
    val colors = LocalPano.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
