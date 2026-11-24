package app.pano.launcher.messages

import android.content.Context
import android.provider.Telephony

/**
 * Pano'nun mesaj okuyabileceği kaynakların durumu; Ayarlar → Bildirimler'de dürüstçe gösterilir.
 *
 * Pano mesajları yalnızca araçta yüklü uygulamaların Android bildirimlerinden okur.
 * BYD DiLink 3.0'da (Atto 2, firmware 2511) telefonun SMS'i Bluetooth ile araca gelmiyor:
 * aracın Bluetooth yığınında mesaj profili (MAP istemcisi, `profile_supported_mapmce`) kapalı,
 * BYD'nin telefon uygulaması (`com.byd.bluetoothcall`) yalnızca arama ve rehber taşıyor ve
 * sistemde SMS uygulaması yok. WhatsApp da yalnızca araca kuruluysa bildirim üretir.
 */
object MessageSources {
    /** Araçta yüklü, mesaj bildirimi beklenebilecek uygulamaların adları. */
    fun installedApps(context: Context): List<String> {
        val pm = context.packageManager
        val names = mutableListOf<String>()
        for ((pkg, name) in KNOWN) {
            if (runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess) names += name
        }
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        if (sms != null && KNOWN.none { it.first == sms }) {
            names += runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sms, 0)).toString() }.getOrDefault("SMS")
        }
        return names
    }

    /** Ayarlar'daki "Mesaj kaynağı" satırının açıklaması. */
    fun statusText(hasAccess: Boolean, apps: List<String>): String = when {
        !hasAccess -> "Bildirim erişimi kapalı olduğu için mesajlar okunamıyor"
        apps.isEmpty() ->
            "Araçta mesaj uygulaması yok. Telefonun SMS ve WhatsApp mesajları Bluetooth ile araca aktarılmıyor, " +
                "bu yüzden Pano onları göremiyor. Araca kurulan bir mesaj uygulamasının bildirimleri burada görünür."
        else -> apps.joinToString(", ") + " bildirimleri okunuyor"
    }

    private val KNOWN = listOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
    )
}
