package app.pano.launcher.assistant

import android.content.Context
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Pano Asistan konuşma günlüğü (isteğe bağlı, varsayılan kapalı).
 *
 * Açıkken her oturumda söylenenler (Google'ın döküm metni), asistanın cevapları, çağrılan araçlar
 * ve araçların döndürdüğü sonuçlar yalnızca bu cihazda, uygulamanın kendi klasöründeki bir metin
 * dosyasına yazılır. Amaç asistanın neden "bilmiyorum / erişimim yok" dediğini görebilmek.
 * Hiçbir yere gönderilmez; API anahtarı ve ses asla yazılmaz.
 *
 * Yazma tek bir arka plan iş parçacığında yapılır; çağıranı bekletmez.
 */
object ConversationLog {
    private const val TAG = "PanoAsistan"
    private const val PREFS = "pano_assistant"
    private const val PREF_ENABLED = "log_enabled"
    private const val FILE = "asistan-gunlugu.txt"
    /** Dosya bunu aşınca eski yarısı atılır. */
    private const val MAX_BYTES = 1_000_000L
    /** Tek bir araç sonucunun günlükteki en uzun hali. */
    private const val MAX_RESULT = 800

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "PanoAsistanGunluk") }
    // DateTimeFormatter iş parçacığı güvenli (SimpleDateFormat değil); olaylar farklı iş parçacıklarından gelir.
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss", Locale("tr"))
    private val day = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale("tr"))
    private fun now() = LocalDateTime.now()

    @Volatile private var file: File? = null

    private val _enabled = mutableStateOf(false)
    val enabled: State<Boolean> = _enabled

    /** Uygulama içinden bir kez çağrılır; ayarı yükler. */
    fun init(context: Context) {
        if (file != null) return
        val app = context.applicationContext
        file = File(app.filesDir, FILE)
        _enabled.value = prefs(app).getBoolean(PREF_ENABLED, false)
    }

    fun setEnabled(context: Context, on: Boolean) {
        init(context)
        prefs(context).edit().putBoolean(PREF_ENABLED, on).apply()
        _enabled.value = on
        if (on) write("── Günlük açıldı (${day.format(now())})")
    }

    fun sessionStarted() = write("\n── Oturum başladı (${day.format(now())})")
    fun sessionEnded(reason: String) = write("── Oturum bitti: $reason")
    fun user(text: String) = write("Sen: $text")
    fun assistant(text: String) = write("Asistan: $text")
    fun error(text: String) = write("Hata: $text")
    fun tool(name: String, args: String, result: String) =
        write("Araç: $name $args\n    → ${if (result.length > MAX_RESULT) result.take(MAX_RESULT) + "…" else result}")

    private fun write(line: String) {
        val f = file ?: return
        if (!_enabled.value) return
        val stamped = line.lines().joinToString("\n") { if (it.isBlank() || it.startsWith("    ")) it else "${time.format(now())}  $it" }
        io.execute {
            runCatching {
                f.appendText(stamped + "\n")
                if (f.length() > MAX_BYTES) {
                    val text = f.readText()
                    f.writeText(text.substring(text.length / 2).substringAfter('\n'))
                }
            }.onFailure { Log.w(TAG, "günlük yazılamadı: ${it.javaClass.simpleName}") }
        }
    }

    /** Günlüğün son [maxLines] satırı (arka planda çağrılmalı). */
    fun tail(maxLines: Int = 300): String {
        val f = file ?: return ""
        return runCatching { f.readLines().takeLast(maxLines).joinToString("\n") }.getOrDefault("")
    }

    fun clear(onDone: () -> Unit = {}) {
        val f = file ?: return
        io.execute { runCatching { f.delete() }; onDone() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
