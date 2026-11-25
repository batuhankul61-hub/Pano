package app.pano.launcher.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import app.pano.launcher.system.HiddenApi

/**
 * Aracın Bluetooth "eller serbest" istemcisi (AOSP `BluetoothHeadsetClient`, profil 16). Araçta telefon
 * bu profille bağlı (`HeadsetClientService`, 25.11.2026'da `dumpsys bluetooth_manager` ile görüldü) ve
 * BYD'nin arama uygulaması da aramaları bundan okuyor (Telecom'da varsayılan çevirici yok, BYD
 * InCallService kullanmıyor). Sınıf gizli API olduğu için yansımayla çağrılır; izin olarak yalnızca
 * BLUETOOTH (Android 10'da normal izin) gerekir.
 *
 * Verdiği: aramanın numarası ve durumu, cevapla / reddet / kapat, DTMF ve sesi telefona aktarma.
 */
object HfpClient {
    private const val TAG = "HfpClient"
    private const val HEADSET_CLIENT = 16
    const val ACTION_CALL_CHANGED = "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"
    const val ACTION_AUDIO_STATE_CHANGED = "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"

    /** BluetoothHeadsetClientCall durumları. */
    private const val CALL_ACTIVE = 0
    private const val CALL_HELD = 1
    private const val CALL_DIALING = 2
    private const val CALL_ALERTING = 3
    private const val CALL_INCOMING = 4
    private const val CALL_WAITING = 5
    private const val CALL_HELD_BY_RESPONSE_AND_HOLD = 6
    private const val CALL_TERMINATED = 7

    /** BluetoothHeadsetClient.STATE_AUDIO_CONNECTED */
    private const val AUDIO_CONNECTED = 2

    data class Call(val raw: Any, val number: String?, val state: Int, val outgoing: Boolean) {
        val phase: CallPhase? get() = when (state) {
            CALL_ACTIVE -> CallPhase.Active
            CALL_HELD, CALL_HELD_BY_RESPONSE_AND_HOLD -> CallPhase.Holding
            CALL_DIALING, CALL_ALERTING -> CallPhase.Dialing
            CALL_INCOMING, CALL_WAITING -> CallPhase.Incoming
            else -> null
        }
    }

    @Volatile private var proxy: BluetoothProfile? = null
    private var receiver: BroadcastReceiver? = null
    private var onChange: (() -> Unit)? = null

    val available: Boolean get() = proxy != null

    @SuppressLint("MissingPermission")
    fun init(context: Context, changed: () -> Unit) {
        if (receiver != null) return
        onChange = changed
        HiddenApi.exempt("Landroid/bluetooth/")
        val app = context.applicationContext
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@runCatching
            adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                    proxy = p
                    Log.i(TAG, "HFP istemcisi bağlandı")
                    onChange?.invoke()
                }
                override fun onServiceDisconnected(profile: Int) { proxy = null }
            }, HEADSET_CLIENT)
        }.onFailure { Log.w(TAG, "HFP istemcisi alınamadı: $it") }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                Log.d(TAG, "yayın: ${intent?.action}")
                onChange?.invoke()
            }
        }.also {
            val filter = IntentFilter().apply { addAction(ACTION_CALL_CHANGED); addAction(ACTION_AUDIO_STATE_CHANGED) }
            runCatching { ContextCompat.registerReceiver(app, it, filter, ContextCompat.RECEIVER_EXPORTED) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun device(): BluetoothDevice? = runCatching { proxy?.connectedDevices?.firstOrNull() }.getOrNull()

    private fun call(name: String, vararg args: Any?): Any? {
        val p = proxy ?: return null
        val m = p.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size } ?: run {
            Log.w(TAG, "$name yok"); return null
        }
        return runCatching { m.invoke(p, *args) }.onFailure { Log.w(TAG, "$name: ${it.cause ?: it}") }.getOrNull()
    }

    /** Telefondaki şu anki aramalar (bitenler hariç). */
    fun calls(): List<Call> {
        val d = device() ?: return emptyList()
        val list = call("getCurrentCalls", d) as? List<*> ?: return emptyList()
        return list.mapNotNull { c ->
            c ?: return@mapNotNull null
            runCatching {
                val cls = c.javaClass
                val state = cls.getMethod("getState").invoke(c) as Int
                val number = cls.getMethod("getNumber").invoke(c) as String?
                val outgoing = runCatching { cls.getMethod("isOutgoing").invoke(c) as Boolean }.getOrDefault(false)
                Call(c, number?.takeIf { it.isNotBlank() }, state, outgoing)
            }.getOrNull()
        }.filter { it.state != CALL_TERMINATED }
    }

    /** Ses araçta mı (SCO bağlı); bilinmiyorsa null. */
    fun audioInCar(): Boolean? {
        val d = device() ?: return null
        return (call("getAudioState", d) as? Int)?.let { it == AUDIO_CONNECTED }
    }

    fun accept(): Boolean = device()?.let { call("acceptCall", it, 0) as? Boolean } == true
    fun reject(): Boolean = device()?.let { call("rejectCall", it) as? Boolean } == true

    fun terminate(c: Call?): Boolean {
        val d = device() ?: return false
        return call("terminateCall", d, c?.raw) as? Boolean == true
    }

    fun dtmf(key: Char): Boolean = device()?.let { call("sendDTMF", it, key.code.toByte()) as? Boolean } == true

    /** Sesi telefona aktarır (`toPhone`) ya da araca geri alır. */
    fun transfer(toPhone: Boolean): Boolean {
        val d = device() ?: return false
        return call(if (toPhone) "disconnectAudio" else "connectAudio", d) as? Boolean == true
    }
}
