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
import app.pano.launcher.ui.returnZoom
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
        // Önceki oturumdan kalan alt boşluğu sıfırla; Pano çökerse de sıfırlansın.
        app.pano.launcher.system.Overscan.installGuard(this)
        // Pencere her zaman tüm ekranı kaplar; sistem çubukları görünüp kaybolunca pencere boyu
        // değişmez (içerik kenar boşluklarını kendisi ayarlar). Sağ paneldeki uygulama penceresi
        // Pano'nun ölçtüğü yerde kalsın diye gerekli.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val style = Settings.state.value
            // Ayar açıksa aracın alt sistem çubuğu gizlenir; alttan kaydırınca kısa süre görünür.
            androidx.compose.runtime.LaunchedEffect(style.hideSystemBar) { applySystemBar(style.hideSystemBar) }
            PanoTheme(style) {
                PanoApp(screen.value) { screen.value = it }
            }
        }
    }

    private fun applySystemBar(hide: Boolean) {
        // Sağ paneldeki uygulamalar ve tam ekran uygulamalar için de (izin verildiyse).
        app.pano.launcher.system.SystemBars.applyGlobal(this, hide)
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        // Üst durum çubuğu ve alt sistem çubuğu birlikte; kenardan kaydırınca kısa süre görünür.
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (hide) {
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(bars)
        } else {
            controller.show(bars)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Başka uygulamadan dönünce sistem çubuğu yeniden görünebiliyor; ayar yeniden uygulanır.
        if (hasFocus && Settings.state.value.hideSystemBar) applySystemBar(true)
        // Sağ paneldeki uygulama, Pano'ya dokunulunca arkaya düşer; gerekirse geri getirilir.
        app.pano.launcher.system.SidePanel.onTopResumed(this, hasFocus)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        app.pano.launcher.system.SidePanel.onTouch(this, ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Ana ekran tuşuna basılınca her zaman ana sayfaya dön. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) screen.value = Screen.Home
        // Uygulamaların üstündeki alt şeritten bir kart istendi (Klima, Uygulamalar…).
        intent.getStringExtra(app.pano.launcher.system.BarOverlay.EXTRA_SCREEN)
            ?.let { name -> Screen.entries.firstOrNull { it.name == name } }
            ?.let { screen.value = it }
    }

    /** Pano öne gelince kendi alt şeridi var; uygulamaların üstündeki kopya kalkar. */
    override fun onStart() {
        super.onStart()
        app.pano.launcher.system.BarOverlay.hide()
    }

    /** Pano arkaya düştü (başka bir uygulama açıldı): alt şerit o uygulamanın üstünde kalır. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) app.pano.launcher.system.BarOverlay.show(this)
    }

    private var lastReturnFor = 0L

    /** Bizim açtığımız bir uygulamadan dönülüyorsa içerik kısa bir yakınlaşmayla gelir. */
    override fun onRestart() {
        super.onRestart()
        if (Launch.lastLaunchAt != 0L && Launch.lastLaunchAt != lastReturnFor) {
            lastReturnFor = Launch.lastLaunchAt
            app.pano.launcher.ui.ReturnMotion.onReturn()
        }
    }

    override fun onResume() {
        super.onResume()
        // Kullanıcı bildirim erişimini yeni verdiyse müzik takibi burada başlar.
        MediaHub.start(this)
        // Haritanın altındaki adada son kullanılanlar.
        RecentApps.refresh(this)
        // Hava durumu 15 dakikada bir yenilenir; döngü zaten çalışıyorsa bir şey yapmaz.
        WeatherHub.start(this)
        // Araç verisi: BYD veri yolu, yoksa GPS hızı, o da yoksa önizleme.
        app.pano.launcher.vehicle.VehicleHub.start(this)
        // Direksiyondaki sesli asistan tuşu; Pano öndeyken asistan bu ekranda açılır.
        app.pano.launcher.assistant.VoiceKey.onResume(this)
    }

    override fun onPause() {
        app.pano.launcher.assistant.VoiceKey.onPause(this)
        super.onPause()
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
        Box(Modifier.fillMaxSize()) {
            val layout = LocalStyle.current.layout
            // Uygulamalar her düzende tam ekran, simgeden büyüyerek açılır. Sağ panelde küçük pencere
            // (eski CarPlay davranışı) kullanıcı isteğiyle kapatıldı; SidePanel kodu duruyor.
            Launch.sideBySide = false
            when (layout) {
                LayoutStyle.CarPlay -> CarPlayApp(screen, climate, onNavigate)
                LayoutStyle.Ufuk -> app.pano.launcher.ui.UfukApp(screen, climate, onNavigate)
                LayoutStyle.Pano -> ClassicApp(screen, climate, onNavigate)
            }
            // WhatsApp / SMS mesajları her iki düzenin üstünde kısa bir banner olarak görünür.
            app.pano.launcher.ui.MessageBanner()
            // Pano Asistan açıkken dock'un üstündeki gösterge.
            app.pano.launcher.assistant.AssistantOverlay()
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
                Modifier.fillMaxSize().returnZoom().padding(start = 32.dp, end = 32.dp, top = 26.dp, bottom = 24.dp),
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
