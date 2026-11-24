package app.pano.launcher.assistant

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class AssistantPhase { Idle, Connecting, Listening, Thinking, Speaking, Error }

data class AssistantUi(
    val phase: AssistantPhase = AssistantPhase.Idle,
    /** Kullanıcının son söylediği (Google'ın döküm metni). */
    val heard: String = "",
    /** Asistanın son cevabının metni. */
    val reply: String = "",
    val error: String? = null,
    /** 0..1 mikrofon seviyesi (gösterge için). */
    val level: Float = 0f,
)

/**
 * Pano Asistan: dock'taki düğmeyle açılan, Gemini Live ile konuşan sesli asistan.
 *
 * Akış: WebSocket → `setup` → `setupComplete` → mikrofon sesi akar → model sesle cevap verir,
 * gerekirse araç çağırır ([AssistantTools] → [PanoBackend]). Bir süre konuşulmazsa oturum
 * kendiliğinden kapanır. Sürekli dinleme ya da uyandırma sözcüğü yok; mikrofon yalnızca oturum
 * açıkken çalışır. Konuşma kaydı tutulmaz, API anahtarı loglanmaz.
 *
 * Yankı: araç hoparlöründen çıkan cevap mikrofona geri girip modelin kendi sözünü kesmesin diye
 * asistan konuşurken mikrofon sesi gönderilmez (yarı çift yönlü). Söz kesmek için ✕.
 */
object PanoAssistant {
    private const val TAG = "PanoAsistan"

    /** Bu kadar süre konuşma olmazsa oturum kapanır. */
    private const val IDLE_TIMEOUT_MS = 12_000L
    /** Cevap bu kadar gecikirse yeniden dinlemeye dönülür. */
    private const val THINKING_TIMEOUT_MS = 20_000L
    /** Maliyet ve gizlilik için bir oturumun üst sınırı. */
    private const val MAX_SESSION_MS = 10 * 60_000L
    /** Konuşma başlamadan önceki bu kadar ses parçası (× 40 ms) da gönderilir: ilk hece kırpılmasın. */
    private const val PRE_ROLL_CHUNKS = 8
    /** Konuşma bittikten sonra bu sürede Google hiç tepki vermezse ses yeniden gönderilir. */
    private const val NO_REPLY_MS = 3_000L
    /** Yeniden gönderim için saklanan en uzun konuşma (× 40 ms = 15 sn). */
    private const val MAX_TURN_CHUNKS = 375

    val SYSTEM_PROMPT = """
        Sen Pano Asistan'sın: bir BYD elektrikli otomobilin ekranındaki sesli asistan. Kullanıcı büyük ihtimalle araç kullanıyor.
        - Her zaman Türkçe konuş. Cevapların kısa olsun: çoğu zaman tek cümle. Liste okuma, gereksiz açıklama yapma.
        - Sakin, net ve dikkat dağıtmayan bir üslup kullan. Sürücüye ekrana bakmasını söyleme.
        - Klima, navigasyon, müzik, ses düzeyi, uygulama açma, konum, araç durumu ve hava durumu için araçlarını kullan. Bir işi yaptıysan ne yaptığını kısaca söyle.
        - "Sesi aç / kıs / biraz artır" müzik ve medyanın ses düzeyidir: set_volume kullan. Kendi konuşma sesini değiştiremezsin.
        - "Müzik aç" dendiğinde hiçbir şey çalmıyor olsa da media_control play çağır; müzik uygulamasını o açar.
        - "Radyoyu aç" için open_app ile "Radyo" çağır; çalan radyoysa media_control next/previous istasyon değiştirir. Telefon gibi diğer araç uygulamaları için de open_app'i dene; "yapamam" demeden önce bir kez dene.
        - "Yarın / hafta sonu hava" için weather sonucundaki forecast listesini kullan.
        - "Neredeyiz?" için current_location kullan.
        - Araçlarının kapsamadığı bilgiler (otobüs saatleri, haberler, güncel olaylar) için bilmediğini kısaca söyle.
        - Araç verisi yalnızca araçlardan gelir. Bir değer gelmediyse ya da "preview" true ise bilmediğini söyle; asla tahmin etme ya da uydurma.
        - Kapı kilidi, bagaj, ayna, cam, koltuk gibi fiziksel parçaları hareket ettiremezsin; istenirse bunu kısaca söyle.
        - Bir araç hata döndürürse sebebini tek cümleyle söyle.
        - Kullanıcı "tamam", "teşekkürler", "kapat" gibi bir şeyle bitirirse kısaca vedalaş.
    """.trimIndent()

