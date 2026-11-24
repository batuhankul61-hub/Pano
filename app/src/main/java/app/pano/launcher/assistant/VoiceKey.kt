package app.pano.launcher.assistant

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * Direksiyondaki sesli asistan tuşu → Pano Asistan.
 *
 * BYD firmware'inde (DiLink 3.0, 13.1.33) tuşun yolu:
 * - Tuş, BYD'ye özel tuş kodu **327** olarak gelir. `PhoneWindowManager.interceptKeyBeforeDispatching`
 *   onu uygulamalara vermez; bırakılınca `MediaKeyHandler.sendVoiceButtonIntent` ile
 *   `android.intent.action.MEDIA_VOICE` yayınını gönderir (`KEY_EVENT` ekiyle). Bu yayın korumalıdır
 *   (yalnızca sistem gönderir) ama herkes dinleyebilir.
 * - BYD'nin asistanı (`com.byd.vrassistant`, `HardKeyReceiverEx`) da aynı yayını dinler. Yayın sıralı
 *   değildir, kimse durduramaz. Ama BYD asistanı mikrofon başkasındayken tuşu yok sayar
 *   (`AudioMicReceiver.isMicRelease`). Android Auto / CarPlay bunu
 *   `android.broadcast.voice.request.start` (`who` = "androidauto") ile bildirir; yayın korumalı değil.
 *
 * Bu yüzden özellik açıkken Pano, BYD asistanına Android Auto gibi "mikrofon bende" der ve tuşa
 * kendisi cevap verir. Kapatılınca mikrofon `...request.release` ile geri bırakılır. Pano'nun süreci
 * hiç çalışmıyorsa tuş her zamanki gibi BYD asistanını açar.
 *
 * Bilinmeyen: BYD asistanının servisi yeniden başlarsa bayrağı sıfırlanır; o zaman iki asistan birden
 * açılabilir. Bunu azaltmak için istek sık tazelenir ([refresh]).
 */
object VoiceKey {
    private const val TAG = "VoiceKey"
    private const val PREFS = "pano_assistant"
    private const val PREF_ENABLED = "voice_key"
    /** Mikrofonu en son biz istedik mi (kapatınca geri bırakmak için kalıcı). */
    private const val PREF_REQUESTED = "voice_key_requested"

    const val ACTION_MEDIA_VOICE = "android.intent.action.MEDIA_VOICE"
    const val ACTION_REQUEST_MIC = "android.broadcast.voice.request.start"
    const val ACTION_RELEASE_MIC = "android.broadcast.voice.request.release"
    /** BYD asistanı yalnızca "androidauto" ve "carplay" adlarını tanır. */
    private const val WHO = "androidauto"

    /** Tuş tekrarlarını (çift yayın, titreyen tuş) eler. */
    private const val DEBOUNCE_MS = 600L

    private val _enabled = mutableStateOf(true)
    val enabled: State<Boolean> = _enabled

    @Volatile private var registered = false
    @Volatile private var lastPress = 0L
    private var app: Context? = null
    /** Ön plandaki Pano ekranı: varsa asistan onun içinde açılır (gösterge görünür, uygulamalar sağ panelde). */
    private var resumed: WeakReference<Activity>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_MEDIA_VOICE) return
            val event = intentKeyEvent(intent)
            // Sistem yalnızca bırakışta gönderir; yine de basış gelirse yok say.
            if (event != null && event.action != KeyEvent.ACTION_UP) return
            onPress()
        }
    }

    /**
     * Uygulamanın her giriş noktasından çağrılır (ana ekran, bildirim dinleyici, erişilebilirlik).
     * İlk çağrıda tuş dinlenmeye başlar; sonrakilerde yalnızca mikrofon isteği tazelenir.
     */
    fun init(context: Context) {
        val a = context.applicationContext
        app = a
        if (!registered) {
            registered = true
            _enabled.value = prefs(a).getBoolean(PREF_ENABLED, true)
            // Korumalı sistem yayını: dışa açık kayıt gerekli (gönderen sistem).
            ContextCompat.registerReceiver(a, receiver, IntentFilter(ACTION_MEDIA_VOICE), ContextCompat.RECEIVER_EXPORTED)
            Log.i(TAG, "sesli asistan tuşu dinleniyor")
        }
        refresh(a)
    }

    fun onResume(activity: Activity) {
        resumed = WeakReference(activity)
        init(activity)
    }

    fun onPause(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(PREF_ENABLED, on).apply()
        _enabled.value = on
        refresh(context)
    }

    /** Tuş bu an Pano'ya mı ait: ayar açık ve bir API anahtarı kayıtlı. */
    fun isActive(context: Context): Boolean = _enabled.value && AssistantKey.isStored(context)

    /**
     * BYD asistanına mikrofonun kimde olduğunu yeniden söyler. Tuş Pano'ya aitse "bende", değilse ve
     * daha önce biz istediysek "bıraktım". Anahtar silinirse tuş BYD asistanına geri döner.
     */
    fun refresh(context: Context) {
        val c = context.applicationContext
        val active = isActive(c)
        val p = prefs(c)
        when {
            active -> send(c, ACTION_REQUEST_MIC)
            p.getBoolean(PREF_REQUESTED, false) -> send(c, ACTION_RELEASE_MIC)
            else -> return
        }
        p.edit().putBoolean(PREF_REQUESTED, active).apply()
    }

    private fun send(context: Context, action: String) {
        runCatching {
            context.sendBroadcast(Intent(action).putExtra("who", WHO).putExtra("usemic", "pano"))
            Log.i(TAG, if (action == ACTION_REQUEST_MIC) "BYD asistanına: mikrofon Pano'da" else "BYD asistanına: mikrofon bırakıldı")
        }.onFailure { Log.w(TAG, "mikrofon yayını gönderilemedi: ${it.javaClass.simpleName}") }
    }

    private fun onPress() {
        val c = app ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPress < DEBOUNCE_MS) return
        lastPress = now
        if (!isActive(c)) {
            Log.i(TAG, "tuş geldi ama Pano'ya ait değil (ayar kapalı ya da anahtar yok)")
            return
        }
        Log.i(TAG, "sesli asistan tuşu")
        // BYD asistanının bayrağı sıfırlanmışsa bir dahaki basışta yine Pano açılsın.
        refresh(c)
        Handler(Looper.getMainLooper()).post { toggle(c) }
    }

    private fun toggle(c: Context) {
        if (PanoAssistant.isActive) {
            PanoAssistant.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(c, "Pano Asistan için mikrofon izni gerekli: Pano'daki asistan düğmesine bir kez bas", Toast.LENGTH_LONG).show()
            return
        }
        val activity = resumed?.get()?.takeIf { !it.isFinishing }
        // Pano ekranda değilse asistan yalnızca sesle çalışır; mikrofon arka planda susturulmasın diye
        // oturum boyunca ön plan hizmeti açılır.
        if (activity == null) AssistantForegroundService.start(c)
        PanoAssistant.start(activity ?: c)
    }

    @Suppress("DEPRECATION")
    private fun intentKeyEvent(intent: Intent): KeyEvent? =
        runCatching { intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) }.getOrNull()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
