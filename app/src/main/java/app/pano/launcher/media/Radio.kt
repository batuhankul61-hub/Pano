package app.pano.launcher.media

/**
 * Çalan şey radyoysa istasyon bilgisi.
 *
 * Kaynak (firmware 13.1.33, `com.byd.mediacenter` → `com.byd.radio.RadioController` ve
 * `dab.singlechip.DABController`): BYD'nin medya merkezi radyoyu Android'in standart medya oturumuyla
 * yayınlar ve kendi anahtarıyla hangi modda olduğunu yazar (`com.byd.mediacenter.metadata.MEDIA_MODE`:
 * 0 = FM/AM, 9 = DAB).
 * - FM/AM: başlık "FM 98.4 MHz" / "AM 1008 kHz" ya da RDS varsa "RDS <istasyon adı>"; sanatçı satırı
 *   istasyonun görünen adı.
 * - DAB: başlık istasyon (servis) adı, sanatçı satırı topluluk (ensemble) adı.
 *
 * [name] istasyon adı (yoksa null), [frequency] "98.4 MHz" gibi (yoksa null).
 */
data class RadioStation(val band: String, val name: String?, val frequency: String?) {
    /** Kartın büyük satırı: istasyon adı, yoksa "FM 98.4". */
    val title: String get() = name ?: listOfNotNull(band, frequency?.substringBefore(' ')).joinToString(" ")

    /** Küçük satır: "FM · 98.4 MHz" ya da yalnızca bant. */
    val subtitle: String get() = if (name != null && frequency != null) "$band · $frequency" else if (name != null) band else frequency ?: band
}

object Radio {
    const val MEDIA_CENTER = "com.byd.mediacenter"
    const val MODE_KEY = "com.byd.mediacenter.metadata.MEDIA_MODE"
    const val MODE_RADIO = 0L
    const val MODE_DAB = 9L

    private val bandFreq = Regex("""^(FM|AM)\s+([\d.,]+)\s*([kKmM][hH][zZ])?""")
    private val rds = Regex("""^RDS\s+(.+)$""")

    /**
     * Medya oturumunun paketi, BYD medya modu (yoksa null), başlık ve sanatçıdan istasyon çıkarır.
     * Radyo değilse null. Saf işlev: birim testleri doğrudan dener.
     */
    fun parse(packageName: String, mode: Long?, title: String, artist: String): RadioStation? {
        if (packageName != MEDIA_CENTER || mode == null) return null
        val t = title.trim()
        val a = artist.trim().takeIf { it.isNotEmpty() && it != t }
        return when (mode) {
            MODE_DAB -> RadioStation("DAB", t.ifEmpty { null } ?: a, null)
            MODE_RADIO -> {
                rds.find(t)?.let { m ->
                    val name = m.groupValues[1].trim()
                    return RadioStation("FM", name.ifEmpty { null }, a?.let(::frequencyOf))
                }
                val m = bandFreq.find(t)
                if (m != null) {
                    val band = m.groupValues[1]
                    val unit = m.groupValues[3].ifEmpty { if (band == "FM") "MHz" else "kHz" }.let(::unit)
                    val freq = "${m.groupValues[2].replace(',', '.')} $unit"
                    // Sanatçı satırı çoğu zaman frekansın kendisi; ad değilse yok say.
                    RadioStation(band, a?.takeIf { frequencyOf(it) == null }, freq)
                } else {
                    RadioStation("Radyo", t.ifEmpty { null } ?: a, null)
                }
            }
            else -> null
        }
    }

    /** "98.4 MHz" / "FM 98.4" gibi bir metinden frekans; yoksa null. */
    private fun frequencyOf(s: String): String? {
        val m = Regex("""([\d]+[.,]?\d*)\s*([kKmM][hH][zZ])""").find(s) ?: return null
        return "${m.groupValues[1].replace(',', '.')} ${unit(m.groupValues[2])}"
    }

    private fun unit(u: String) = if (u.lowercase().startsWith("k")) "kHz" else "MHz"
}