    private val _ui = MutableStateFlow(AssistantUi())
    val ui: StateFlow<AssistantUi> = _ui.asStateFlow()

    private val http by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var ws: WebSocket? = null
    private var mic: LiveMic? = null
    private var speaker: LiveSpeaker? = null
    private var focus: LiveFocus? = null
    private var backend: AssistantBackend? = null
    /** Mikrofon, etkin kayıtların biçimini buradan sorar ([LiveMic]). */
    private var audioManager: android.media.AudioManager? = null
    private var ticker: Job? = null
    private val toolJobs = ConcurrentHashMap<String, Job>()

    @Volatile private var ready = false
    @Volatile private var lastActivity = 0L
    @Volatile private var lastVoice = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var newUserTurn = true
    /** Oturumdaki en yüksek mikrofon seviyesi: "sesimi duymuyor" sorununu logdan anlamak için. */
    @Volatile private var peakLevel = 0f
    /** Oturum özeti sayaçları (bkz. [stop]). */
    @Volatile private var sentBytes = 0L
    @Volatile private var sendFailures = 0
    @Volatile private var voiceEvents = 0
    @Volatile private var transcripts = 0
    @Volatile private var localTurns = 0

    /**
     * Konuşmanın başını ve sonunu Pano bulur (Google'ın otomatik algılaması kapalı, bkz. [TurnDetector]).
     * Yalnızca mikrofon iş parçacığından kullanılır; [preRoll] de öyle.
     */
    private var turns = TurnDetector()
    private val preRoll = ArrayDeque<ByteArray>()
    /** Son konuşmanın gönderilen sesi: Google cevap vermezse bir kez yeniden gönderilir. */
    private val turnAudio = ArrayList<ByteArray>()
    /** Konuşma bitti ve Google'dan tepki bekleniyor: o an (uptime ms); beklenmiyorsa 0. */
    @Volatile private var awaitingReplySince = 0L
    @Volatile private var replyRetried = false
    /** Yeni bağlantı kurulunca cevapsız kalan konuşma yeniden gönderilecek. */
    @Volatile private var resendAfterReconnect = false
    @Volatile private var retries = 0

    /** Konuşma günlüğü için turun parça parça gelen dökümleri ([ConversationLog]). */
    private val turnLock = Any()
    private val turnHeard = StringBuilder()
    private val turnReply = StringBuilder()
    @Volatile private var sessionLogged = false
    private var resumeHandle: String? = null
    private var apiKey: String? = null
    /** Her bağlantıya bir numara: eski bağlantının geç gelen olayları yok sayılır. */
    @Volatile private var generation = 0

    val isActive: Boolean get() = _ui.value.phase != AssistantPhase.Idle

    /**
     * Oturumu açar. [context] dock'a basılan ekranın Activity'si olmalı. Anahtar ve mikrofon izni
     * çağıran tarafından denetlenir ([rememberAssistantLauncher]).
     */
    fun start(context: Context) {
        if (isActive) return
        // Önce "bağlanıyor" görünür; Keystore araçta yavaş olabildiği için anahtar arka planda çözülür.
        _ui.value = AssistantUi(AssistantPhase.Connecting)
        val gen = ++generation
        scope.launch {
            val key = withContext(Dispatchers.IO) { AssistantKey.get(context) }
            if (gen != generation) return@launch // bu arada kapatıldı
            if (key.isNullOrEmpty()) {
                _ui.value = AssistantUi(AssistantPhase.Error, error = "API anahtarı yok. Pano Ayarları → Pano Asistan.")
                scope.launch { delay(6_000); if (_ui.value.phase == AssistantPhase.Error) stop() }
                return@launch
            }
            runCatching { begin(context, key) }.onFailure {
                Log.w(TAG, "oturum başlatılamadı: ${it.javaClass.simpleName}")
                fail("Asistan başlatılamadı.")
            }
        }
    }

