package app.pano.launcher.system

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.mutableLongStateOf
import app.pano.launcher.settings.LayoutStyle
import app.pano.launcher.settings.Settings as PanoSettings

/**
 * Ses tuşları (direksiyon, ünitenin tuşları) Ufuk'ta Pano'nun kendi ses göstergesini açar; aracın
 * kendi ses paneli çıkmaz. Pano'nun erişilebilirlik hizmeti tuşları sistemden önce alır
 * ([PanoAccessibility.onKeyEvent]), sesi göstergesiz değiştirir ve [pressedAt]'i günceller; Pano'daki
 * ve uygulamaların üstündeki şeritteki gösterge buna bakıp açılır.
 *
 * Yalnızca göstergenin görüneceği bir yer varken yakalanır: Pano önde ya da şerit başka bir
 * uygulamanın üstünde. Aracın kendi ana ekranında, aramada ve çalarken tuşlar sisteme bırakılır
 * (arama sesi aracın kendi kontrolünde kalsın).
 */
object VolumeKeys {
    private const val TAG = "VolumeKeys"

    /** Son ses tuşunun zamanı (uptime); 0 = hiç. Göstergeler bunu izler. */
    val pressedAt = mutableLongStateOf(0L)

    /** Pano'nun etkinliği önde mi (MainActivity yazar). */
    @Volatile var panoVisible = false

    fun handle(context: Context, event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return false
        }
        if (PanoSettings.state.value.layout != LayoutStyle.Ufuk) return false
        if (!panoVisible && !BarOverlay.visible) return false
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.mode == AudioManager.MODE_RINGTONE
        ) return false
        // Basılı tutunca tekrarlar; bırakma olayı da yutulur ki sistem paneli açılmasın.
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (direction == AudioManager.ADJUST_TOGGLE_MUTE && event.repeatCount > 0) return true
            runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
                .onFailure { Log.w(TAG, "ses değişmedi: $it") }
            pressedAt.longValue = android.os.SystemClock.uptimeMillis()
        }
        return true
    }
}
