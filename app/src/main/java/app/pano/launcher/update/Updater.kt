package app.pano.launcher.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import app.pano.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pano'nun kendini güncellemesi: GitHub'daki `batuhankul61-hub/Pano` deposunun en son
 * sürümüne (Releases) bakar; etiket `v<versionCode>`, eki `.apk`. Daha yeniyse APK'yı indirir ve
 * Android'in kendi yükleyicisiyle (PackageInstaller) üstüne kurar; kullanıcı ekranda onaylar.
 * Aynı release anahtarıyla imzalı olduğu için ayarlar ve veriler korunur.
 *
 * Depo herkese açık: anahtarsız okunur. İsteğe bağlı bir GitHub erişim anahtarı girilirse
 * (cihazda şifreli durur: [token]) istekler onunla gider; depo yeniden özel yapılırsa ya da
 * anahtarsız saatlik sınıra (60 istek) takılınırsa işe yarar.
 */
object Updater {
    private const val TAG = "Updater"
    private const val REPO = "batuhankul61-hub/Pano"
    private const val ACTION_INSTALL = "app.pano.launcher.UPDATE_INSTALL"

    val token = SecretValue("pano_update", "gh_token_enc", "pano_update_key")

    val currentCode: Int get() = BuildConfig.VERSION_CODE
    val currentName: String get() = BuildConfig.VERSION_NAME

    data class Release(val code: Int, val name: String, val notes: String, val assetUrl: String, val size: Long)

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object UpToDate : Status
        data class Available(val release: Release) : Status
        data class Downloading(val release: Release, val progress: Float) : Status
        data object Installing : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun request(url: String, tok: String?, accept: String) = Request.Builder()
        .url(url)
        .apply { if (!tok.isNullOrEmpty()) header("Authorization", "Bearer $tok") }
        .header("Accept", accept)
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Pano-launcher")
        .build()

    /** En son sürümü sorar; sonuç [status]'ta. */
    suspend fun check(context: Context) {
        val tok = token.get(context)
        _status.value = Status.Checking
        _status.value = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(request("https://api.github.com/repos/$REPO/releases/latest", tok, "application/vnd.github+json"))
                    .execute().use { r ->
                        when (r.code) {
                            200 -> {}
                            401 -> return@runCatching Status.Failed("Erişim anahtarı geçersiz. Sil ya da yenisini gir.")
                            403, 429 -> return@runCatching Status.Failed(
                                if (tok.isNullOrEmpty()) "GitHub şimdilik yanıt vermiyor (saatlik sınır). Biraz sonra dene."
                                else "Erişim anahtarının yetkisi yok.",
                            )
                            404 -> return@runCatching Status.Failed(
                                if (tok.isNullOrEmpty()) "Sürüm bulunamadı (depo özelse erişim anahtarı gir)."
                                else "Depoda henüz yayımlanmış sürüm yok.",
                            )
                            else -> return@runCatching Status.Failed("GitHub yanıtı: ${r.code}")
                        }
                        val json = JSONObject(r.body!!.string())
                        val code = json.getString("tag_name").trimStart('v', 'V').substringBefore('-').toIntOrNull()
                            ?: return@runCatching Status.Failed("Sürüm etiketi okunamadı: ${json.getString("tag_name")}")
                        val assets = json.getJSONArray("assets")
                        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                            .firstOrNull { it.getString("name").endsWith(".apk") }
                            ?: return@runCatching Status.Failed("Sürümde APK yok.")
                        val rel = Release(
                            code = code,
                            name = json.optString("name").ifBlank { "v$code" },
                            notes = json.optString("body"),
                            assetUrl = apk.getString("url"),
                            size = apk.optLong("size"),
                        )
                        Log.i(TAG, "son sürüm v$code, bu cihazda v$currentCode")
                        if (code > currentCode) Status.Available(rel) else Status.UpToDate
                    }
            }.getOrElse { Log.w(TAG, "denetim: $it"); Status.Failed("Bağlantı kurulamadı. İnternet var mı?") }
        }
    }

    /** APK'yı indirir ve yükleyiciyi başlatır. */
    suspend fun downloadAndInstall(context: Context, release: Release) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(app, "Pano'nun uygulama yüklemesine izin ver, sonra tekrar Güncelle'ye bas", Toast.LENGTH_LONG).show()
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        val tok = token.get(app)
        val file = File(app.cacheDir, "pano-update.apk")
        _status.value = Status.Downloading(release, 0f)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                // OkHttp, GitHub'ın dosya sunucusuna yönlendirirken Authorization başlığını düşürür.
                http.newCall(request(release.assetUrl, tok, "application/octet-stream")).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: release.size
                    body.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) _status.value = Status.Downloading(release, done.toFloat() / total)
                            }
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "indirme: $it") }.isSuccess
        }
        if (!ok) {
            _status.value = Status.Failed("İndirilemedi. Tekrar dene.")
            return
        }
        _status.value = Status.Installing
        withContext(Dispatchers.IO) { install(app, file) }
    }

    private fun install(context: Context, apk: File) = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("pano.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                context, id, Intent(ACTION_INSTALL).setPackage(context.packageName), flags,
            )
            session.commit(pi.intentSender)
        }
        Log.i(TAG, "yükleme oturumu gönderildi")
    }.onFailure {
        Log.w(TAG, "yükleme: $it")
        _status.value = Status.Failed("Yüklenemedi: ${it.message}")
    }

    /** Yükleyicinin sonucu: onay ekranını açar ya da hatayı gösterir. */
    class InstallResult : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val s = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "güncellendi")
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    Log.w(TAG, "yükleme başarısız ($s): $msg")
                    _status.value = Status.Failed(
                        if (s == PackageInstaller.STATUS_FAILURE_ABORTED) "Yükleme iptal edildi." else "Yüklenemedi ($s). $msg",
                    )
                }
            }
        }
    }
}
