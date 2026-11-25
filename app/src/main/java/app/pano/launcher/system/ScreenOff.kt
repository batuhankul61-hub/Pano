package app.pano.launcher.system

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import app.pano.launcher.vehicle.Gear
import app.pano.launcher.vehicle.VehicleHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Ekranı kapat": tüm ekranı siyah bir pencereyle örter ve parlaklığı en alta çeker; dokununca
 * (ya da geri vitese geçilince) eski parlaklıkla geri açılır.
 *
 * BYD'nin kendi düğmesi (SystemUI `BydScreenOffManager`) arka ışığı `PowerManager.TurnBacklightOff`
 * ile gerçekten kapatıyor; bu yöntem `DEVICE_POWER` (imza) izni istediği için Pano'ya kapalı. Bu
 * yüzden Pano ekranı karartır: siyah + en düşük parlaklık. Pencere erişilebilirlik katmanındadır
 * (sistem çubuklarının da üstü); hizmet yoksa "diğer uygulamaların üzerinde" katmanı kullanılır.
 */
object ScreenOff {
    private const val TAG = "ScreenOff"
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var wm: WindowManager? = null
    private var savedBrightness: Int? = null
    private var gearWatch: Job? = null

    val isOn: Boolean get() = view != null

    /** Bu cihazda karartma penceresi açılabilir mi. */
    fun available(context: Context): Boolean =
        PanoAccessibility.instance != null || Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility")
    fun turnOff(context: Context) {
        if (view != null) return
        // Geri viteste kamera görünür kalmalı.
        if (VehicleHub.state.value.gear == Gear.R) return
        val service = PanoAccessibility.instance
        val host: Context = service ?: context.applicationContext
        val type = if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val manager = host.getSystemService(WindowManager::class.java) ?: return
        val v = View(host).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = "Ekran kapalı, açmak için dokunun"
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_UP) turnOn()
                true
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        ).apply {
            title = "Pano ekran kapalı"
            // En düşük parlaklık yalnızca bu pencere görünürken; sistem ayarına dokunulmaz.
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        }
        runCatching { manager.addView(v, lp) }
            .onSuccess {
                view = v
                wm = manager
                dimSystemBrightness(host)
                watchGear()
                Log.i(TAG, "ekran karartıldı (${if (service != null) "erişilebilirlik" else "üst katman"})")
            }
            .onFailure { Log.w(TAG, "karartma penceresi açılamadı: $it") }
    }

    fun turnOn() {
        main.post {
            val v = view ?: return@post
            view = null
            gearWatch?.cancel()
            gearWatch = null
            runCatching { wm?.removeViewImmediate(v) }
            restoreSystemBrightness(v.context)
            wm = null
            Log.i(TAG, "ekran açıldı")
        }
    }

    /**
     * Pencerenin parlaklık isteği bazı sürücülerde (BYD gibi) yok sayılabilir; o yüzden yazma izni
     * varsa sistem parlaklığı da en alta çekilir ve açılınca eski değere döner.
     */
    private fun dimSystemBrightness(context: Context) {
        if (!QuickControls.canWriteBrightness(context)) return
        val now = QuickControls.brightness(context) ?: return
        savedBrightness = now
        runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, QuickControls.minBrightness(context)) }
    }

    private fun restoreSystemBrightness(context: Context) {
        val saved = savedBrightness ?: return
        savedBrightness = null
        runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, saved) }
    }

    private fun watchGear() {
        gearWatch?.cancel()
        gearWatch = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            VehicleHub.state.collect { if (it.gear == Gear.R) turnOn() }
        }
    }
}
