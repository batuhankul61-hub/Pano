package app.pano.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Aracın alt sistem çubuğundaki düğmelerin Pano karşılıkları: geri, son uygulamalar, yarım ekran
 * ve ekranı döndürme.
 *
 * Geri / son uygulamalar / yarım ekran bütün sistemde ancak bir erişilebilirlik hizmetiyle
 * yapılabiliyor ([PanoAccessibility]); kullanıcının bunu bir kez Ayarlar > Erişilebilirlik'ten
 * açması gerekiyor. Ekran döndürme BYD'nin kendi yayınıyla çalışır (direksiyondaki döndürme
 * düğmesi de aynısını yapıyor: SystemUI'deki AutoRotationController bu yayını dinliyor).
 */
object SystemActions {
    private const val TAG = "SystemActions"
    private const val BYD_ROTATE = "byd.intent.action.DIRECT_ROTATION"

    fun back(context: Context) = global(context, AccessibilityService.GLOBAL_ACTION_BACK)
    fun recents(context: Context) = global(context, AccessibilityService.GLOBAL_ACTION_RECENTS)
    fun splitScreen(context: Context) = global(context, AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

    /** Ekranı yatay ↔ dikey döndürür (BYD'de; başka cihazda bir şey olmaz). */
    fun rotate(context: Context) {
        runCatching { context.sendBroadcast(Intent(BYD_ROTATE)) }
            .onFailure { Log.w(TAG, "döndürme: $it") }
    }

    private fun global(context: Context, action: Int) {
        val service = PanoAccessibility.instance
        if (service == null) {
            Toast.makeText(
                context, "Bu düğme için Ayarlar > Erişilebilirlik'te \"Pano\" hizmetini aç", Toast.LENGTH_LONG,
            ).show()
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val ok = service.performGlobalAction(action)
        Log.i(TAG, "sistem eylemi $action → $ok")
    }
}

/**
 * Yalnızca sistem düğmelerine basmak için (geri, son uygulamalar, yarım ekran). Ekran içeriğini
 * okumaz, olay dinlemez; yapılandırma res/xml/pano_accessibility.xml'de.
 */
class PanoAccessibility : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Log.i("SystemActions", "erişilebilirlik hizmeti bağlandı")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile var instance: PanoAccessibility? = null
            private set
    }
}
