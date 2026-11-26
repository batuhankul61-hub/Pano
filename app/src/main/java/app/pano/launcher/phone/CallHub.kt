package app.pano.launcher.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Süren görüşmenin tek kaynağı. Bilgi birkaç yerden gelir, öncelik sırasıyla:
 *  1. [PanoInCallService]: Telecom'un kendisi (Pano "arama yardımcısı" rolündeyse; adb ile verilir).
 *  2. [HfpClient]: aracın Bluetooth eller serbest istemcisi; BYD'nin arama uygulamasının da kaynağı.
 *  3. Telephony (`PhoneStateListener`): yalnızca çalıyor / sürüyor / bitti ve gelen numara.
 * Ad: Pano'nun okuyabildiği rehber, yoksa BYD'nin arama penceresinde yazan ad ([onScreen]).
 *
 * Eylemler de aynı sırayla denenir; hiçbiri olmazsa BYD penceresindeki düğmeye erişilebilirlik
 * hizmetiyle basılır ([screenAction]).
 */
object CallHub {
    private const val TAG = "CallHub"
    /** Bitince "Arama bitti" bu kadar görünür. */
    private const val ENDED_MS = 1800L

    enum class Action { Answer, Reject, HangUp, Mute, Transfer }

    private val _state = MutableStateFlow<CallInfo?>(null)
    val state: StateFlow<CallInfo?> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var listener: PhoneStateListener? = null

    private var inCall: CallInfo? = null
    private var hfp: CallInfo? = null
    private var hfpCall: HfpClient.Call? = null
    private var tel: CallInfo? = null
    private var screen: CallScreenText.Parsed? = null

    /** Görüşmenin başladığı an (kaynak vermediyse ilk "Active" görüldüğü an); numara değişince sıfırlanır. */
    private var activeSince: Long? = null
    private var activeKey: String? = null
    /** Pano'nun kendi sessiz durumu (Telecom'a bağlı değilken AudioManager ile). */
    private var micMuted = false
    private var lastTelState = TelephonyManager.CALL_STATE_IDLE
    private val names = HashMap<String, String?>()
    private var endedAt = 0L
    private var last: CallInfo? = null

    /** BYD penceresindeki düğmeye basan yedek yol (CallOverlay kurar). */
    var screenAction: ((Action) -> Boolean)? = null

    private val clearEnded = Runnable { if (live() == null) { _state.value = null; last = null } }
    private val poll = object : Runnable {
        override fun run() {
            refreshHfp()
            if (_state.value != null && _state.value?.phase != CallPhase.Ended) main.postDelayed(this, 1000)
        }
    }

