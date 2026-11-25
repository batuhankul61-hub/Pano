package app.pano.launcher.phone

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log

/**
 * Telecom'un arama bağlantısı. Arayüzü olmayan (IN_CALL_SERVICE_UI=false) bir InCallService:
 * Telecom bunu yalnızca Pano "arama yardımcısı" rolündeyse bağlar. Rol uygulamadan istenemiyor,
 * adb ile verilir:
 *
 *     adb shell cmd role add-role-holder android.app.role.CALL_COMPANION app.pano.launcher
 *
 * Bağlıyken en eksiksiz kaynak budur: numara, durum, görüşmenin başladığı an, sessiz, DTMF,
 * cevapla / reddet / kapat. Bağlı değilse [CallHub] Bluetooth HFP'ye ve Telephony'ye düşer.
 */
class PanoInCallService : InCallService() {
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish()
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Telecom'a bağlandı")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        CallHub.onInCall(null)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        publish()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        publish()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) = publish()

    /** Öne çıkan arama: çalan, sonra etkin olan, sonra ilki. */
    private fun main(): Call? {
        val list = calls.filter { stateOf(it) != Call.STATE_DISCONNECTED }
        return list.firstOrNull { stateOf(it) == Call.STATE_RINGING }
            ?: list.firstOrNull { stateOf(it) == Call.STATE_ACTIVE }
            ?: list.firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun stateOf(c: Call) = c.state

    private fun publish() {
        val c = main()
        if (c == null) { CallHub.onInCall(null); return }
        val d = c.details
        val phase = when (stateOf(c)) {
            Call.STATE_RINGING -> CallPhase.Incoming
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW, Call.STATE_SELECT_PHONE_ACCOUNT -> CallPhase.Dialing
            Call.STATE_ACTIVE -> CallPhase.Active
            Call.STATE_HOLDING -> CallPhase.Holding
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> CallPhase.Ended
            else -> CallPhase.Ongoing
        }
        // connectTimeMillis duvar saati; süre sayacı için açılış anına (elapsedRealtime) çevrilir.
        val since = d.connectTimeMillis.takeIf { it > 0 && phase == CallPhase.Active }?.let {
            android.os.SystemClock.elapsedRealtime() - (System.currentTimeMillis() - it)
        }
        CallHub.onInCall(
            CallInfo(
                number = d.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() },
                name = d.callerDisplayName?.takeIf { it.isNotBlank() },
                phase = phase,
                activeSince = since,
                muted = callAudioState?.isMuted == true,
                canDtmf = true,
                source = "incall",
            ),
        )
    }

    fun answer(): Boolean = main()?.takeIf { stateOf(it) == Call.STATE_RINGING }?.let { it.answer(VideoProfile.STATE_AUDIO_ONLY); true } ?: false
    fun reject(): Boolean = main()?.takeIf { stateOf(it) == Call.STATE_RINGING }?.let { it.reject(false, null); true } ?: false
    fun hangUp(): Boolean = main()?.let { it.disconnect(); true } ?: false
    fun mute(on: Boolean): Boolean { setMuted(on); return true }

    fun dtmf(key: Char): Boolean {
        val c = main() ?: return false
        c.playDtmfTone(key)
        Handler(Looper.getMainLooper()).postDelayed({ c.stopDtmfTone() }, 180)
        return true
    }

    companion object {
        private const val TAG = "PanoInCall"
        @Volatile var instance: PanoInCallService? = null
            private set
    }
}
