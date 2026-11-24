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
    /** Etkin kayıtların biçimini sormak için (araçta BYD asistanının açık tuttuğu biçimle eşleşmek). */
    private val audio: AudioManager? = null,
) {
    @Volatile private var running = false

    /**
     * Denenecek kayıt biçimleri, sırayla: son çalışan, mono, sistemde etkin kaydın biçimi, 5/4/2 kanal.
     *
     * Araçta (BYD DiLink 3.0, 24.11.2026 denemeleri):
     * - BYD'nin asistanı "Hey BYD" için mikrofonu açık tutarken Pano'ya hiçbir biçimde ses gelmiyor
     *   (yalnızca sıfır; aynı biçimi istemek de akışı paylaştırmıyor).
     * - Mikrofon boştayken 5 kanal istenince ilk iki kanaldan sürekli ~0,5 seviyesinde anlamsız veri
     *   geliyor (gerçek ses değil); bu yüzden mono önce denenir.
     * Bir biçimden [MUTE_LIMIT_MS] yalnızca sıfır ya da [GARBAGE_MS] sürekli aşırı yüksek seviye gelirse
     * sıradakine geçilir; ses veren biçim hatırlanır.
     */
    private fun candidates(): List<Int> {
        val active = runCatching {
            audio?.activeRecordingConfigurations.orEmpty()
                .filter { it.clientAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION && it.clientFormat.sampleRate == LiveProtocol.INPUT_RATE }
                .map { it.clientFormat.channelCount }
        }.getOrDefault(emptyList())
        return (listOfNotNull(lastGood) + 1 + active + CHANNEL_TRIES).distinct()
    }

    /** RECORD_AUDIO izni çağırandan önce denetlenir. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val rate = LiveProtocol.INPUT_RATE
        val tries = candidates().toMutableList()
        var r: AudioRecord? = null
        while (r == null && tries.isNotEmpty()) {
            val n = tries.removeAt(0)
            r = open(rate, n)?.takeIf { rec ->
                val ok = runCatching { rec.startRecording() }.isSuccess && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
                if (!ok) rec.release()
                ok
            }
            if (r != null) channels = n
        }
        if (r == null) {
            Log.w(TAG, "mikrofon açılamadı")
            return false
        }
        running = true
        Thread({
            var rec: AudioRecord = r
            while (true) {
                val end = loop(rec, rate)
                runCatching { rec.stop() }
                runCatching { rec.release() }
                if ((end != MicEnd.Muted && end != MicEnd.Garbage) || !running) { report(end); break }
                // Bu biçim ses vermedi (sessizlik ya da anlamsız veri): sıradakini dene.
                Log.w(TAG, "$channels kanaldan ${if (end == MicEnd.Muted) "yalnızca sessizlik" else "anlamsız (sürekli aşırı yüksek) veri"} geldi; sıradaki biçim deneniyor")
                var next: AudioRecord? = null
                while (next == null && tries.isNotEmpty() && running) {
                    val n = tries.removeAt(0)
                    next = open(rate, n)?.takeIf { x ->
                        val ok = runCatching { x.startRecording() }.isSuccess && x.recordingState == AudioRecord.RECORDSTATE_RECORDING
                        if (!ok) x.release()
                        ok
                    }
                    if (next != null) { channels = n; Log.i(TAG, "mikrofon yeniden açıldı ($n kanal)") }
                }
                if (next == null) { report(end); break }
                rec = next
            }
        }, "PanoAsistanMikrofon").start()
        Log.i(TAG, "mikrofon açık ($channels kanal; sıradaki biçimler: $tries)")
        return true
    }

    private fun report(end: MicEnd) {
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
            MicEnd.Garbage -> {
                Log.w(TAG, "mikrofondan anlamsız veri geliyor; kapatılıyor")
                if (running) { running = false; onError("Mikrofondan anlaşılır ses alınamıyor.") }
            }
        }
    }

    @Volatile private var channels = 1

    /** [n] kanallı (1 = mono) kayıt açar; olmazsa null. Efekt eklenmez: mevcut akışa bağlanmayı engelleyebilir. */
    @SuppressLint("MissingPermission")
    private fun open(rate: Int, n: Int): AudioRecord? {
        val format = AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).run {
            if (n == 1) setChannelMask(AudioFormat.CHANNEL_IN_MONO) else setChannelIndexMask((1 shl n) - 1)
        }.build()
        val r = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(rate * 2 * n)
                .build()
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED || r.channelCount != n) {
            r?.release()
            return null
        }
        return r
    }

    /** Bir biçimle okur; bitiş nedenini döndürür. Ses geldiyse biçim sonraki oturumlar için hatırlanır. */
    private fun loop(r: AudioRecord, rate: Int): MicEnd {
        val n = channels
        var heard = false
        val buf = ByteArray(rate / 25 * 2) // 40 ms, mono
        val multi = ByteArray(buf.size * n)
        val energy = FloatArray(n)
        var lastLog = 0L
        val end = readLoop(
            buf = buf,
            read = { b ->
                if (n == 1) r.read(b, 0, b.size) else {
                    val got = r.read(multi, 0, multi.size)
                    if (got <= 0) got else {
                        val frames = got / (2 * n)
                        pickLoudest(multi, n, frames, b, energy)
                        // Hangi kanalın konuşmayı taşıdığı araçta logdan görülsün (2 sn'de bir).
                        val now = SystemClock.uptimeMillis()
                        if (now - lastLog > 2_000) {
                            lastLog = now
                            Log.i(TAG, "kanal seviyeleri: ${energy.joinToString(" ") { "%.4f".format(java.util.Locale.US, kotlin.math.sqrt(it)) }}")
                        }
                        frames * 2
                    }
                }
            },
            isRunning = { running },
            onChunk = { chunk, level ->
                if (!heard && level > 0f) {
                    heard = true
                    if (lastGood != n) { lastGood = n; Log.i(TAG, "mikrofondan ses geliyor ($n kanal); bu biçim hatırlandı") }
                }
                onChunk(chunk, level)
            },
            now = SystemClock::uptimeMillis,
            sleep = SystemClock::sleep,
        )
        // Bu biçim ses vermediyse bir dahaki oturumda önce denenmesin.
        if ((end == MicEnd.Muted || end == MicEnd.Garbage) && lastGood == n) lastGood = null
        return end
    }

    /** Okuma döngüsünün neden bittiği. [Garbage]: sürekli aşırı yüksek seviye (biçim yanlış yorumlanıyor). */
    internal enum class MicEnd { Stopped, ReadErrors, Muted, Garbage }

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
        internal const val MUTE_LIMIT_MS = 2_000L
        /** Denenecek kanal sayıları: araçtaki 5 kanallı dizi, olası 4/2 kanallılar, en son mono. */
        private val CHANNEL_TRIES = listOf(5, 4, 2, 1)
        /** Bu seviyenin üstü [GARBAGE_MS] boyunca hiç düşmezse veri gerçek ses değildir (rastgele gürültü ~0,58). */
        internal const val GARBAGE_LEVEL = 0.3f
        internal const val GARBAGE_MS = 1_500L
        /** Son oturumda ses veren kanal sayısı; sonraki oturum önce bunu dener. */
        @Volatile private var lastGood: Int? = null

        /**
         * Çok kanallı PCM'den [out]'a tek kanal çıkarır: kanal başına enerji yavaşça ortalanır ([energy],
         * yaklaşık 1 sn) ve en güçlü kanal kopyalanır. Parça parça kanal atlaması olmasın diye ortalama
         * kullanılır. Döndürdüğü: seçilen kanal. Saf işlev: birim testleri doğrudan dener.
         */
        internal fun pickLoudest(multi: ByteArray, n: Int, frames: Int, out: ByteArray, energy: FloatArray): Int {
            for (ch in 0 until n) {
                var sum = 0.0
                for (f in 0 until frames) {
                    val i = (f * n + ch) * 2
                    val s = (multi[i].toInt() and 0xff) or (multi[i + 1].toInt() shl 8)
                    sum += s.toDouble() * s
                }
                val ms = if (frames > 0) (sum / frames / (32768.0 * 32768.0)).toFloat() else 0f
                energy[ch] += (ms - energy[ch]) * 0.04f // 40 ms parçalar: ~1 sn
            }
            var best = 0
            for (ch in 1 until n) if (energy[ch] > energy[best]) best = ch
            for (f in 0 until frames) {
                val i = (f * n + best) * 2
                out[f * 2] = multi[i]
                out[f * 2 + 1] = multi[i + 1]
            }
            return best
        }

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
            var loudSince = -1L
            while (isRunning()) {
                val n = read(buf)
                if (n <= 0) {
                    // Veri gelmedi: "kesintisiz yüksek seviye" de kesilmiş sayılır.
                    loudSince = -1L
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
                // Konuşma bile bu seviyede kesintisiz kalmaz: biçim yanlış yorumlanıyordur.
                if (level > GARBAGE_LEVEL) {
                    val t = now()
                    if (loudSince < 0) loudSince = t
                    else if (t - loudSince > GARBAGE_MS) return MicEnd.Garbage
                } else {
                    loudSince = -1L
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
 * Oturum boyunca ses odağı: odağa uyan uygulamalar duraklar (kısmak yetmiyordu: araçta müzik yüksek
 * kalınca mikrofon konuşmayı duymuyordu), oturum bitince geri gelir. Odağa uymayanları
 * [app.pano.launcher.media.MediaHub.pauseForAssistant] ayrıca duraklatır.
 * [release] birden çok kez çağrılabilir; yalnızca alınmış odak bırakılır.
 */
class LiveFocus(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
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
