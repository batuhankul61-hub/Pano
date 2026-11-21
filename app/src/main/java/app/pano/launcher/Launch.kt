package app.pano.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** Başka uygulamaları açan yardımcılar. Uygulama bulunamazsa kullanıcıya kısa bir not gösterilir. */
object Launch {
    private fun start(context: Context, intent: Intent, failMessage: String) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, failMessage, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, failMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun intent(context: Context, intent: Intent) = start(context, intent, "Ekran açılamadı")

    fun app(context: Context, packageName: String, className: String) =
        start(
            context,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(packageName, className)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            "Uygulama açılamadı",
        )

    fun maps(context: Context) =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")), "Navigasyon uygulaması bulunamadı")

    fun music(context: Context, activePackage: String?) {
        val launch = activePackage?.let { context.packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            start(context, launch, "Müzik uygulaması açılamadı")
        } else {
            start(
                context,
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC),
                "Müzik uygulaması bulunamadı",
            )
        }
    }

    fun phone(context: Context) = start(context, Intent(Intent.ACTION_DIAL), "Telefon uygulaması bulunamadı")

    fun settings(context: Context) = start(context, Intent(Settings.ACTION_SETTINGS), "Ayarlar açılamadı")

    fun notificationAccess(context: Context) =
        start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "Ayar ekranı açılamadı")

    fun notConnectedYet(context: Context) =
        Toast.makeText(context, "Araç bağlantısı henüz yok (önizleme)", Toast.LENGTH_SHORT).show()
}
