package app.pano.launcher.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
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
        // Sistem bu hizmeti sürekli bağlı tutar: direksiyondaki sesli asistan tuşu Pano ekranda
        // açılmamış olsa da dinlenir.
        app.pano.launcher.assistant.VoiceKey.init(this)
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
    /** Çalan şey BYD radyosuysa istasyon bilgisi ([Radio]); değilse null. */
    val radio: RadioStation? = null,
) {
    /** Kartın büyük satırı: radyoda istasyon, müzikte parça adı. */
    val displayTitle: String get() = radio?.title ?: title
    /** Küçük satır: radyoda bant/frekans, müzikte sanatçı. */
    val displaySubtitle: String get() = radio?.subtitle ?: artist
}

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

    private var appContext: Context? = null

    /**
     * Bazı uygulamalar (Spotify dahil) kapağı resim olarak değil adres olarak verir
     * (content:// ya da https://). Adres arka planda bir kez yüklenir; gelince durum yeniden yayınlanır.
     */
    private var artUri: String? = null
    private var artBitmap: Bitmap? = null
    private val artLoader = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun artFromUri(meta: MediaMetadata?): Bitmap? {
        val uri = meta?.let {
            it.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: it.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                ?: it.description?.iconUri?.toString()
        }?.takeIf { it.isNotBlank() }
        if (uri == null) {
            if (meta != null) android.util.Log.i("MediaHub", "kapak yok; alanlar: ${meta.keySet()}")
            return null
        }
        if (uri == artUri) return artBitmap
        artUri = uri
        artBitmap = null
        val context = appContext ?: return null
        artLoader.execute {
            val bmp = runCatching { loadArt(context, uri) }.getOrNull()
            mainHandler.post {
                if (artUri == uri && bmp != null) {
                    artBitmap = bmp
                    publish()
                }
            }
        }
        return null
    }

    private fun loadArt(context: Context, uri: String): Bitmap? {
        val stream = if (uri.startsWith("http")) {
            (java.net.URL(uri).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }.inputStream
        } else {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))
        }
        return stream?.use { android.graphics.BitmapFactory.decodeStream(it) }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (listening || !hasAccess(context)) return
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(context, MediaListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener({ sessions -> onSessions(sessions) }, component, mainHandler)
            onSessions(manager.getActiveSessions(component))
            listening = true
        } catch (_: SecurityException) {
            // Erişim henüz verilmemiş; kullanıcı izin verince tekrar denenir.
        }
    }

    /**
     * Tüm etkin oturumların çalma durumu izlenir (yalnızca gösterilen değil). Gerekçe: bir oturum
     * listede zaten varken çalmaya başlayınca (BYD medya merkezinin radyo oturumu hep açık durur;
     * Spotify duraklatılıp radyo açılır) liste değişmez, Pano eski parçada takılı kalıyordu.
     */
    private val watched = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    private fun onSessions(list: List<MediaController>?) {
        val now = list.orEmpty()
        val tokens = now.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in tokens }.forEach { t -> watched.remove(t)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        // Sistemin sırası korunur (en son etkin olan önce).
        val ordered = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
        for (c in now) {
            ordered[c.sessionToken] = watched[c.sessionToken] ?: run {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = choose()
                }
                c.registerCallback(cb, mainHandler)
                c to cb
            }
        }
        watched.clear()
        watched.putAll(ordered)
        choose()
    }

    /** Çalan oturum öne çıkar; hiçbiri çalmıyorsa son gösterilen (duraklatılmış) kalır. */
    private fun choose() {
        val all = watched.values.map { it.first }
        val current = controller?.let { c -> all.firstOrNull { it.sessionToken == c.sessionToken } }
        val next = current?.takeIf { it.isPlaying() }
            ?: all.firstOrNull { it.isPlaying() }
            ?: current
            ?: all.firstOrNull()
        if (next?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback, mainHandler)
        publish()
    }

    private fun MediaController.isPlaying() = playbackState?.state == PlaybackState.STATE_PLAYING

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = null
            return
        }
        val meta = c.metadata
        val playback = c.playbackState
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "Bilinmeyen parça" }
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        // BYD medya merkezi radyo modunu kendi anahtarıyla yazar (anahtar yoksa radyo sayılmaz).
        val mode = meta?.takeIf { it.containsKey(Radio.MODE_KEY) }?.getLong(Radio.MODE_KEY)
        _state.value = NowPlaying(
            title = title,
            artist = artist,
            radio = Radio.parse(c.packageName, mode, title, artist),
            art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta?.description?.iconBitmap
                ?: artFromUri(meta),
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

    /** Uygulama sarmayı destekliyorsa true; ilerleme çubuğu ancak o zaman sürüklenebilir. */
    fun canSeek(): Boolean =
        (controller?.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L

    fun seekTo(positionMs: Long) = controller?.transportControls?.seekTo(positionMs)
}
