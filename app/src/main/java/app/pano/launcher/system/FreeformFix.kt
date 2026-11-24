package app.pano.launcher.system

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Tam ekran açılması istenen uygulama küçük pencerede (freeform) gelirse onu büyütür.
 *
 * Android 10 bir görevin pencere modunu görev yaşadıkça korur: sağ panelde (CarPlay düzeni) bir
 * kez küçük pencere olarak açılan uygulama, sonra tam ekran istense de (ör. Ufuk düzeninde) yine
 * küçük pencerede, son kaldığı yerde gelir. Var olan göreve ne tam ekran isteği ne yeni sınırlar
 * ne de FLAG_ACTIVITY_CLEAR_TASK / MULTIPLE_TASK uygulanıyor (emülatörde denendi); uygulamalar
 * başka bir görevin modunu değiştiremez. Tek yol pencere çubuğundaki "büyüt" düğmesi: Pano'nun
 * erişilebilirlik hizmeti yalnızca bu durumda, yalnızca açılan uygulamanın penceresinde bu
 * düğmeyi arar ve basar. Pencere tam ekransa (düğme yoksa) hiçbir şey yapılmaz.
 */
object FreeformFix {
    private const val TAG = "FreeformFix"
    private const val MAXIMIZE_ID = "android:id/maximize_window"
    private const val TRIES = 10
    private const val STEP_MS = 200L

    private val handler = Handler(Looper.getMainLooper())

    /** Cihazda küçük pencere açılabiliyor mu (BYD DiLink 3.0'da açık). */
    private fun freeformEnabled(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            runCatching { Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) != 0 }.getOrDefault(false)

    /** [app.pano.launcher.Launch] tam ekran açtığı her uygulama için çağırır. */
    fun ensureFullscreen(context: Context, pkg: String?) {
        if (pkg == null || pkg == context.packageName) return
        val app = context.applicationContext
        if (!freeformEnabled(app)) return
        if (PanoAccessibility.instance == null && !SystemActions.enableOwnService(app)) return
        attempt(pkg, 0)
    }

    private fun attempt(pkg: String, n: Int) {
        handler.postDelayed({
            when (tryMaximize(pkg)) {
                Result.Done -> Log.i(TAG, "$pkg küçük pencerede geldi, büyütüldü")
                Result.NotFloating -> Unit
                Result.NotYet -> if (n + 1 < TRIES) attempt(pkg, n + 1)
            }
        }, STEP_MS)
    }

    private enum class Result { Done, NotFloating, NotYet }

    private fun tryMaximize(pkg: String): Result {
        val service = PanoAccessibility.instance ?: return Result.NotYet
        val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
            .ifEmpty { listOfNotNull(service.rootInActiveWindow) }
        val root = roots.firstOrNull { it.packageName?.toString() == pkg } ?: return Result.NotYet
        val button = root.findAccessibilityNodeInfosByViewId(MAXIMIZE_ID).firstOrNull()
            ?: return Result.NotFloating
        // Android 10'da çubuğun düğmeleri dokunuşla çalışıyor (tıklama eylemine bağlı değil): o
        // noktaya kısa bir dokunuş gönderilir; olmazsa tıklama eylemi denenir.
        val b = Rect().also { button.getBoundsInScreen(it) }
        val tapped = !b.isEmpty && runCatching {
            val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
            service.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(),
                null, null,
            )
        }.getOrDefault(false)
        return if (tapped || button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) Result.Done else Result.NotYet
    }
}
