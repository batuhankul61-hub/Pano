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

    /**
     * BYD'nin ayarlarında Erişilebilirlik menüsü yok. Pano'ya güvenli ayar izni verildiyse
     * (bkz. [SystemBars]) hizmet buradan açılır: kullanıcı düğmeye bastığı anda, yalnızca
     * Pano'nun kendi hizmeti eklenir; listedeki diğer hizmetlere dokunulmaz.
     */
    private var lastRebindAt = -60_000L

    internal fun enableOwnService(context: Context): Boolean {
        if (!SystemBars.canApplyGlobal(context)) return false
        val me = "${context.packageName}/${PanoAccessibility::class.java.name}"
        return runCatching {
            val cr = context.contentResolver
            val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val items = list.split(':').filter { it.isNotBlank() }
            if (me !in items) Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (items + me).joinToString(":"))
            else if (PanoAccessibility.instance == null && android.os.SystemClock.uptimeMillis() - lastRebindAt > 30_000) {
                // Listede ama bağlı değil: güncellemeden sonra araçta Android hizmeti yeniden bağlamadı
                // (25.11.2026). Listeden çıkarıp yeniden eklemek bağlanmasını sağlıyor.
                lastRebindAt = android.os.SystemClock.uptimeMillis()
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, items.filter { it != me }.joinToString(":"))
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching {
                        val now = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                            .split(':').filter { it.isNotBlank() && it != me }
                        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (now + me).joinToString(":"))
                    }
                }, 800)
                Log.i(TAG, "Pano erişilebilirlik hizmeti listede ama bağlı değil, yeniden ekleniyor")
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Pano erişilebilirlik hizmeti açıldı")
            true
        }.onFailure { Log.w(TAG, "hizmet açılamadı: $it") }.getOrDefault(false)
    }

    private fun global(context: Context, action: Int) {
        val service = PanoAccessibility.instance
        if (service == null && enableOwnService(context)) {
            // Hizmetin bağlanması bir an sürer; sonra istenen eylem yapılır.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val s = PanoAccessibility.instance
                if (s != null) s.performGlobalAction(action)
                else Toast.makeText(context, "Pano hizmeti açılıyor, tekrar dene", Toast.LENGTH_SHORT).show()
            }, 800)
            return
        }
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
 * Sistem düğmelerine basmak için (geri, son uygulamalar, yarım ekran). "Pencere değişti"
 * olaylarından öndeki uygulamanın paket adını alır ([ForegroundApp]: alt şerit aracın kendi ana
 * ekranında gizlenir). Pencere içeriğine yalnızca [FreeformFix] bakar: küçük pencerede kalmış
 * bir uygulama tam ekran açılırken çubuğundaki "büyüt" düğmesine basmak için.
 * Yapılandırma res/xml/pano_accessibility.xml'de.
 */
class PanoAccessibility : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        // Bu hizmet de sürekli bağlı kalır: sesli asistan tuşu buradan da dinlenmeye başlar.
        app.pano.launcher.assistant.VoiceKey.init(this)
        Log.i("SystemActions", "erişilebilirlik hizmeti bağlandı")
        // Direksiyondan ses değişince aracın ses barı yerine Pano'nunki.
        VolumeHudOverlay.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        VolumeHudOverlay.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ForegroundApp.onWindowChanged(this, event.packageName?.toString(), event.className?.toString())
        }
    }
    override fun onInterrupt() {}

    companion object {
        @Volatile var instance: PanoAccessibility? = null
            private set
    }
}
