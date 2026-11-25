package app.pano.launcher.phone

/**
 * Görüşmenin Pano'daki hali. Kaynaklar ([CallHub]) bunu doldurur; arayüz yalnızca bunu çizer.
 * Bilinmeyen alan null kalır (uydurma süre ya da ad yok).
 */
enum class CallPhase {
    /** Gelen arama, çalıyor. */
    Incoming,
    /** Biz arıyoruz, karşı taraf henüz açmadı. */
    Dialing,
    /** Görüşme sürüyor. */
    Active,
    /** Beklemede. */
    Holding,
    /** Arama sürüyor ama aranıyor mu konuşuluyor mu bilinmiyor (yalnızca Telephony bilgisi varken). */
    Ongoing,
    /** Bitti (kart kısa bir süre "Arama bitti" yazıp kapanır). */
    Ended,
}

data class CallInfo(
    val number: String?,
    val name: String?,
    val phase: CallPhase,
    /** Görüşmenin başladığı an, `SystemClock.elapsedRealtime()` cinsinden; bilinmiyorsa null. */
    val activeSince: Long? = null,
    val muted: Boolean = false,
    /** Bittiği an (`elapsedRealtime`); süre o anda durur. */
    val endedAt: Long? = null,
    /** Ses telefonda mı (araçtan telefona aktarıldı); bilinmiyorsa null. */
    val audioOnPhone: Boolean? = null,
    /** Tuş takımı (DTMF) gönderilebiliyor mu. */
    val canDtmf: Boolean = false,
    /** Ses telefona aktarılabiliyor mu. */
    val canTransfer: Boolean = false,
    /** Bilgi nereden geldi (log ve hata ayıklama için): incall, hfp, telephony. */
    val source: String = "",
) {
    /** Kartın başlığı: ad, yoksa numara, o da yoksa "Bilinmeyen numara". */
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: number?.takeIf { it.isNotBlank() } ?: "Bilinmeyen numara"

    /** Başlıkta ad varsa numara alt satırda gösterilir. */
    val subtitleNumber: String? get() = if (!name.isNullOrBlank() && !number.isNullOrBlank() && name != number) number else null
}

/** Durum yazısı (süre ayrı gösterilir). */
fun CallPhase.label(): String = when (this) {
    CallPhase.Incoming -> "Gelen arama"
    CallPhase.Dialing -> "Aranıyor…"
    CallPhase.Active -> "Görüşme"
    CallPhase.Holding -> "Beklemede"
    CallPhase.Ongoing -> "Arama sürüyor"
    CallPhase.Ended -> "Arama bitti"
}

/** 65 sn → "1:05", 3725 sn → "1:02:05". */
fun formatCallDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * BYD'nin arama penceresinden (erişilebilirlik ağacı) okunan yazılar. Alan adları araçta henüz
 * bilinmiyor: önce görünüm kimliğine (`name`, `number`, `time`, `state` geçenler) bakılır, kimlik
 * yoksa yazının biçiminden tahmin edilir (süre "0:42", numara rakamlar, durum bilinen sözcükler).
 */
object CallScreenText {
    data class Node(val viewId: String?, val text: String)

    data class Parsed(
        val name: String? = null,
        val number: String? = null,
        val phase: CallPhase? = null,
        val durationSec: Long? = null,
    ) {
        val isEmpty get() = name == null && number == null && phase == null && durationSec == null
    }

    private val durationRe = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    private val numberRe = Regex("""^[+(]?[\d][\d\s()\-.]{2,}$""")

    /** Düğme ve başlık yazıları: ad sanılmasın. */
    private val buttonWords = setOf(
        "kapat", "cevapla", "yanıtla", "reddet", "sessiz", "sessize al", "tuş takımı", "tuşlar", "klavye",
        "hoparlör", "telefona aktar", "özel", "bekle", "beklet", "rehber", "kişiler", "son aramalar", "telefon",
        "bluetooth", "answer", "accept", "reject", "decline", "hang up", "end", "mute", "keypad", "keyboard",
        "speaker", "private", "hold", "contacts", "接听", "挂断", "拒接", "静音", "键盘", "免提", "私密", "切换",
    )

    fun phaseOf(text: String): CallPhase? {
        val t = text.trim().lowercase()
        return when {
            t.isEmpty() -> null
            listOf("gelen arama", "arıyor…", "sizi arıyor", "incoming", "来电").any { t.contains(it) } -> CallPhase.Incoming
            listOf("aranıyor", "çağrı yapılıyor", "calling", "dialing", "dialling", "正在呼叫", "呼叫中", "拨号").any { t.contains(it) } -> CallPhase.Dialing
            listOf("beklemede", "on hold", "保持").any { t.contains(it) } -> CallPhase.Holding
            listOf("bitti", "sona erdi", "kapatıldı", "ended", "call end", "通话结束", "已挂断").any { t.contains(it) } -> CallPhase.Ended
            listOf("görüşme", "konuşuluyor", "bağlandı", "connected", "通话中").any { t.contains(it) } -> CallPhase.Active
            else -> null
        }
    }

    fun isDuration(text: String) = durationRe.matches(text.trim())

    fun durationSeconds(text: String): Long? {
        if (!isDuration(text)) return null
        val parts = text.trim().split(':').map { it.toLong() }
        return parts.fold(0L) { acc, p -> acc * 60 + p }
    }

    fun isNumber(text: String): Boolean {
        val t = text.trim()
        return numberRe.matches(t) && t.count { it.isDigit() } >= 3 && !isDuration(t)
    }

    fun parse(nodes: List<Node>): Parsed {
        val clean = nodes.map { Node(it.viewId?.substringAfter(":id/")?.lowercase(), it.text.trim()) }.filter { it.text.isNotEmpty() }
        /** Anahtarlar öncelik sırasıyla denenir ("name" geçen kimlik "contact" geçenden önce). */
        fun byId(vararg keys: String, accept: (String) -> Boolean = { true }) = keys.firstNotNullOfOrNull { k ->
            clean.firstOrNull { n -> n.viewId != null && n.viewId.contains(k) && accept(n.text) }?.text
        }

        val idName = byId("name", "caller", "contact") { t ->
            !isNumber(t) && !isDuration(t) && phaseOf(t) == null && t.lowercase() !in buttonWords
        }
        val idNumber = byId("number", "phone_num", "phonenum", "tel")?.takeIf { isNumber(it) }
        val idTime = byId("time", "duration", "timer", "chronometer")?.let { durationSeconds(it) }
        val idState = byId("state", "status")?.let { phaseOf(it) }

        val duration = idTime ?: clean.firstNotNullOfOrNull { durationSeconds(it.text) }
        val number = idNumber ?: clean.firstOrNull { isNumber(it.text) }?.text
        val phase = idState ?: clean.firstNotNullOfOrNull { phaseOf(it.text) } ?: if (duration != null) CallPhase.Active else null
        // Kimlikten bulunamazsa yazıdan tahmin: yalnızca tek bir aday kalıyorsa (yanlış ad göstermektense hiç).
        val name = idName ?: clean.map { it.text }.distinct().filter { t ->
            t.length in 1..40 && !isNumber(t) && !isDuration(t) && phaseOf(t) == null &&
                t.lowercase() !in buttonWords && t.any { it.isLetter() }
        }.singleOrNull()
        return Parsed(name, number, phase, duration)
    }
}
