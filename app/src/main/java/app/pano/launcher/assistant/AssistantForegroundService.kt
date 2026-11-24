package app.pano.launcher.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.pano.launcher.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Pano ekranda değilken (direksiyon tuşuyla) açılan asistan oturumu için ön plan hizmeti.
 *
 * Android 9+ arka plandaki uygulamaya mikrofondan sessizlik verir; ön plan hizmeti varken vermez.
 * Oturum bitince ([PanoAssistant] boşta) hizmet kendini kapatır. Bildirime dokunmak Pano'yu açar.
 */
class AssistantForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }.onFailure { Log.w(TAG, "ön plan hizmeti başlatılamadı: ${it.javaClass.simpleName}") }.isSuccess
        if (!started) { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            // Oturum açılana kadar beklenir (start kısa süre sonra gelir), sonra bitmesi beklenir.
            PanoAssistant.ui.first { it.phase != AssistantPhase.Idle }
            PanoAssistant.ui.first { it.phase == AssistantPhase.Idle }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Pano Asistan", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Pano Asistan dinliyor")
            .setContentText("Kapatmak için direksiyondaki sesli asistan tuşuna yeniden bas.")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "VoiceKey"
        private const val CHANNEL = "pano_assistant"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, AssistantForegroundService::class.java),
                )
            }.onFailure { Log.w(TAG, "ön plan hizmeti istenemedi: ${it.javaClass.simpleName}") }
        }
    }
}
