package app.pano.launcher.assistant

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
 * Kullanıcının girdiği Gemini API anahtarı. Yalnızca bu cihazda, Android Keystore'daki bir AES
 * anahtarıyla şifrelenmiş olarak saklanır (anahtarın kendisi Keystore'dan çıkamaz). Değer asla
 * loglanmaz, ekranda yalnızca son 4 karakteri gösterilir.
 *
 * Not: EncryptedSharedPreferences kullanımdan kaldırıldığı için aynı işi doğrudan Keystore ile
 * yapıyoruz; ek kütüphane gerekmiyor.
 */
object AssistantKey {
    private const val TAG = "AssistantKey"
    private const val PREFS = "pano_assistant"
    private const val PREF_KEY = "api_key_enc"
    private const val ALIAS = "pano_assistant_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private val _hasKey = mutableStateOf(false)
    val hasKey: State<Boolean> = _hasKey

    private val _hint = mutableStateOf("")
    /** "••••abcd" gibi; anahtar yoksa boş. */
    val hint: State<String> = _hint

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        refresh(get(context))
    }

    /** Çözülmüş anahtar; yoksa ya da çözülemezse null. Sonucu loglama! */
    fun get(context: Context): String? {
        val enc = prefs(context).getString(PREF_KEY, null) ?: return null
        return runCatching {
            val raw = Base64.decode(enc, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "anahtar çözülemedi (${it.javaClass.simpleName})") }.getOrNull()
    }

    fun set(context: Context, key: String) {
        val k = key.trim()
        if (k.isEmpty()) return clear(context)
        val ok = runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secret())
            val out = c.iv + c.doFinal(k.toByteArray(Charsets.UTF_8))
            prefs(context).edit().putString(PREF_KEY, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        }.onFailure { Log.w(TAG, "anahtar kaydedilemedi (${it.javaClass.simpleName})") }.isSuccess
        refresh(if (ok) k else null)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(PREF_KEY).apply()
        refresh(null)
    }

    private fun refresh(key: String?) {
        _hasKey.value = !key.isNullOrEmpty()
        _hint.value = key?.takeLast(4)?.let { "••••$it" }.orEmpty()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
