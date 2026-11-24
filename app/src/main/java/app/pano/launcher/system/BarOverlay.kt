package app.pano.launcher.system

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.pano.launcher.MainActivity
import app.pano.launcher.Screen
import app.pano.launcher.settings.LayoutStyle
import app.pano.launcher.settings.Settings as PanoSettings
import app.pano.launcher.ui.BarHeight
import app.pano.launcher.ui.UfukOverlayBar
import app.pano.launcher.ui.theme.PanoTheme
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pano arkadayken (başka bir uygulama tam ekran açıkken) Pano'nun kendi alt şeridi ekranın altında
 * "diğer uygulamaların üzerinde" katmanı olarak durur; aracın alt sistem çubuğu yerine bu kullanılır.
 *
 * Yalnızca Ufuk düzeninde ve "Sistem çubuklarını gizle" açıkken gösterilir (aracın çubuğu o zaman
 * gizli). Şerit, öndeki uygulamanın en alt kısmını örter: Android 10'da başka bir uygulamanın tam
 * ekran penceresini kısaltmanın güvenli bir yolu yok. İzin ("Diğer uygulamaların üzerinde göster")
 * yoksa hiçbir şey gösterilmez.
 */
object BarOverlay {
    private const val TAG = "BarOverlay"
    /** Pano'yu bu kartla öne getirir; [MainActivity.onNewIntent] okur. */
    const val EXTRA_SCREEN = "app.pano.launcher.SCREEN"

    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    fun wanted(context: Context): Boolean {
        val s = PanoSettings.state.value
        return s.hideSystemBar && s.layout == LayoutStyle.Ufuk && Settings.canDrawOverlays(context)
    }

    fun show(context: Context) {
        if (view != null || !wanted(context)) return
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(WindowManager::class.java) ?: return
        // Pano'daki tasarım tuvaliyle aynı ölçek: şerit Pano'dakiyle aynı boyda görünür.
        val m = DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        val scale = min(m.widthPixels / m.density / 1600f, m.heightPixels / m.density / 900f)
        val height = (BarHeight.value * m.density * scale).roundToInt()

        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                PanoTheme(PanoSettings.state.value) {
                    UfukOverlayBar { screen -> openPano(appContext, screen) }
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            title = "Pano alt şerit"
        }
        runCatching { wm.addView(v, lp) }
            .onSuccess { view = v; owner = o }
            .onFailure { Log.w(TAG, "şerit eklenemedi: $it"); o.destroy() }
    }

    fun hide() {
        val v = view ?: return
        view = null
        runCatching { v.context.getSystemService(WindowManager::class.java)?.removeViewImmediate(v) }
        owner?.destroy()
        owner = null
    }

    /** Şeritteki kart düğmeleri (Klima, Uygulamalar, Ayarlar, Müzik) Pano'yu o kartla açar. */
    private fun openPano(context: Context, screen: Screen) {
        hide()
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_SCREEN, screen.name),
            )
        }.onFailure { Log.w(TAG, "Pano açılamadı: $it") }
    }

    /** Pencere katmanındaki Compose için yaşam döngüsü (etkinlik yok). */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = saved.savedStateRegistry

        fun start() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
