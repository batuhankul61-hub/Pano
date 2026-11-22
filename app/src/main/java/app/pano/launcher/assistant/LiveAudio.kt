package app.pano.launcher.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

/**
 * Mikrofon: 16 kHz, mono, 16 bit PCM; 40 ms'lik parçalar halinde [onChunk]'a verir.
 * Yalnızca asistan oturumu açıkken çalışır.
 */
class LiveMic(private val onChunk: (ByteArray, Float) -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    /** RECORD_AUDIO izni çağırandan önce denetlenir. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val rate = LiveProtocol.INPUT_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "mikrofon açılamadı")
            r?.release()
            return false
        }
        // Hoparlörden gelen asistan sesini bastırmaya yardım eder (cihaz destekliyorsa).
        if (AcousticEchoCanceler.isAvailable()) aec = runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        if (NoiseSuppressor.isAvailable()) ns = runCatching { NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        record = r
        running = true
        r.startRecording()
        thread = Thread({
            val buf = ByteArray(rate / 25 * 2) // 40 ms
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                val chunk = buf.copyOf(n)
                onChunk(chunk, rms(chunk))
            }
        }, "PanoAsistanMikrofon").apply { start() }
        Log.i(TAG, "mikrofon açık (yankı bastırma: ${aec != null}, gürültü: ${ns != null})")
        return true
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
        runCatching { record?.stop() }
        record?.release()
        record = null
        aec?.release(); aec = null
        ns?.release(); ns = null
    }

    companion object {
        private const val TAG = "PanoAsistan"

        /** 0..1 arası ses seviyesi (kök ortalama kare). */
        fun rms(pcm: ByteArray): Float {
            var sum = 0.0
            val n = pcm.size / 2
            if (n == 0) return 0f
            for (i in 0 until n) {
                val s = (pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)
                sum += s.toDouble() * s
            }
            return (sqrt(sum / n) / 32768.0).toFloat()
        }
    }
}

/** Gelen 24 kHz PCM sesi asistan sesi olarak çalar. */
class LiveSpeaker {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Kuyruğa yazılan sesin tahmini bitiş anı (uptime ms). */
    @Volatile private var endsAt = 0L

    fun start() {
        if (running) return
        val rate = LiveProtocol.OUTPUT_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        running = true
        t.play()
        thread = Thread({
            while (running) {
                val chunk = runCatching { queue.take() }.getOrNull() ?: continue
                if (!running) break
                if (chunk.isNotEmpty()) t.write(chunk, 0, chunk.size)
            }
        }, "PanoAsistanHoparlor").apply { start() }
    }

    fun play(pcm: ByteArray) {
        val ms = pcm.size / 2 * 1000L / LiveProtocol.OUTPUT_RATE
        val now = SystemClock.uptimeMillis()
        endsAt = maxOf(endsAt, now) + ms
        queue.offer(pcm)
    }

    /** Asistan hâlâ konuşuyor mu (kuyrukta ya da çalınıyor). */
    val isPlaying: Boolean get() = SystemClock.uptimeMillis() < endsAt + TAIL_MS

    /** Söz kesildi: bekleyen sesi at. */
    fun flush() {
        queue.clear()
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
        endsAt = 0L
    }

    fun stop() {
        running = false
        queue.clear()
        queue.offer(ByteArray(0))
        thread?.join(300)
        thread = null
        runCatching { track?.stop() }
        track?.release()
        track = null
        endsAt = 0L
    }

    private companion object {
        /** Hoparlörün son parçayı bitirmesi ve yankının sönmesi için pay. */
        const val TAIL_MS = 350L
    }
}

/** Oturum boyunca ses odağı: müzik kısılır (ducking), oturum bitince geri gelir. */
class LiveFocus(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()

    fun acquire() { am?.requestAudioFocus(request) }
    fun release() { am?.abandonAudioFocusRequest(request) }
}
