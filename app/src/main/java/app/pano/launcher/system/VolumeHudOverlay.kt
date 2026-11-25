package app.pano.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.pano.launcher.settings.LayoutStyle
import app.pano.launcher.settings.Settings as PanoSettings
import app.pano.launcher.ui.SideVolumeHud
import app.pano.launcher.ui.theme.PanoTheme

/**
 * Direksiyondaki ses tuşları BYD'de uygulamalara ulaşmıyor: sistem sesi kendisi değiştirip kendi ses
 * barını (SystemUI `BydVolumeDialogImpl`, ekranın solunda) açıyor. Pano o barı kapatamıyor; bunun
 * yerine Ufuk'ta ses değişince erişilebilirlik hizmetinin pencere katmanında (sistemin ses barından
 * daha üstte) kendi barını tam onun yerine açar, BYD barı altında kalır.
 *
 * BYD barı gibi iki sürgü gösterir: Medya (müzik; radyo / Bluetooth müzik çalarken BYD'nin kendi
 * kanalları, aramada arama sesi) ve Navigasyon (BYD `STREAM_NAVI` = 14, BYD barında "Yayın").
 *
 * Barın yeri araçta ölçüldü (1280×720, firmware 13.1.33): [24,210]–[256,528]. Ekran farklıysa oranla
 * ölçeklenir. Pano'nun kendi +/- düğmeleri sistem barını açmıyor; onların değişikliği burada
 * gösterilmez ([markOwnChange]).
 */
object VolumeHudOverlay {
    private const val TAG = "VolumeHud"
    private val BYD_BAR = Rect(24, 210, 256, 528)
    private const val REF_W = 1280f
    private const val REF_H = 720f
    /**
     * BYD barını gölgesiyle örtmek için taşma (piksel, 720p'de). Yanlarda gölge görünüyordu (20);
     * üstte ve altta az (6): üstte hız göstergesi, altta müzik kartı var.
     */
    private const val MARGIN_X = 20
    private const val MARGIN_Y = 6
    /** Son değişiklikten sonra açık kalma süresi; BYD barı bu sürede kapanmış oluyor. */
    private const val SHOW_MS = 6000L
    /** Kapanış animasyonu (SideVolumeHud ile aynı). */
    const val EXIT_MS = 180L

    /** BYD'nin ek ses kanalları (android.media.AudioManager, gizli): FM 11, AUX 12, BT müzik 13, navigasyon 14. */
    const val STREAM_NAVI = 14
    private val MEDIA_STREAMS = setOf(AudioManager.STREAM_MUSIC, 11, 12, 13)

    /** Bir sürgünün durumu. */
    class Slider(val label: String) {
        val stream = mutableIntStateOf(AudioManager.STREAM_MUSIC)
        val level = mutableIntStateOf(0)
        val max = mutableIntStateOf(1)
        val muted = mutableStateOf(false)
        val title = mutableStateOf(label)
    }

    val media = Slider("Medya")
    val navi = Slider("Navigasyon").also { it.stream.intValue = STREAM_NAVI }
    /** Son değişen sürgü navigasyon mu (vurgulanır). */
    val naviActive = mutableStateOf(false)
    /** Pano önde: bar sol sütunun bir parçası gibi çizilir (kart yok). */
    val onPano = mutableStateOf(false)
    /** Görünür mü (açılış / kapanış animasyonu bunu izler). */
    val visible = mutableStateOf(false)

    private var service: AccessibilityService? = null
    private var receiver: BroadcastReceiver? = null
    private var view: ComposeView? = null
    private var owner: Owner? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private val removeRunnable = Runnable { removeWindow() }
    @Volatile private var ownChangeAt = 0L
    /** Kullanıcı bara dokunurken kapanmasın. */
    @Volatile var touching = false

    /** Pano'nun kendi ses düğmeleri sesi değiştirdi: bu değişiklik için bar açılmaz. */
    fun markOwnChange() { ownChangeAt = SystemClock.uptimeMillis() }

