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
 * Barın yeri araçta ölçüldü (1280×720, firmware 13.1.33): [24,210]–[256,528]. Ekran farklıysa oranla
 * ölçeklenir. Pano'nun kendi +/- düğmeleri sistem barını açmıyor; onların değişikliği burada
 * gösterilmez ([markOwnChange]).
 */
object VolumeHudOverlay {
    private const val TAG = "VolumeHud"
    private val BYD_BAR = Rect(24, 210, 256, 528)
    private const val REF_W = 1280f
    private const val REF_H = 720f
    /** BYD barını kenarlarıyla birlikte örtmek için taşma (piksel, 720p'de). */
    private const val MARGIN = 20
    /** Son değişiklikten sonra açık kalma süresi; BYD barı bu sürede kapanmış oluyor. */
    private const val SHOW_MS = 6000L

    val level = mutableIntStateOf(0)
    val max = mutableIntStateOf(1)
    val muted = mutableStateOf(false)
    /** Gösterilen ses akışı (müzik; aramada arama sesi). */
    @Volatile private var stream = AudioManager.STREAM_MUSIC

    private var service: AccessibilityService? = null
    private var receiver: BroadcastReceiver? = null
    private var view: ComposeView? = null
    private var owner: Owner? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
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
        hide()
        service = null
    }

    private fun onVolumeChanged(type: Int) {
        if (PanoSettings.state.value.layout != LayoutStyle.Ufuk) return
        if (SystemClock.uptimeMillis() - ownChangeAt < 800) return
        val audio = service?.getSystemService(AudioManager::class.java) ?: return
        // Aramada arama sesi, yoksa müzik gösterilir (BYD de böyle değiştiriyor).
        val inCall = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
        val wanted = if (inCall) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        if (type != -1 && type != wanted) return
        stream = wanted
        refresh(audio)
        show()
    }

    private fun refresh(audio: AudioManager) {
        max.intValue = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        level.intValue = audio.getStreamVolume(stream)
        muted.value = audio.isStreamMute(stream)
    }

    /** Bardan sürükleyerek ses ayarı. */
    fun set(value: Int) {
        val audio = service?.getSystemService(AudioManager::class.java) ?: return
        markOwnChange()
        runCatching {
            if (muted.value && value > 0) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            audio.setStreamVolume(stream, value.coerceIn(0, max.intValue), 0)
        }
        refresh(audio)
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
            ((BYD_BAR.left - MARGIN) * sx).toInt(), ((BYD_BAR.top - MARGIN) * sy).toInt(),
            ((BYD_BAR.right + MARGIN) * sx).toInt(), ((BYD_BAR.bottom + MARGIN) * sy).toInt(),
        )
    }

    private fun show() {
        val s = service ?: return
        scheduleHide()
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

    fun hide() {
        if (touching) { scheduleHide(); return }
        handler.removeCallbacks(hideRunnable)
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
