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
 *
 * Araçta mikrofonu BYD'nin kendi sesli asistanı da tutabilir. Okuma hata verirse ya da uzun süre
 * tamamen sessiz (sıfır) veri gelirse döngü durur ve [onError] çağrılır; böylece iş parçacığı
 * işlemciyi boşuna meşgul etmez.
 *
 * [AudioRecord] ve efektler yalnızca okuma iş parçacığında bırakılır: [stop] ana iş parçacığını
 * bekletmez.
 */
class LiveMic(
    private val onChunk: (ByteArray, Float) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false

    /** RECORD_AUDIO izni çağırandan önce denetlenir. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val rate = LiveProtocol.INPUT_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            Log.w(TAG, "mikrofon biçimi desteklenmiyor ($min)")
            return false
        }
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "mikrofon açılamadı")
            r?.release()
            return false
        }
        // Hoparlörden gelen asistan sesini bastırmaya yardım eder (cihaz destekliyorsa).
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val started = runCatching { r.startRecording() }.isSuccess && r.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            Log.w(TAG, "mikrofon kayda başlamadı (başka bir uygulama kullanıyor olabilir)")
            release(r, aec, ns)
            return false
        }
        running = true
        Thread({ loop(r, rate) ; release(r, aec, ns) }, "PanoAsistanMikrofon").start()
        Log.i(TAG, "mikrofon açık (yankı bastırma: ${aec != null}, gürültü: ${ns != null})")
        return true
    }

    private fun loop(r: AudioRecord, rate: Int) {
        val buf = ByteArray(rate / 25 * 2) // 40 ms
        val end = readLoop(
            buf = buf,
            read = { b -> r.read(b, 0, b.size) },
            isRunning = { running },
            onChunk = onChunk,
            now = SystemClock::uptimeMillis,
            sleep = SystemClock::sleep,
        )
        when (end) {
            MicEnd.Stopped -> Unit
            MicEnd.ReadErrors -> {
                Log.w(TAG, "mikrofon okunamıyor; kapatılıyor")
                if (running) { running = false; onError("Mikrofon okunamıyor.") }
            }
            MicEnd.Muted -> {
                Log.w(TAG, "mikrofondan yalnızca sessizlik geliyor; kapatılıyor")
                if (running) { running = false; onError("Mikrofon başka bir uygulamada (araç asistanı?).") }
            }
        }
    }

    /** Okuma döngüsünün neden bittiği. */
    internal enum class MicEnd { Stopped, ReadErrors, Muted }

    private fun release(r: AudioRecord, aec: AcousticEchoCanceler?, ns: NoiseSuppressor?) {
        runCatching { r.stop() }
        runCatching { r.release() }
        runCatching { aec?.release() }
        runCatching { ns?.release() }
    }

    /** Döngüyü durdurur; kaynakları okuma iş parçacığı en geç bir okuma (40 ms) sonra bırakır. */
    fun stop() {
        running = false
    }

    companion object {
        private const val TAG = "PanoAsistan"
        /** Arka arkaya bu kadar hatalı okumadan sonra mikrofon bırakılır (~0,5 sn). */
        private const val MAX_ERRORS = 10
        private const val ERROR_WAIT_MS = 50L
        /** Bu kadar süre tam sıfır gelirse mikrofon başka yerde demektir. */
        internal const val MUTE_LIMIT_MS = 3_000L

        /**
         * Mikrofon okuma döngüsü. Android'e bağımlı değildir (okuma, saat ve bekleme dışarıdan
         * verilir); böylece hata davranışı JVM'de test edilebilir.
         *
         * - Hatalı ya da boş okumada [ERROR_WAIT_MS] beklenir; [MAX_ERRORS] kez üst üste olursa biter.
         * - [MUTE_LIMIT_MS]'den uzun süre tam sıfır gelirse biter (mikrofon başka uygulamada).
         */
        internal fun readLoop(
            buf: ByteArray,
            read: (ByteArray) -> Int,
            isRunning: () -> Boolean,
            onChunk: (ByteArray, Float) -> Unit,
            now: () -> Long,
            sleep: (Long) -> Unit,
        ): MicEnd {
            var errors = 0
            var silentSince = -1L
            while (isRunning()) {
                val n = read(buf)
                if (n <= 0) {
                    // Beklemeden tekrar denemek bir çekirdeği %100 meşgul eder.
                    if (++errors >= MAX_ERRORS) return MicEnd.ReadErrors
                    sleep(ERROR_WAIT_MS)
                    continue
                }
                errors = 0
                val chunk = buf.copyOf(n)
                val level = rms(chunk)
                // Android 10'da mikrofonu başka bir uygulama tutuyorsa hata yerine sıfırlarla dolu veri gelir.
                if (level == 0f) {
                    val t = now()
                    if (silentSince < 0) silentSince = t
                    else if (t - silentSince > MUTE_LIMIT_MS) return MicEnd.Muted
                } else {
                    silentSince = -1L
                }
                onChunk(chunk, level)
            }
            return MicEnd.Stopped
        }

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

/**
 * Gelen 24 kHz PCM sesi asistan sesi olarak çalar.
 *
 * Önce `USAGE_ASSISTANT` denenir; araçtaki ses düzeni bunu kabul etmezse medya kanalına düşülür.
 * [AudioTrack] yalnızca çalma iş parçacığında bırakılır: [stop] ana iş parçacığını bekletmez.
 */
class LiveSpeaker {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false

    /** Kuyruğa yazılan sesin tahmini bitiş anı (uptime ms). */
    @Volatile private var endsAt = 0L

    /** Ses çıkışı açılamazsa false döner; hiçbir durumda istisna fırlatmaz. */
    fun start(): Boolean {
        if (running) return true
        val t = build(AudioAttributes.USAGE_ASSISTANT) ?: build(AudioAttributes.USAGE_MEDIA)
        if (t == null) {
            Log.w(TAG, "ses çıkışı açılamadı")
            return false
        }
        if (runCatching { t.play() }.isFailure) {
            Log.w(TAG, "ses çıkışı başlatılamadı")
            runCatching { t.release() }
            return false
        }
        track = t
        running = true
        Thread({
            while (running) {
                val chunk = runCatching { queue.take() }.getOrNull() ?: continue
                if (!running) break
                if (chunk.isNotEmpty() && runCatching { t.write(chunk, 0, chunk.size) }.getOrDefault(-1) < 0) {
                    Log.w(TAG, "ses yazılamadı")
                    SystemClock.sleep(20)
                }
            }
            runCatching { t.stop() }
            runCatching { t.release() }
        }, "PanoAsistanHoparlor").start()
        return true
    }

    private fun build(usage: Int): AudioTrack? = runCatching {
        val rate = LiveProtocol.OUTPUT_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
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
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); null } else t
    }.onFailure { Log.w(TAG, "ses çıkışı kurulamadı (usage $usage): ${it.javaClass.simpleName}") }.getOrNull()

    fun play(pcm: ByteArray) {
        if (!running) return
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

    /** Çalmayı durdurur; kaynakları çalma iş parçacığı bırakır. */
    fun stop() {
        running = false
        queue.clear()
        queue.offer(ByteArray(0))
        track = null
        endsAt = 0L
    }

    private companion object {
        const val TAG = "PanoAsistan"
        /** Hoparlörün son parçayı bitirmesi ve yankının sönmesi için pay. */
        const val TAIL_MS = 350L
    }
}

/**
 * Oturum boyunca ses odağı: müzik kısılır (ducking), oturum bitince geri gelir.
 * [release] birden çok kez çağrılabilir; yalnızca alınmış odak bırakılır.
 */
class LiveFocus(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()
    @Volatile private var held = false

    fun acquire() {
        val r = runCatching { am?.requestAudioFocus(request) }.getOrNull()
        held = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!held) Log.w("PanoAsistan", "ses odağı alınamadı ($r)")
    }

    fun release() {
        if (!held) return
        held = false
        runCatching { am?.abandonAudioFocusRequest(request) }
    }
}