    private fun begin(context: Context, key: String) {
        ConversationLog.init(context)
        ConversationLog.sessionStarted()
        sessionLogged = true
        apiKey = key
        backend = PanoBackend(context)
        audioManager = context.applicationContext.getSystemService(android.media.AudioManager::class.java)
        focus = LiveFocus(context.applicationContext).also { it.acquire() }
        // Çalan müzik/radyo asistan boyunca durur (araçta odak isteğine uymuyor), sonunda sürer.
        app.pano.launcher.media.MediaHub.pauseForAssistant()
        val sp = LiveSpeaker()
        if (!sp.start()) { fail("Ses çıkışı açılamadı."); return }
        speaker = sp
        resumeHandle = null
        startedAt = SystemClock.uptimeMillis()
        lastActivity = startedAt
        newUserTurn = true
        peakLevel = 0f
        sentBytes = 0L; sendFailures = 0; voiceEvents = 0; transcripts = 0; localTurns = 0; retries = 0
        awaitingReplySince = 0L; replyRetried = false; resendAfterReconnect = false
        synchronized(turnAudio) { turnAudio.clear() }
        // Mikrofon bu oturumda henüz açılmadı (SetupComplete'te açılır): yeni algılayıcı güvenle kurulur.
        turns = TurnDetector()
        preRoll.clear()
        connect()
        ticker = scope.launch { while (isActive) { tick(); delay(200) } }
    }

    /** Oturumu kapatır (✕, sessizlik ya da hata). [reason] yalnızca konuşma günlüğüne yazılır. */
    fun stop(reason: String = "kapatıldı") {
        flushHeard(); flushReply()
        if (sessionLogged) {
            sessionLogged = false
            // "Sesimi duymuyor" sorunlarında hangi halkanın koptuğunu gösterir: mikrofon → gönderim → Google.
            val summary = "gönderilen ses ${"%.1f".format(Locale.US, sentBytes / (LiveProtocol.INPUT_RATE * 2f))} sn, " +
                "başarısız gönderim $sendFailures, Google konuşma algılaması $voiceEvents, döküm parçası $transcripts, Pano'nun algıladığı konuşma $localTurns, yeniden gönderim $retries, " +
                "gürültü tabanı ${"%.3f".format(Locale.US, turns.noiseFloor)}, " +
                "mikrofon en yüksek ${"%.3f".format(Locale.US, peakLevel)}"
            Log.i(TAG, "oturum özeti: $summary")
            ConversationLog.sessionEnded("$reason ($summary)")
        }
        generation++
        ready = false
        ticker?.cancel(); ticker = null
        toolJobs.values.forEach { it.cancel() }; toolJobs.clear()
        ws?.close(1000, "kapatıldı"); ws = null
        mic?.stop(); mic = null
        speaker?.stop(); speaker = null
        focus?.release(); focus = null
        app.pano.launcher.media.MediaHub.resumeAfterAssistant()
        backend = null
        apiKey = null
        resumeHandle = null
        _ui.value = AssistantUi()
        Log.i(TAG, "oturum kapandı")
    }