    fun attach(s: AccessibilityService) {
        detach()
        service = s
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val type = intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) ?: -1
                onVolumeChanged(type)
            }
        }
        receiver = r
        ContextCompat.registerReceiver(
            s, r, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun detach() {
        receiver?.let { r -> runCatching { service?.unregisterReceiver(r) } }
        receiver = null
        removeWindow()
        service = null
    }

    private fun audio(): AudioManager? = service?.getSystemService(AudioManager::class.java)

    private fun inCall(a: AudioManager) =
        a.mode == AudioManager.MODE_IN_CALL || a.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun onVolumeChanged(type: Int) {
        if (PanoSettings.state.value.layout != LayoutStyle.Ufuk) return
        if (SystemClock.uptimeMillis() - ownChangeAt < 800) return
        val a = audio() ?: return
        val call = inCall(a)
        when {
            type == STREAM_NAVI -> naviActive.value = true
            call && type == AudioManager.STREAM_VOICE_CALL -> {
                media.stream.intValue = AudioManager.STREAM_VOICE_CALL
                naviActive.value = false
            }
            !call && type in MEDIA_STREAMS -> {
                media.stream.intValue = type
                naviActive.value = false
            }
            else -> return
        }
        media.title.value = if (media.stream.intValue == AudioManager.STREAM_VOICE_CALL) "Arama" else "Medya"
        refresh(a)
        show()
    }

    private fun refresh(a: AudioManager) {
        for (s in listOf(media, navi)) {
            val st = s.stream.intValue
            runCatching {
                s.max.intValue = a.getStreamMaxVolume(st).coerceAtLeast(1)
                s.level.intValue = a.getStreamVolume(st)
                s.muted.value = a.isStreamMute(st)
            }
        }
    }

    /** Sürgüden ses ayarı. */
    fun set(slider: Slider, value: Int) {
        val a = audio() ?: return
        markOwnChange()
        val st = slider.stream.intValue
        runCatching {
            if (slider.muted.value && value > 0) a.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0)
            a.setStreamVolume(st, value.coerceIn(0, slider.max.intValue), 0)
        }.onFailure { Log.w(TAG, "ses ayarlanamadı ($st): $it") }
        naviActive.value = slider === navi
        refresh(a)
        scheduleHide()
    }

    fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, SHOW_MS)
    }

    private fun bounds(s: Context): Rect {
        val wm = s.getSystemService(WindowManager::class.java)
        val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        val sx = m.widthPixels / REF_W
        val sy = m.heightPixels / REF_H
        return Rect(
            ((BYD_BAR.left - MARGIN_X) * sx).toInt(), ((BYD_BAR.top - MARGIN_Y) * sy).toInt(),
            ((BYD_BAR.right + MARGIN_X) * sx).toInt(), ((BYD_BAR.bottom + MARGIN_Y) * sy).toInt(),
        )
    }

    private fun show() {
        val s = service ?: return
        scheduleHide()
        handler.removeCallbacks(removeRunnable)
        onPano.value = ForegroundApp.pkg.value == s.packageName
        visible.value = true
        if (view != null) return
        val wm = s.getSystemService(WindowManager::class.java) ?: return
        val b = bounds(s)
        val o = Owner().also { it.start() }
        val v = ComposeView(s).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { PanoTheme(PanoSettings.state.value) { SideVolumeHud() } }
        }
        val lp = WindowManager.LayoutParams(
            b.width(), b.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = b.left
            y = b.top
            title = "Pano ses barı"
        }
        runCatching { wm.addView(v, lp) }
            .onSuccess { view = v; owner = o; Log.i(TAG, "ses barı açıldı $b") }
            .onFailure { Log.w(TAG, "ses barı açılamadı: $it"); o.destroy() }
    }

    /** Kapanış animasyonu oynar, sonra pencere kalkar. */
    fun hide() {
        if (touching) { scheduleHide(); return }
        handler.removeCallbacks(hideRunnable)
        if (view == null) return
        visible.value = false
        handler.postDelayed(removeRunnable, EXIT_MS + 40)
    }

    private fun removeWindow() {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(removeRunnable)
        visible.value = false
        val v = view ?: return
        view = null
        runCatching { service?.getSystemService(WindowManager::class.java)?.removeViewImmediate(v) }
        owner?.destroy()
        owner = null
    }

    private class Owner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = saved.savedStateRegistry
        fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
