package app.pano.launcher.update

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Kullanıcının girdiği gizli bir değer (ör. GitHub erişim anahtarı). Android Keystore'daki bir AES
 * anahtarıyla şifrelenip SharedPreferences'ta durur; değer asla loglanmaz, ekranda yalnızca son 4
 * karakteri gösterilir. (Asistanın `AssistantKey`'iyle aynı yöntem.)
 */
class SecretValue(private val prefsName: String, private val prefKey: String, private val alias: String) {
    private val _hasValue = mutableStateOf(false)
    val hasValue: State<Boolean> = _hasValue
    private val _hint = mutableStateOf("")
    val hint: State<String> = _hint
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        refresh(get(context))
    }

    /** Çözülmüş değer; yoksa null. Sonucu loglama! */
    fun get(context: Context): String? {
        val enc = prefs(context).getString(prefKey, null) ?: return null
        return runCatching {
            val raw = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "değer çözülemedi (${it.javaClass.simpleName})") }.getOrNull()
    }

    fun set(context: Context, value: String) {
        val v = value.trim()
        if (v.isEmpty()) return clear(context)
        val ok = runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secret())
            val out = c.iv + c.doFinal(v.toByteArray(Charsets.UTF_8))
            prefs(context).edit().putString(prefKey, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        }.onFailure { Log.w(TAG, "değer kaydedilemedi (${it.javaClass.simpleName})") }.isSuccess
        refresh(if (ok) v else null)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(prefKey).apply()
        refresh(null)
    }

    private fun refresh(v: String?) {
        _hasValue.value = !v.isNullOrEmpty()
        _hint.value = v?.takeLast(4)?.let { "••••$it" }.orEmpty()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object { const val TAG = "SecretValue" }
}
