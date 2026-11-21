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

    /**
     * Medya oturumunu açan paketin kendi simgesi olmayabilir: BYD'de Spotify çalar ama araç ekranında
     * onu "com.vivid.music.byd" kabuğu açar. Böyle durumlar için açılacak paket.
     */
    private val MEDIA_FRONTENDS = mapOf("com.spotify.music" to "com.vivid.music.byd")

    /** Araç ünitelerinin kendi müzik uygulamaları (standart müzik kategorisi tanımlı değilse). */
    private val CAR_MUSIC_APPS = listOf("com.byd.mediacenter")

    /** Araç ünitelerinin telefon uygulamaları; standart arama ekranı (DIAL) olmayan cihazlar için. */
    private val CAR_PHONE_APPS = listOf("com.byd.bluetoothcall")

    private fun launchable(context: Context, packages: List<String?>): Intent? = packages.asSequence()
        .filterNotNull()
        .mapNotNull { context.packageManager.getLaunchIntentForPackage(it) }
        .firstOrNull()

    private fun resolves(context: Context, intent: Intent) =
        intent.resolveActivity(context.packageManager) != null

    fun music(context: Context, activePackage: String?, anchor: LaunchAnchor? = null) {
        val launch = launchable(context, listOf(activePackage, MEDIA_FRONTENDS[activePackage]))
            ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC).takeIf { resolves(context, it) }
            ?: launchable(context, CAR_MUSIC_APPS)
        if (launch != null) start(context, launch, "Müzik uygulaması açılamadı", anchor)
        else Toast.makeText(context, "Müzik uygulaması bulunamadı", Toast.LENGTH_SHORT).show()
    }

    fun phone(context: Context, anchor: LaunchAnchor? = null) {
        val launch = Intent(Intent.ACTION_DIAL).takeIf { resolves(context, it) }
            ?: launchable(context, CAR_PHONE_APPS)
        if (launch != null) start(context, launch, "Telefon uygulaması açılamadı", anchor)
        else Toast.makeText(context, "Telefon uygulaması bulunamadı", Toast.LENGTH_SHORT).show()
    }

    fun settings(context: Context) = start(context, Intent(Settings.ACTION_SETTINGS), "Ayarlar açılamadı")

    /** Aracın kendi ayarları (BYD'de alt çubuktaki dişli); yoksa Android ayarları. */
    private val CAR_SETTINGS_APPS = listOf("com.byd.carsettings")

    fun carSettings(context: Context) {
        val launch = launchable(context, CAR_SETTINGS_APPS)
        if (launch != null) start(context, launch, "Araç ayarları açılamadı") else settings(context)
    }

    fun notificationAccess(context: Context) =
        start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "Ayar ekranı açılamadı")

    fun notConnectedYet(context: Context) =
        Toast.makeText(context, "Araç bağlantısı henüz yok (önizleme)", Toast.LENGTH_SHORT).show()
}
