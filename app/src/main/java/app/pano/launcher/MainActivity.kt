package app.pano.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pano.launcher.media.MediaHub
import app.pano.launcher.ui.AppsScreen
import app.pano.launcher.ui.ClimateScreen
import app.pano.launcher.ui.DesignCanvas
import app.pano.launcher.ui.Dock
import app.pano.launcher.ui.HomeScreen
import app.pano.launcher.ui.theme.LocalPano
import app.pano.launcher.settings.LayoutStyle
import app.pano.launcher.settings.LocalStyle
import app.pano.launcher.settings.Settings
import app.pano.launcher.ui.CarPlayApp
import app.pano.launcher.ui.SettingsScreen
import app.pano.launcher.ui.theme.PanoTheme
import app.pano.launcher.vehicle.ClimateState
import app.pano.launcher.weather.WeatherHub

enum class Screen { Home, Climate, Apps, Settings }

class MainActivity : ComponentActivity() {
    private val screen = mutableStateOf(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.load(this)
        setContent {
            PanoTheme(Settings.state.value) {
                PanoApp(screen.value) { screen.value = it }
            }
        }
    }

    /** Ana ekran tuşuna basılınca her zaman ana sayfaya dön. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) screen.value = Screen.Home
    }

    override fun onResume() {
        super.onResume()
        // Kullanıcı bildirim erişimini yeni verdiyse müzik takibi burada başlar.
        MediaHub.start(this)
        // Hava durumu 15 dakikada bir yenilenir; döngü zaten çalışıyorsa bir şey yapmaz.
        WeatherHub.start(this)
        // Araç verisi: BYD veri yolu, yoksa GPS hızı, o da yoksa önizleme.
        app.pano.launcher.vehicle.VehicleHub.start(this)
    }
}

@Composable
fun PanoApp(screen: Screen, onNavigate: (Screen) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val climate = remember { app.pano.launcher.vehicle.ClimateStore.load(context) }

    // Launcher olduğumuz için geri tuşu uygulamadan çıkmaz, ana sayfaya döner.
    BackHandler { onNavigate(Screen.Home) }

    // Geliştirici modunda seçilebilen CarPlay / iOS havasındaki cam arayüz.
    // Harita tüm ekranlar ve iki düzen arasında paylaşılıyor; geçişlerde yeniden yüklenmiyor.
    app.pano.launcher.ui.ProvideMapHolder {
        if (LocalStyle.current.layout == LayoutStyle.CarPlay) {
            CarPlayApp(screen, climate, onNavigate)
        } else {
            ClassicApp(screen, climate, onNavigate)
        }
    }
}

@Composable
private fun ClassicApp(screen: Screen, climate: ClimateState, onNavigate: (Screen) -> Unit) {
    val colors = LocalPano.current
    // Sistem çubukları (durum çubuğu, gezinme çizgisi) içeriğin üstüne binmesin.
    Box(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        DesignCanvas {
            Column(
                Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 26.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.Home -> HomeScreen(climate) { onNavigate(Screen.Climate) }
                        Screen.Climate -> ClimateScreen(climate) { onNavigate(Screen.Home) }
                        Screen.Apps -> AppsScreen { onNavigate(Screen.Home) }
                        Screen.Settings -> SettingsScreen { onNavigate(Screen.Home) }
                    }
                }
                Dock(climate, screen, onNavigate)
            }
        }
    }
}
