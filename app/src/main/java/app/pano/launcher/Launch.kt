package app.pano.launcher

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Toast

/**
 * Uygulamanın açılış animasyonunun başlayacağı yer: dokunulan öğenin penceredeki gerçek piksel
 * sınırları. [view] pencerenin kök görünümüdür; sınırlar ona göredir.
 */
class LaunchAnchor(val view: View, val bounds: Rect)

/** Başka uygulamaları açan yardımcılar. Uygulama bulunamazsa kullanıcıya kısa bir not gösterilir. */
object Launch {
    /** Son başarılı başlatmanın zamanı; launcher'a dönüş animasyonu yalnızca bundan sonra oynar. */
    @Volatile
    var lastLaunchAt: Long = 0L
        private set

    /** İkondan büyüyerek açılma (iOS gibi). Sınır yoksa sistemin varsayılan geçişi kullanılır. */
    private fun options(anchor: LaunchAnchor?): Bundle? {
        val b = anchor?.bounds ?: return null
        if (b.width() <= 0 || b.height() <= 0) return null
        return ActivityOptions.makeClipRevealAnimation(anchor.view, b.left, b.top, b.width(), b.height()).toBundle()
    }

    private fun start(context: Context, intent: Intent, failMessage: String, anchor: LaunchAnchor? = null) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options(anchor))
            lastLaunchAt = SystemClock.uptimeMillis()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, failMessage, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, failMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun intent(context: Context, intent: Intent) = start(context, intent, "Ekran açılamadı")

    fun app(context: Context, packageName: String, className: String, anchor: LaunchAnchor? = null) =
        start(
            context,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(packageName, className)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            "Uygulama açılamadı",
            anchor,
        )

    fun maps(context: Context, anchor: LaunchAnchor? = null) =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")), "Navigasyon uygulaması bulunamadı", anchor)

    fun music(context: Context, activePackage: String?, anchor: LaunchAnchor? = null) {
        val launch = activePackage?.let { context.packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            start(context, launch, "Müzik uygulaması açılamadı", anchor)
        } else {
            start(
                context,
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC),
                "Müzik uygulaması bulunamadı",
                anchor,
            )
        }
    }

    fun phone(context: Context, anchor: LaunchAnchor? = null) =
        start(context, Intent(Intent.ACTION_DIAL), "Telefon uygulaması bulunamadı", anchor)

    fun settings(context: Context) = start(context, Intent(Settings.ACTION_SETTINGS), "Ayarlar açılamadı")

    fun notificationAccess(context: Context) =
        start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "Ayar ekranı açılamadı")

    fun notConnectedYet(context: Context) =
        Toast.makeText(context, "Araç bağlantısı henüz yok (önizleme)", Toast.LENGTH_SHORT).show()
}
