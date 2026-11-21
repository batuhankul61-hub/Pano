package app.pano.launcher.messages

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** WhatsApp ya da SMS uygulamasından gelen son mesaj. */
data class IncomingMessage(
    /** Gönderen (kişi adı / numara). Grup sohbetinde "Grup: Kişi". */
    val sender: String,
    /** Mesajın kısa metni. */
    val text: String,
    /** Bildirimi gönderen paket. */
    val packageName: String,
    /** Kullanıcıya gösterilecek uygulama adı, örn. "WhatsApp" / "Mesajlar". */
    val appName: String,
    /** Bildirimin kendi açma isteği (doğrudan sohbete gider); yoksa uygulama açılır. */
    val contentIntent: PendingIntent?,
    /** Bildirimin anahtarı; aynı bildirimin tekrarlarını ayırt etmek için. */
    val key: String,
    val postedAt: Long,
)

/**
 * WhatsApp, WhatsApp Business ve varsayılan SMS uygulamasının bildirimlerini okuyup
 * son mesajı [state] üzerinden yayınlar. Veriyi [app.pano.launcher.media.MediaListenerService] besler.
 *
 * Kullanım:
 * ```
 * val msg by MessageHub.state.collectAsState()
 * ```
 */
object MessageHub {
    private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")

    /** Hata ayıklama sürümünde `adb shell cmd notification post` ile test için. */
    private const val SHELL = "com.android.shell"

    private val _state = MutableStateFlow<IncomingMessage?>(null)
    val state: StateFlow<IncomingMessage?> = _state.asStateFlow()

    fun isMessagePackage(context: Context, packageName: String): Boolean =
        packageName in WHATSAPP ||
            packageName == defaultSms(context) ||
            (packageName == SHELL && isDebuggable(context))

    /** Bildirim geldi/güncellendi. Mesaj bildirimi değilse yok sayılır. */
    fun onPosted(context: Context, sbn: StatusBarNotification) {
        if (!isMessagePackage(context, sbn.packageName)) return
        val n = sbn.notification
        // Grup özetleri ("3 sohbetten 5 mesaj") ve sürekli bildirimler (yedekleme, web oturumu) atlanır.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing) return

        val parsed = parse(context, sbn) ?: return
        // Aynı mesaj tekrar yayınlandıysa banner'ı yeniden açma.
        val current = _state.value
        if (current != null && current.key == parsed.key && current.text == parsed.text && current.sender == parsed.sender) return
        _state.value = parsed
    }

    /** Bildirim kaldırıldı (kullanıcı telefonda okudu vb.); gösterilen buysa kapat. */
    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.key == _state.value?.key) clear()
    }

    /** Banner kapatıldığında ya da dinleyici koptuğunda. */
    fun clear() {
        _state.value = null
    }

    private fun parse(context: Context, sbn: StatusBarNotification): IncomingMessage? {
        val n = sbn.notification
        val extras = n.extras ?: Bundle.EMPTY

        var sender: String? = null
        var text: String? = null

        // MessagingStyle varsa son mesajı oradan al: gönderen ve metin ayrı ayrı gelir.
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        if (last != null) {
            text = last.text?.toString()
            val person = last.person?.name?.toString()
            val conversation = style.conversationTitle?.toString()
            sender = when {
                style.isGroupConversation && conversation != null && person != null -> "$conversation: $person"
                person != null -> person
                else -> conversation
            }
        }

        if (text.isNullOrBlank()) text = extras.text(Notification.EXTRA_TEXT) ?: extras.text(Notification.EXTRA_BIG_TEXT)
        if (sender.isNullOrBlank()) sender = extras.text(Notification.EXTRA_TITLE_BIG) ?: extras.text(Notification.EXTRA_TITLE)

        val body = text?.trim()?.replace(Regex("\\s+"), " ")
        if (body.isNullOrBlank() || sender.isNullOrBlank()) return null

        return IncomingMessage(
            sender = sender.trim(),
            text = body,
            packageName = sbn.packageName,
            appName = appName(context, sbn.packageName),
            contentIntent = n.contentIntent,
            key = sbn.key,
            postedAt = sbn.postTime,
        )
    }

    private fun appName(context: Context, packageName: String): String = when (packageName) {
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        SHELL -> "Test"
        else -> try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            "Mesajlar"
        }
    }

    private fun defaultSms(context: Context): String? = try {
        Telephony.Sms.getDefaultSmsPackage(context)
    } catch (_: Exception) {
        null
    }

    private fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun Bundle.text(key: String): String? =
        getCharSequence(key)?.toString()?.trim()?.ifBlank { null }
}
