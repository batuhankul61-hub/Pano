package app.pano.launcher.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.pano.launcher.messages.MessageHub
import app.pano.launcher.navigation.NavHub
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bildirim dinleyicisi. Varlığı, Android'in medya oturumlarını okumamıza izin verir
 * ([MediaHub]); ayrıca navigasyon uygulamalarının bildirimlerini [NavHub]'a,
 * WhatsApp / SMS bildirimlerini [MessageHub]'a aktarır.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Navigasyon zaten başlamışsa mevcut bildirimi hemen al.
        val active = try {
            activeNotifications
        } catch (_: Exception) {
            null
        }
        active?.filter { NavHub.isNavPackage(it.packageName) }?.forEach { NavHub.onPosted(this, it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NavHub.clear()
        MessageHub.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (NavHub.isNavPackage(sbn.packageName)) NavHub.onPosted(this, sbn)
        // Bağlanırken eski mesajlar alınmaz; yalnızca yeni gelenler banner olur.
        else MessageHub.onPosted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (NavHub.isNavPackage(sbn.packageName)) NavHub.onRemoved(sbn) else MessageHub.onRemoved(sbn)
    }
}

data class NowPlaying(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val speed: Float,
    val durationMs: Long,
    val packageName: String,
)

/** Şu an çalan müziği (Spotify, Bluetooth, radyo…) takip eder ve kontrol eder. */
object MediaHub {
    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var listening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            controller = null
            _state.value = null
        }
    }

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun start(context: Context) {
        if (listening || !hasAccess(context)) return
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(context, MediaListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener({ sessions -> pick(sessions) }, component, mainHandler)
            pick(manager.getActiveSessions(component))
            listening = true
        } catch (_: SecurityException) {
            // Erişim henüz verilmemiş; kullanıcı izin verince tekrar denenir.
        }
    }

    private fun pick(sessions: List<MediaController>?) {
        val next = sessions?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions?.firstOrNull()
        if (next?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback, mainHandler)
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = null
            return
        }
        val meta = c.metadata
        val playback = c.playbackState
        _state.value = NowPlaying(
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "Bilinmeyen parça" },
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            positionMs = playback?.position ?: 0L,
            positionUpdatedAt = playback?.lastPositionUpdateTime ?: 0L,
            speed = playback?.playbackSpeed ?: 0f,
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            packageName = c.packageName,
        )
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
}
