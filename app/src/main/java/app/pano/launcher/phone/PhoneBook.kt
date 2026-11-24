package app.pano.launcher.phone

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pano'nun telefon kartının verisi: son aramalar ve rehber, Android'in standart kayıtlarından
 * (CallLog, ContactsContract) okunur. Araç ünitelerinde telefon Bluetooth ile bağlanınca rehber ve
 * arama geçmişi genelde buraya eşitlenir (AOSP PBAP istemcisi); eşitlenmiyorsa liste boş kalır,
 * uydurma kayıt gösterilmez. İzin yoksa da boş döner.
 */
object PhoneBook {
    private const val TAG = "PhoneBook"

    data class Contact(val id: Long, val name: String, val number: String, val label: String?)

    /** [type]: CallLog.Calls.INCOMING_TYPE / OUTGOING_TYPE / MISSED_TYPE … */
    data class Recent(val name: String?, val number: String, val type: Int, val date: Long)

    fun canReadContacts(context: Context) = granted(context, Manifest.permission.READ_CONTACTS)
    fun canReadCalls(context: Context) = granted(context, Manifest.permission.READ_CALL_LOG)

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    suspend fun recents(context: Context, limit: Int = 60): List<Recent> = withContext(Dispatchers.IO) {
        if (!canReadCalls(context)) return@withContext emptyList()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val out = ArrayList<Recent>()
                while (c.moveToNext() && out.size < limit) {
                    val number = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    out += Recent(c.getString(0)?.takeIf { it.isNotBlank() }, number, c.getInt(2), c.getLong(3))
                }
                out
            }.orEmpty()
        }.onFailure { Log.w(TAG, "arama geçmişi okunamadı: $it") }.getOrDefault(emptyList())
    }

    suspend fun contacts(context: Context): List<Contact> = withContext(Dispatchers.IO) {
        if (!canReadContacts(context)) return@withContext emptyList()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                ),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
            )?.use { c ->
                val out = ArrayList<Contact>()
                val seen = HashSet<String>()
                while (c.moveToNext()) {
                    val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    val id = c.getLong(0)
                    // Aynı kişinin aynı numarası birden çok kaynaktan gelebiliyor.
                    if (!seen.add("$id/${digits(number)}")) continue
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: number
                    out += Contact(id, name, number, typeLabel(c.getInt(3), c.getString(4)))
                }
                out
            }.orEmpty()
        }.onFailure { Log.w(TAG, "rehber okunamadı: $it") }.getOrDefault(emptyList())
    }

    private fun typeLabel(type: Int, custom: String?): String? = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Cep"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Ev"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "İş"
        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> custom?.takeIf { it.isNotBlank() }
        else -> null
    }

    /** Karşılaştırma için yalnızca rakamlar (son 10 hane: +90 / 0 farkı önemsiz). */
    fun digits(number: String): String = number.filter { it.isDigit() }.takeLast(10)
}

/** Aramayı başlatır: izin varsa doğrudan arar, yoksa aracın telefon ekranını numarayla açar. */
object PhoneCall {
    private const val TAG = "PhoneCall"

    fun canCallDirectly(context: Context) =
        context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    fun call(context: Context, number: String) {
        val clean = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (clean.isEmpty()) return
        val uri = Uri.fromParts("tel", clean, null)
        val direct = canCallDirectly(context)
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Log.i(TAG, if (direct) "arama başlatıldı" else "telefon ekranı numarayla açıldı")
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Bu araçta arama yapılamıyor", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.w(TAG, "arama izni: $e")
            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