    private fun connect() {
        val key = apiKey ?: return
        val gen = ++generation
        ready = false
        val request = Request.Builder().url(LiveProtocol.url(key)).build()
        // Adres anahtarı içerdiği için loglanmaz.
        Log.i(TAG, "bağlanıyor (${LiveProtocol.MODEL}${if (resumeHandle != null) ", sürdürülüyor" else ""})")
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                webSocket.send(LiveProtocol.setup(SYSTEM_PROMPT + "\n" + nowLine(), AssistantTools.declarations(), resumeHandle))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(gen, text)
            // Live API JSON'u çoğunlukla ikili çerçeveyle gönderir.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(gen, bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (gen != generation) return
                Log.w(TAG, "sunucu kapattı: $code ${reason.take(120)}")
                scope.launch { fail(closeMessage(code, reason)) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                Log.w(TAG, "bağlantı hatası: ${t.javaClass.simpleName} ${response?.code ?: ""}")
                scope.launch {
                    fail(
                        when (response?.code) {
                            400, 401, 403 -> "API anahtarı geçersiz ya da yetkisiz."
                            429 -> "Kota doldu. Biraz sonra yeniden dene."
                            else -> "Bağlantı kurulamadı. İnternet var mı?"
                        },
                    )
                }
            }
        })
    }

    /**
     * Model yerel saati bilmiyor (sorulunca UTC'yi tahmin ediyordu). Her bağlantıda cihazın yerel
     * tarih ve saati talimata eklenir; oturum en fazla 10 dakika sürdüğü için yeterince güncel kalır.
     */
    private fun nowLine(): String {
        val now = java.time.ZonedDateTime.now()
        val text = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy EEEE, HH:mm", Locale.forLanguageTag("tr-TR")).format(now)
        return "- Oturum başında yerel tarih ve saat: $text (${now.zone.id}). Saat sorulursa bunu ve geçen süreyi kullan."
    }

    private fun closeMessage(code: Int, reason: String): String {
        val r = reason.lowercase()
        return when {
            "api key" in r || "permission" in r || "unauth" in r -> "API anahtarı geçersiz ya da yetkisiz."
            "quota" in r || "exhausted" in r -> "Kota doldu. Biraz sonra yeniden dene."
            "model" in r && ("not found" in r || "not supported" in r) -> "Model bulunamadı: ${LiveProtocol.MODEL}"
            code == 1000 -> "Oturum bitti."
            else -> "Bağlantı kapandı ($code)."
        }
    }

    private fun fail(message: String) {
        if (!isActive) return
        val wasReady = _ui.value.phase != AssistantPhase.Connecting
        stopSessionParts()
        _ui.value = AssistantUi(AssistantPhase.Error, error = if (message == "Oturum bitti." && wasReady) null else message)
        if (_ui.value.error == null) { stop("sunucu oturumu bitirdi"); return }
        ConversationLog.error(message)
        // Hata birkaç saniye görünür, sonra kapanır.
        scope.launch {
            val shown = _ui.value
            delay(6_000)
            if (_ui.value === shown) stop("hata")
        }
    }

    private fun stopSessionParts() {
        generation++
        ready = false
        ticker?.cancel(); ticker = null
        ws?.cancel(); ws = null
        mic?.stop(); mic = null
        speaker?.stop(); speaker = null
        focus?.release(); focus = null
    }

    private fun handle(gen: Int, text: String) {
        if (gen != generation) return
        val events = runCatching { LiveProtocol.parse(text) }.getOrElse {
            Log.w(TAG, "çözülemeyen mesaj"); return
        }
        for (e in events) onEvent(e)
    }

    private fun onEvent(e: LiveEvent) {
        val now = SystemClock.uptimeMillis()
        // Google konuşmaya tepki verdi: cevapsızlık bekçisi ([checkNoReply]) durur.
        if (e is LiveEvent.UserText || e is LiveEvent.ModelText || e is LiveEvent.Audio || e is LiveEvent.Tools || e == LiveEvent.TurnComplete) {
            awaitingReplySince = 0L
        }
        when (e) {
            LiveEvent.SetupComplete -> scope.launch {
                ready = true
                lastActivity = SystemClock.uptimeMillis()
                if (mic == null) {
                    val g = generation
                    val m = LiveMic(::onMic, { message -> scope.launch { if (g == generation) fail(message) } }, audioManager)
                    if (!m.start()) { fail("Mikrofon açılamadı (araç asistanı kullanıyor olabilir)."); return@launch }
                    mic = m
                }
                if (resendAfterReconnect) {
                    // Cevapsız kalan konuşma yeni bağlantıya gönderilir ([checkNoReply]).
                    resendAfterReconnect = false
                    val chunks = synchronized(turnAudio) { turnAudio.toList() }
                    ws?.send(LiveProtocol.activityStart())
                    chunks.forEach(::sendAudio)
                    ws?.send(LiveProtocol.activityEnd())
                    awaitingReplySince = SystemClock.uptimeMillis()
                    Log.i(TAG, "yeniden bağlandı; konuşma tekrar gönderildi")
                    return@launch
                }
                _ui.update { it.copy(phase = AssistantPhase.Listening) }
                Log.i(TAG, "hazır, dinliyor")
            }
            is LiveEvent.Audio -> {
                val pcm = runCatching { Base64.decode(e.base64Pcm, Base64.DEFAULT) }.getOrNull() ?: return
                speaker?.play(pcm)
                lastActivity = now
                _ui.update { it.copy(phase = AssistantPhase.Speaking) }
            }
            is LiveEvent.UserText -> {
                transcripts++
                lastActivity = now
                val fresh = newUserTurn
                newUserTurn = false
                _ui.update { it.copy(heard = if (fresh) e.text.trim() else (it.heard + e.text).trim(), reply = if (fresh) "" else it.reply) }
                synchronized(turnLock) { turnHeard.append(e.text) }
            }
            is LiveEvent.ModelText -> {
                lastActivity = now
                _ui.update { it.copy(reply = (it.reply + e.text).trim()) }
                flushHeard()
                synchronized(turnLock) { turnReply.append(e.text) }
            }
            LiveEvent.Interrupted -> {
                speaker?.flush()
                flushReply(" (sözü kesildi)")
            }
            LiveEvent.TurnComplete -> {
                lastActivity = now
                newUserTurn = true
                flushHeard(); flushReply()
            }
            is LiveEvent.Tools -> {
                flushHeard()
                lastActivity = now
                _ui.update { it.copy(phase = AssistantPhase.Thinking) }
                e.calls.forEach(::runTool)
            }
            is LiveEvent.ToolsCancelled -> e.ids.forEach { toolJobs.remove(it)?.cancel() }
            // Otomatik algılama kapalıyken gelmemeli; gelirse yalnızca oturum özetinde sayılır.
            LiveEvent.SpeechStarted, LiveEvent.SpeechEnded -> voiceEvents++
            LiveEvent.GoAway -> scope.launch {
                // Sunucu bağlantıyı yakında kesecek: sürdürme anahtarıyla yeniden bağlan.
                Log.i(TAG, "goAway; yeniden bağlanılıyor")
                ws?.close(1000, null)
                if (resumeHandle != null) connect() else fail("Oturum süresi doldu.")
            }
            is LiveEvent.ResumeHandle -> resumeHandle = e.handle
            is LiveEvent.Usage -> Log.i(TAG, "token: giriş ${e.prompt}, çıkış ${e.response}, toplam ${e.total}")
        }
    }

    /** Kullanıcının bu turdaki sözü bittiyse günlüğe yazar (döküm parça parça gelir). */
    private fun flushHeard() {
        val text = synchronized(turnLock) { turnHeard.toString().trim().also { turnHeard.setLength(0) } }
        if (text.isNotEmpty()) ConversationLog.user(text)
    }

    /** Asistanın bu turdaki cevabını günlüğe yazar. */
    private fun flushReply(suffix: String = "") {
        val text = synchronized(turnLock) { turnReply.toString().trim().also { turnReply.setLength(0) } }
        if (text.isNotEmpty()) ConversationLog.assistant(text + suffix)
    }

    private fun runTool(call: ToolCall) {
        val b = backend ?: return
        Log.i(TAG, "araç: ${call.name}")
        // Müziği kullanıcı asistanla kendisi yönetti: oturum sonunda Pano geri açmasın.
        if (call.name == "media_control" || call.name == "open_app") app.pano.launcher.media.MediaHub.forgetAssistantPause()
        val job = scope.launch {
            val result = AssistantTools.run(call, b)
            toolJobs.remove(call.id)
            ConversationLog.tool(call.name, call.args.toString(), result.toString())
            if (!ready) return@launch
            ws?.send(LiveProtocol.toolResponse(listOf(ToolResult(call.id, call.name, wrap(result)))))
            lastActivity = SystemClock.uptimeMillis()
        }
        toolJobs[call.id] = job
    }

    /** Araç sonucu `response` içinde `result` alanıyla gider. */
    private fun wrap(o: org.json.JSONObject) = org.json.JSONObject().put("result", o)

    private fun onMic(pcm: ByteArray, level: Float) {
        if (!ready) return
        val sp = speaker
        val now = SystemClock.uptimeMillis()
        if (sp != null && sp.isPlaying) {
            // Asistan konuşuyor: mikrofon dinlenmez (yankı). Yarım kalmış bir tur varsa kapatılır.
            if (turns.speaking) ws?.send(LiveProtocol.activityEnd())
            turns.reset()
            preRoll.clear()
            _ui.update { if (it.level != 0f) it.copy(level = 0f) else it }
            return
        }
        if (level > peakLevel) peakLevel = level
        when (turns.feed(level, now)) {
            TurnDetector.Action.Start -> {
                localTurns++
                lastVoice = now
                lastActivity = now
                synchronized(turnAudio) { turnAudio.clear() }
                awaitingReplySince = 0L
                ws?.send(LiveProtocol.activityStart())
                // Konuşmanın başlangıcı birkaç parça önce olabilir: bekleyen sesi önce gönder.
                while (preRoll.isNotEmpty()) sendTurnAudio(preRoll.removeFirst())
                sendTurnAudio(pcm)
            }
            TurnDetector.Action.End -> {
                sendTurnAudio(pcm)
                ws?.send(LiveProtocol.activityEnd())
                lastActivity = now
                replyRetried = false
                awaitingReplySince = now
                _ui.update { it.copy(phase = AssistantPhase.Thinking) }
            }
            TurnDetector.Action.None -> if (turns.speaking) {
                lastVoice = now
                sendTurnAudio(pcm)
            } else {
                preRoll.addLast(pcm)
                while (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
            }
        }
        _ui.update { it.copy(level = level) }
    }

    private fun sendAudio(pcm: ByteArray) {
        val sent = ws?.send(LiveProtocol.audio(Base64.encodeToString(pcm, Base64.NO_WRAP))) == true
        if (sent) sentBytes += pcm.size else sendFailures++
    }

    /** Konuşmanın sesini gönderir ve olası yeniden gönderim için turun sonuna kadar saklar. */
    private fun sendTurnAudio(pcm: ByteArray) {
        synchronized(turnAudio) { if (turnAudio.size < MAX_TURN_CHUNKS) turnAudio += pcm }
        sendAudio(pcm)
    }

    /**
     * Google ara sıra bir bağlantıyı tepkisiz bırakıyor: konuşmaya döküm bile gelmiyor ve aynı
     * bağlantıdaki sonraki konuşmalar da cevapsız kalıyor (emülatörde ~10 oturumda 1). Normalde döküm
     * konuşma bittikten ~0,3 sn sonra gelir. [NO_REPLY_MS] içinde hiçbir şey gelmezse bağlantı bırakılıp
     * yeni bir oturum açılır ve aynı ses oraya gönderilir ([resendAfterReconnect]). Yine cevap gelmezse
     * kullanıcıdan tekrar söylemesi istenir.
     */
    private fun checkNoReply(now: Long) {
        val since = awaitingReplySince
        if (since == 0L || now - since < NO_REPLY_MS) return
        if (!replyRetried) {
            replyRetried = true
            awaitingReplySince = 0L
            retries++
            Log.w(TAG, "Google konuşmaya cevap vermedi; yeniden bağlanıp ses tekrar gönderilecek")
            resendAfterReconnect = true
            // Sürdürme anahtarı kullanılmaz: tepkisiz olan Google'daki oturumun kendisi; sürdürünce aynı
            // tepkisiz oturuma dönülüyordu (ya da "zaten bağlı" 1008 hatası). Takılma hep oturumun ilk
            // konuşmasında görüldü, yani korunacak bir bağlam da yok.
            resumeHandle = null
            // Önce numara artar: eski bağlantının kapanırken üreteceği hata olayı yok sayılsın.
            generation++
            ws?.cancel()
            connect()
            return
        }
        awaitingReplySince = 0L
        Log.w(TAG, "Google yeniden bağlantıda da cevap vermedi")
        ConversationLog.error("Google bu konuşmaya cevap vermedi (yeniden bağlanma dahil)")
        lastActivity = now
        newUserTurn = true
        _ui.update { it.copy(phase = AssistantPhase.Listening, heard = "", reply = "Anlayamadım, tekrar söyler misin?") }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val ui = _ui.value
        if (now - startedAt > MAX_SESSION_MS) { stop("10 dakika sınırı"); return }
        checkNoReply(now)
        when (ui.phase) {
            AssistantPhase.Speaking -> if (speaker?.isPlaying != true) {
                lastActivity = now
                _ui.update { it.copy(phase = AssistantPhase.Listening, level = 0f) }
            }
            AssistantPhase.Listening -> {
                // Kullanıcı konuştu ve sustu, cevap bekleniyor.
                if (!newUserTurn && now - lastVoice > 700 && ui.heard.isNotEmpty()) {
                    _ui.update { it.copy(phase = AssistantPhase.Thinking) }
                } else if (now - lastActivity > IDLE_TIMEOUT_MS && now - lastVoice > IDLE_TIMEOUT_MS / 2) {
                    Log.i(TAG, "sessizlik; kapanıyor (mikrofon en yüksek seviye ${"%.4f".format(peakLevel)}, konuşma eşiği ${"%.4f".format(turns.startThreshold())})")
                    stop("sessizlik")
                }
            }
            AssistantPhase.Thinking -> if (now - lastActivity > THINKING_TIMEOUT_MS) {
                lastActivity = now
                newUserTurn = true
                _ui.update { it.copy(phase = AssistantPhase.Listening) }
            }
            else -> Unit
        }
    }
}
