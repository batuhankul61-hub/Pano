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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
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
    /** Bunun üstündeki mikrofon seviyesi "konuşuyor" sayılır (gösterge için). */
    private const val VOICE_LEVEL = 0.02f

    val SYSTEM_PROMPT = """
        Sen Pano Asistan'sın: bir BYD elektrikli otomobilin ekranındaki sesli asistan. Kullanıcı büyük ihtimalle araç kullanıyor.
        - Her zaman Türkçe konuş. Cevapların kısa olsun: çoğu zaman tek cümle. Liste okuma, gereksiz açıklama yapma.
        - Sakin, net ve dikkat dağıtmayan bir üslup kullan. Sürücüye ekrana bakmasını söyleme.
        - Klima, navigasyon, müzik, uygulama açma, araç durumu ve hava durumu için araçlarını kullan. Bir işi yaptıysan ne yaptığını kısaca söyle.
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
    private var ticker: Job? = null
    private val toolJobs = ConcurrentHashMap<String, Job>()

    @Volatile private var ready = false
    @Volatile private var lastActivity = 0L
    @Volatile private var lastVoice = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var newUserTurn = true
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
        val key = AssistantKey.get(context)
        if (key.isNullOrEmpty()) {
            _ui.value = AssistantUi(AssistantPhase.Error, error = "API anahtarı yok. Pano Ayarları → Pano Asistan.")
            return
        }
        apiKey = key
        backend = PanoBackend(context)
        focus = LiveFocus(context.applicationContext).also { it.acquire() }
        speaker = LiveSpeaker().also { it.start() }
        resumeHandle = null
        startedAt = SystemClock.uptimeMillis()
        lastActivity = startedAt
        newUserTurn = true
        _ui.value = AssistantUi(AssistantPhase.Connecting)
        connect()
        ticker = scope.launch { while (isActive) { tick(); delay(200) } }
    }

    /** Oturumu kapatır (✕, sessizlik ya da hata). */
    fun stop() {
        generation++
        ready = false
        ticker?.cancel(); ticker = null
        toolJobs.values.forEach { it.cancel() }; toolJobs.clear()
        ws?.close(1000, "kapatıldı"); ws = null
        mic?.stop(); mic = null
        speaker?.stop(); speaker = null
        focus?.release(); focus = null
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
                webSocket.send(LiveProtocol.setup(SYSTEM_PROMPT, AssistantTools.declarations(), resumeHandle))
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
        if (_ui.value.error == null) { stop(); return }
        // Hata birkaç saniye görünür, sonra kapanır.
        scope.launch {
            val shown = _ui.value
            delay(6_000)
            if (_ui.value === shown) stop()
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
        when (e) {
            LiveEvent.SetupComplete -> scope.launch {
                ready = true
                lastActivity = SystemClock.uptimeMillis()
                if (mic == null) {
                    mic = LiveMic(::onMic).also {
                        if (!it.start()) { fail("Mikrofon açılamadı."); return@launch }
                    }
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
                lastActivity = now
                val fresh = newUserTurn
                newUserTurn = false
                _ui.update { it.copy(heard = if (fresh) e.text.trim() else (it.heard + e.text).trim(), reply = if (fresh) "" else it.reply) }
            }
            is LiveEvent.ModelText -> {
                lastActivity = now
                _ui.update { it.copy(reply = (it.reply + e.text).trim()) }
            }
            LiveEvent.Interrupted -> speaker?.flush()
            LiveEvent.TurnComplete -> {
                lastActivity = now
                newUserTurn = true
            }
            is LiveEvent.Tools -> {
                lastActivity = now
                _ui.update { it.copy(phase = AssistantPhase.Thinking) }
                e.calls.forEach(::runTool)
            }
            is LiveEvent.ToolsCancelled -> e.ids.forEach { toolJobs.remove(it)?.cancel() }
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

    private fun runTool(call: ToolCall) {
        val b = backend ?: return
        Log.i(TAG, "araç: ${call.name}")
        val job = scope.launch {
            val result = AssistantTools.run(call, b)
            toolJobs.remove(call.id)
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
            _ui.update { if (it.level != 0f) it.copy(level = 0f) else it }
            return
        }
        if (level > VOICE_LEVEL) lastVoice = now
        ws?.send(LiveProtocol.audio(Base64.encodeToString(pcm, Base64.NO_WRAP)))
        _ui.update { it.copy(level = level) }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val ui = _ui.value
        if (now - startedAt > MAX_SESSION_MS) { stop(); return }
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
                    Log.i(TAG, "sessizlik; kapanıyor")
                    stop()
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
