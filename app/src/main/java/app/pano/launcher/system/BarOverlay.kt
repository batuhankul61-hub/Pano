package app.pano.launcher.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
import app.pano.launcher.ui.OverlayBarHeight
import app.pano.launcher.ui.UfukOverlayBar
import app.pano.launcher.ui.UfukOverlayState
import app.pano.launcher.ui.theme.PanoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pano arkadayken (başka bir uygulama tam ekran açıkken) Pano'nun kendi alt şeridi ekranın altında
 * "diğer uygulamaların üzerinde" katmanı olarak durur; aracın alt sistem çubuğu yerine bu kullanılır.
 *
 * Yalnızca Ufuk düzeninde ve "Sistem çubuklarını gizle" açıkken gösterilir (aracın çubuğu o zaman
 * gizli). Şerit görünürken ekranın altından şerit boyu kadar yer uygulamalardan ayrılır ([Overscan]):
 * öndeki uygulama şeridin üstünde biter. Bu yapılamazsa (izin yok, Android 10 değil) şerit
 * uygulamanın en alt kısmını örter. İzin ("Diğer uygulamaların üzerinde göster") yoksa hiçbir şey
 * gösterilmez.
 *
 * Klima kartı açılınca pencere tüm ekranı kaplar (dışarı dokunmak yalnızca kartı kapatır), ses
 * göstergesi açılınca şeridin üstünde biraz büyür; kapanınca yine şerit boyuna iner ve şeridin
 * dışı öndeki uygulamaya dokunur. Gösterge açıkken pencerenin dışına dokunmak onu da kapatır.
 *
 * Aracın kendi ana ekranı öndeyken şerit gizlenir (orada aracın kendi çubuğu var, çift şerit
 * olmasın). Öndeki uygulama Pano'nun erişilebilirlik hizmetinden öğrenilir ([ForegroundApp]);
 * hizmet kapalıysa ve açılamıyorsa şerit her yerde gösterilir.
 */
object BarOverlay {
    private const val TAG = "BarOverlay"
    /** Pano'yu bu kartla öne getirir; [MainActivity.onNewIntent] okur. */
    const val EXTRA_SCREEN = "app.pano.launcher.SCREEN"

    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var owner: OverlayOwner? = null
    private var scope: CoroutineScope? = null
    /** Tasarım biriminin piksel karşılığı. */
    private var unitPx = 1f
    /** Aracın ana ekranı önde: şerit görünmez ve dokunuş almaz. */
    private var suppressed = false

    /** Şerit şu an bir uygulamanın üstünde görünüyor mu (ses tuşları göstergesini burada açar). */
    val visible: Boolean get() = view != null && !suppressed
    /** Şeridin boyu (piksel); uygulamalardan bu kadar yer ayrılır. */
    private var barPx = 0
    /** Ekranın gerçek yüksekliği (piksel). */
    private var screenPx = 0

    fun wanted(context: Context): Boolean {
        val s = PanoSettings.state.value
        return s.hideSystemBar && s.layout == LayoutStyle.Ufuk && Settings.canDrawOverlays(context)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (view != null || !wanted(context)) return
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(WindowManager::class.java) ?: return
        // Pano'daki tasarım tuvaliyle aynı ölçek: şerit Pano'dakiyle aynı boyda görünür.
        val m = DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        val scale = min(m.widthPixels / m.density / 1600f, m.heightPixels / m.density / 900f)
        unitPx = m.density * scale
        screenPx = m.heightPixels
        val height = (OverlayBarHeight.value * unitPx).roundToInt()

        // Ana ekranı tanımak için erişilebilirlik hizmeti gerekir; güvenli ayar izni varsa Pano'nun
        // kendi hizmeti eklenir (dock'taki Geri düğmesiyle aynı yol, başka hizmetlere dokunulmaz).
        if (PanoAccessibility.instance == null) SystemActions.enableOwnService(appContext)

        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                PanoTheme(PanoSettings.state.value) {
                    UfukOverlayBar(
                        scale = scale,
                        onOpen = { screen -> openPano(appContext, screen) },
                        onHeight = { units -> resize(units) },
                    )
                }
            }
            // Kart açıkken pencerenin dışına (öndeki uygulamaya) dokunmak kartı kapatır.
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) UfukOverlayState.dismiss()
                false
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Uygulamalardan ayrılan alt boşluğa (overscan) çizilebilsin.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            title = "Pano alt şerit"
        }
        runCatching { wm.addView(v, lp) }
            .onSuccess {
                view = v; params = lp; owner = o
                suppressed = false
                barPx = height
                applyOverscan(appContext, true)
                watchForeground(appContext)
            }
            .onFailure { Log.w(TAG, "şerit eklenemedi: $it"); o.destroy() }
    }

    fun hide() {
        val v = view ?: return
        view = null
        params = null
        scope?.cancel()
        scope = null
        UfukOverlayState.dismiss()
        Overscan.reset(v.context)
        runCatching { v.context.getSystemService(WindowManager::class.java)?.removeViewImmediate(v) }
        owner?.destroy()
        owner = null
    }

    /** Pencerenin boyu (tasarım birimi; null = tüm ekran); kart açılınca büyür, kapanınca şerit boyuna döner. */
    private fun resize(units: Float?) {
        val v = view ?: return
        val lp = params ?: return
        // Tüm ekran: alt boşluk açıkken pencere aşağı kaydırılmış; tam ekran boyu verilir ki üstte açık kalmasın.
        val h = units?.let { (it * unitPx).roundToInt() }
            ?: if (lp.y != 0 && screenPx > 0) screenPx else WindowManager.LayoutParams.MATCH_PARENT
        if (lp.height == h) return
        lp.height = h
        runCatching { v.context.getSystemService(WindowManager::class.java)?.updateViewLayout(v, lp) }
            .onFailure { Log.w(TAG, "şerit boyu değişmedi: $it") }
    }

    /**
     * Alt boşluğu açar/kapatır. Açıkken uygulamaların düzeni boşluğun üstünde biter; şerit penceresi
     * de boşluk kadar aşağı kaydırılır (yoksa Android onu uygulama alanının dibine koyuyor).
     */
    private fun applyOverscan(context: Context, on: Boolean, update: Boolean = true) {
        val lp = params ?: return
        val gap = Overscan.set(context, if (on) barPx else 0)
        lp.y = -gap
        if (update) view?.let { v -> runCatching { context.getSystemService(WindowManager::class.java)?.updateViewLayout(v, lp) } }
    }

    /** Öndeki uygulama aracın ana ekranıysa şerit gizlenir. */
    private fun watchForeground(context: Context) {
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            ForegroundApp.pkg.collect { pkg -> setSuppressed(ForegroundApp.isHome(context, pkg)) }
        }
    }

    private fun setSuppressed(on: Boolean) {
        if (suppressed == on) return
        val v = view ?: return
        val lp = params ?: return
        suppressed = on
        if (on) UfukOverlayState.dismiss()
        // Aracın ana ekranında şerit yok: alt boşluk da kalkar.
        applyOverscan(v.context, !on, update = false)
        v.visibility = if (on) View.INVISIBLE else View.VISIBLE
        lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { v.context.getSystemService(WindowManager::class.java)?.updateViewLayout(v, lp) }
        Log.i(TAG, if (on) "aracın ana ekranı önde: şerit gizlendi" else "şerit gösteriliyor")
    }

    /** Şeritteki kart düğmeleri (Ev, Uygulamalar, Ayarlar, Müzik) Pano'yu o kartla açar. */
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
