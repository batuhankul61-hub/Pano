package app.pano.launcher.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BYD'nin "genel sessiz" durumu. Direksiyondaki ses tekerine basınca BYD sesi Android'in akış
 * sessizinden değil kendi genel sessizinden kapatıyor (`AudioManager.setGlobalMuteState(MUTE_CLIENT_SYSTEM, …)`,
 * gizli API) ve `GLOBAL_MUTE_CHANGED_ACTION` yayınını `SYSTEM_MUTE_STATUS` ile yapıyor (araçta görüldü,
 * 25.11.2026). Pano bu durumu izler, gösterir ve kendi sessiz düğmesinde de aynısını kullanır.
 * BYD dışı cihazlarda [available] false olur ve hiçbir şey yapılmaz.
 */
object BydMute {
    private const val TAG = "BydMute"
    const val ACTION = "android.media.AudioManager.GLOBAL_MUTE_CHANGED_ACTION"
    private const val EXTRA = "android.media.extra.SYSTEM_MUTE_STATUS"
    /** `AudioManager.MUTE_CLIENT_SYSTEM` (firmware 13.1.33). */
    private const val MUTE_CLIENT_SYSTEM = 0

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    @Volatile var available = false
        private set
    private var registered = false

    fun init(context: Context) {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        HiddenApi.exempt("Landroid/media/AudioManager")
        runCatching {
            audio.javaClass.getMethod("getGlobalMuteState", Int::class.javaPrimitiveType)
                .invoke(audio, MUTE_CLIENT_SYSTEM) as Boolean
        }.onSuccess { available = true; _muted.value = it }
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val on = intent?.getBooleanExtra(EXTRA, false) ?: return
                available = true
                _muted.value = on
            }
        }, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    /** Genel sessizi açar/kapatır; yapılamazsa false (çağıran Android'in sessizine düşer). */
    fun set(context: Context, on: Boolean): Boolean {
        if (!available) return false
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return runCatching {
            audio.javaClass.getMethod("setGlobalMuteState", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(audio, MUTE_CLIENT_SYSTEM, on)
            _muted.value = on
        }.onFailure { Log.w(TAG, "sessiz değişmedi: ${(it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it}") }
            .isSuccess
    }
}