    @SuppressLint("MissingPermission")
    fun init(context: Context) {
        if (app != null) return
        val a = context.applicationContext
        app = a
        HfpClient.init(a) { main.post { refreshHfp() } }
        runCatching {
            val tm = a.getSystemService(TelephonyManager::class.java)
            // Android dinleyiciye zayıf referans tutar: alanda saklanmazsa çöp toplanır ve olaylar kesilir.
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onTelephony(state, phoneNumber)
            }
            listener = l
            @Suppress("DEPRECATION")
            tm?.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        }.onFailure { Log.w(TAG, "arama durumu dinlenemiyor: $it") }
        Log.i(TAG, "hazır (HFP: ${HfpClient.available})")
    }

    fun canAnswerCalls(context: Context) =
        context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    // ── Kaynaklar ──

    internal fun onInCall(info: CallInfo?) = main.post { inCall = info; update() }

    private fun onTelephony(state: Int, number: String?) {
        val prev = lastTelState
        lastTelState = state
        tel = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> CallInfo(number?.takeIf { it.isNotBlank() }, null, CallPhase.Incoming, source = "telephony")
            TelephonyManager.CALL_STATE_OFFHOOK -> CallInfo(
                number?.takeIf { it.isNotBlank() } ?: tel?.number, null,
                // Çalarken açıldıysa görüşme başlamıştır; boştan açıldıysa biz arıyoruz ama karşı taraf açtı mı bilinmez.
                if (prev == TelephonyManager.CALL_STATE_RINGING || tel?.phase == CallPhase.Active) CallPhase.Active else CallPhase.Ongoing,
                source = "telephony",
            )
            else -> null
        }
        Log.i(TAG, "telephony durum $state")
        refreshHfp()
    }

    /** Arama penceresi HFP olayından önce açıldıysa durumu hemen yeniden okur (CallOverlay çağırır). */
    fun refresh() { main.post { refreshHfp() } }

    private fun refreshHfp() {
        val calls = if (HfpClient.available) HfpClient.calls() else emptyList()
        val c = calls.firstOrNull { it.phase == CallPhase.Incoming }
            ?: calls.firstOrNull { it.phase == CallPhase.Active }
            ?: calls.firstOrNull()
        hfpCall = c
        hfp = c?.phase?.let { phase ->
            CallInfo(
                number = c.number, name = null, phase = phase,
                audioOnPhone = HfpClient.audioInCar()?.not(),
                canDtmf = true, canTransfer = true, source = "hfp",
            )
        }
        update()
    }

    /** BYD'nin arama penceresinden okunanlar (CallOverlay verir). */
    fun onScreen(parsed: CallScreenText.Parsed?) {
        main.post {
            // Okunan alanlar birikir: arama ekranı arkaya alınınca da ad kalsın.
            val merged = parsed?.let { p ->
                val old = screen
                CallScreenText.Parsed(p.name ?: old?.name, p.number ?: old?.number, p.phase ?: old?.phase, p.durationSec ?: old?.durationSec)
            }
            if (merged == screen) return@post
            screen = merged
            update()
        }
    }

    private fun live(): CallInfo? = inCall?.takeIf { it.phase != CallPhase.Ended } ?: hfp ?: tel

    private fun update() {
        val base = live()
        if (base == null) {
            val prev = last
            screen = null
            activeSince = null; activeKey = null
            if (prev != null && prev.phase != CallPhase.Ended) {
                micMuted = false
                runCatching { app?.getSystemService(AudioManager::class.java)?.isMicrophoneMute = false }
                endedAt = SystemClock.elapsedRealtime()
                _state.value = prev.copy(phase = CallPhase.Ended, endedAt = endedAt)
                last = _state.value
                main.removeCallbacks(clearEnded)
                main.postDelayed(clearEnded, ENDED_MS)
            }
            return
        }
        main.removeCallbacks(clearEnded)
        val s = screen
        val number = base.number ?: inCall?.number ?: hfp?.number ?: tel?.number ?: s?.number
        // Telephony yalnızca "sürüyor" diyorsa BYD ekranındaki durum daha bilgili olabilir.
        val phase = if (base.phase == CallPhase.Ongoing && s?.phase != null && s.phase != CallPhase.Ended) s.phase else base.phase

        val key = number ?: "?"
        if (key != activeKey) { activeKey = key; activeSince = null }
        if (phase == CallPhase.Active) {
            val fromSource = base.activeSince
            val fromScreen = s?.durationSec?.let { SystemClock.elapsedRealtime() - it * 1000 }
            activeSince = fromSource ?: activeSince ?: fromScreen
                ?: if (base.source != "telephony" || tel?.phase == CallPhase.Active) SystemClock.elapsedRealtime() else null
        }
        val name = base.name ?: number?.let { lookupName(it) } ?: s?.name?.takeIf { it != number }
        val info = base.copy(
            number = number,
            name = name,
            phase = phase,
            activeSince = if (phase == CallPhase.Active || phase == CallPhase.Holding) activeSince else null,
            muted = if (base.source == "incall") base.muted else micMuted,
            audioOnPhone = hfp?.audioOnPhone ?: base.audioOnPhone,
            canDtmf = inCall != null || hfp != null,
            canTransfer = hfp != null,
        )
        if (info != _state.value) {
            if (_state.value == null || _state.value?.phase == CallPhase.Ended) Log.i(TAG, "arama başladı: ${info.phase} (${info.source})")
            _state.value = info
        }
        last = info
        main.removeCallbacks(poll)
        main.postDelayed(poll, 1000)
    }

    /** Pano'nun okuyabildiği rehberde numaranın adı (önbellekli, arka planda). */
    private fun lookupName(number: String): String? {
        val key = PhoneBook.digits(number)
        if (names.containsKey(key)) return names[key]
        names[key] = null
        val a = app ?: return null
        if (!PhoneBook.canReadContacts(a)) return null
        io.execute {
            val found = runCatching {
                a.contentResolver.query(
                    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            if (found != null) main.post { names[key] = found; update() }
        }
        return null
    }

    // ── Eylemler ──

    private fun telecom() = app?.getSystemService(TelecomManager::class.java)

    @SuppressLint("MissingPermission")
    fun answer() {
        val ok = PanoInCallService.instance?.answer() == true ||
            HfpClient.accept() ||
            (app?.let { canAnswerCalls(it) } == true && runCatching { telecom()?.acceptRingingCall(); true }.getOrDefault(false)) ||
            screenAction?.invoke(Action.Answer) == true
        Log.i(TAG, "cevapla → $ok")
    }

    @SuppressLint("MissingPermission")
    fun reject() {
        val ok = PanoInCallService.instance?.reject() == true ||
            HfpClient.reject() ||
            endCallTelecom() ||
            screenAction?.invoke(Action.Reject) == true
        Log.i(TAG, "reddet → $ok")
    }

    fun hangUp() {
        val ok = PanoInCallService.instance?.hangUp() == true ||
            (hfpCall != null && HfpClient.terminate(hfpCall)) ||
            endCallTelecom() ||
            screenAction?.invoke(Action.HangUp) == true
        Log.i(TAG, "kapat → $ok")
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun endCallTelecom(): Boolean {
        val a = app ?: return false
        if (!canAnswerCalls(a)) return false
        return runCatching { telecom()?.endCall() == true }.onFailure { Log.w(TAG, "endCall: $it") }.getOrDefault(false)
    }

    fun toggleMute() {
        val on = !(_state.value?.muted ?: false)
        val svc = PanoInCallService.instance
        val ok = if (svc != null) svc.mute(on) else {
            // Telecom'a bağlı değilken mikrofon AudioManager ile kapatılır; olmazsa BYD'nin düğmesi.
            runCatching {
                val am = app!!.getSystemService(AudioManager::class.java)
                am.isMicrophoneMute = on
                am.isMicrophoneMute == on
            }.getOrDefault(false) || screenAction?.invoke(Action.Mute) == true
        }
        if (ok && svc == null) micMuted = on
        Log.i(TAG, "sessiz $on → $ok")
        update()
    }

    fun dtmf(key: Char) {
        val ok = PanoInCallService.instance?.dtmf(key) == true || HfpClient.dtmf(key)
        Log.i(TAG, "tuş $key → $ok")
    }

    /** Sesi telefona aktarır; telefondaysa araca geri alır. */
    fun transfer() {
        val toPhone = _state.value?.audioOnPhone != true
        val ok = HfpClient.transfer(toPhone) || screenAction?.invoke(Action.Transfer) == true
        Log.i(TAG, "aktar (telefona: $toPhone) → $ok")
        main.postDelayed({ refreshHfp() }, 600)
    }
}
