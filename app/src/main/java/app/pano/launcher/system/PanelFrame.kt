package app.pano.launcher.system

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * Sağ paneldeki uygulama penceresini Pano'nun kartlarına benzetir. Android 10 başka bir
 * uygulamanın pencere köşelerini yuvarlatmaya izin vermediği için pencerenin üstüne iki
 * "diğer uygulamaların üzerinde" katman konur:
 *  - Üst çubuk: Android'in gri pencere çubuğunun yerine uygulama adı ve ✕ (Pano renklerinde).
 *  - Köşe maskesi: dokunulmaz; pencerenin dört köşesine Pano'nun o noktadaki görüntüsünü çizer,
 *    pencere yuvarlak köşeli görünür.
 * İzin ("Diğer uygulamaların üzerinde göster") yoksa çerçeve çizilmez, pencere düz kalır.
 */
object PanelFrame {
    private const val TAG = "PanelFrame"
    /** Android 10 freeform pencere çubuğunun yüksekliği (dp; araçta ölçüldü: 160 dpi'da 42 px). */
    private const val CAPTION_DP = 42f
    private const val RADIUS_DP = 22f

    /** Pano gece temasında mı; CarPlay düzeni yazar. */
    @Volatile var dark = true

    private val views = mutableListOf<View>()
    private var asked = false

    fun show(activity: Activity, window: Rect, label: String) {
        hide()
        if (!Settings.canDrawOverlays(activity)) {
            askOnce(activity)
            return
        }
        val wm = activity.getSystemService(WindowManager::class.java) ?: return
        val d = activity.resources.displayMetrics.density
        val captionH = (CAPTION_DP * d).toInt()

        // Pano'nun şu anki görüntüsü (panel alanı boş: yalnızca duvar kağıdı); köşeler buradan.
        val root = activity.window.decorView
        val rootLoc = IntArray(2).also { root.getLocationOnScreen(it) }
        val shot = runCatching {
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }.onFailure { Log.w(TAG, "görüntü alınamadı: $it") }.getOrNull()

        val header = Header(activity, label, dark, d) { SidePanel.dismiss(activity) }
        add(wm, header, Rect(window.left, window.top, window.right, window.top + captionH), touchable = true)
        val mask = CornerMask(activity, shot, window.left - rootLoc[0], window.top - rootLoc[1], RADIUS_DP * d, dark)
        add(wm, mask, window, touchable = false)
    }

    fun hide() {
        val wm = views.firstOrNull()?.context?.getSystemService(WindowManager::class.java)
        views.forEach { v -> runCatching { wm?.removeView(v) } }
        views.clear()
    }

    private fun add(wm: WindowManager, view: View, r: Rect, touchable: Boolean) {
        val lp = WindowManager.LayoutParams(
            r.width(), r.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = r.left
            y = r.top
            title = "Pano panel çerçevesi"
        }
        runCatching { wm.addView(view, lp); views += view }.onFailure { Log.w(TAG, "katman eklenemedi: $it") }
    }

    private fun askOnce(activity: Activity) {
        if (asked) return
        asked = true
        Toast.makeText(
            activity, "Sağ paneldeki uygulamaların köşeleri için \"Diğer uygulamaların üzerinde göster\" iznini ver",
            Toast.LENGTH_LONG,
        ).show()
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Android'in gri pencere çubuğunu örten, Pano renklerinde üst çubuk: uygulama adı ve ✕. */
    @SuppressLint("ViewConstructor")
    private class Header(context: Context, private val label: String, dark: Boolean, private val d: Float, private val onClose: () -> Unit) : View(context) {
        private val bg = if (dark) Color.rgb(0x1A, 0x1C, 0x22) else Color.rgb(0xF3, 0xF4, 0xF7)
        private val fg = if (dark) Color.WHITE else Color.rgb(0x0B, 0x0B, 0x0C)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; textSize = 15 * d; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; alpha = 170; strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND
        }
        private val closeW get() = 64 * d

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(bg)
            canvas.drawText(label, 18 * d, height / 2f + text.textSize * 0.35f, text)
            // ✕
            val cx = width - closeW / 2; val cy = height / 2f; val s = 7 * d
            canvas.drawLine(cx - s, cy - s, cx + s, cy + s, line)
            canvas.drawLine(cx + s, cy - s, cx - s, cy + s, line)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && e.x > width - closeW) onClose()
            return true
        }
    }

    /** Pencerenin dört köşesini, arkadaki Pano görüntüsüyle kapatır (yuvarlak köşe etkisi). */
    @SuppressLint("ViewConstructor")
    private class CornerMask(
        context: Context, private val shot: Bitmap?, private val dx: Int, private val dy: Int,
        private val radius: Float, dark: Boolean,
    ) : View(context) {
        private val fallback = Paint().apply { color = if (dark) Color.rgb(0x12, 0x16, 0x2C) else Color.rgb(0xEC, 0xEA, 0xF6) }
        private val clip = Path()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            clip.reset()
            clip.fillType = Path.FillType.EVEN_ODD
            clip.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
            clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.clipPath(clip)
            if (shot != null) canvas.drawBitmap(shot, -dx.toFloat(), -dy.toFloat(), null)
            else canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallback)
            canvas.restore()
        }
    }
}
