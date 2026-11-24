package app.pano.launcher.vehicle

/**
 * Klimanın üfleme yönü. Kodlar BYD'nin klima cihazındaki değerler (firmware 13.1.33,
 * `BYDAutoAcDevice.AC_WINDMODE_*`, okuma `AC_WIND_MODE`, yazma `AC_WIND_MODE_SET`).
 *
 * Araç kaç mod desteklediğini bildirir: 5 modlu araçta yalnızca ilk beşi var; 7 modlu araçta
 * [FaceFootWindshield] ve [FaceWindshield] de var. "Cam" yönü ön camın havalandırmasıdır; ön cam
 * buğu çözme (maksimum) ayrı bir anahtardır.
 */
enum class AirMode(val code: Int, val label: String, val face: Boolean, val feet: Boolean, val windshield: Boolean) {
    Face(1, "Yüz", face = true, feet = false, windshield = false),
    FaceFeet(2, "Yüz + Ayak", face = true, feet = true, windshield = false),
    Feet(3, "Ayak", face = false, feet = true, windshield = false),
    FeetWindshield(4, "Ayak + Cam", face = false, feet = true, windshield = true),
    Windshield(5, "Cam", face = false, feet = false, windshield = true),
    FaceFootWindshield(6, "Yüz + Ayak + Cam", face = true, feet = true, windshield = true),
    FaceWindshield(7, "Yüz + Cam", face = true, feet = false, windshield = true);

    /** Tuşta gösterilen kısa ad ("Yüz+Ayak"); dar tuşta tam ad kesiliyordu. */
    val short: String get() = label.replace(" + ", "+")

    /** Hava çıkışları; sürücü için üç ayrı aç/kapa tuşu (7 mod bunların bütün kombinasyonları). */
    enum class Outlet { Face, Feet, Windshield }

    fun has(o: Outlet): Boolean = when (o) {
        Outlet.Face -> face
        Outlet.Feet -> feet
        Outlet.Windshield -> windshield
    }

    /**
     * [o] çıkışını açıp kapatınca oluşan mod. Son açık çıkış kapatılamaz (değişmez). [count] 5 ise
     * yüz ile cam birlikte olamaz: birini açmak diğerini kapatır.
     */
    fun toggle(o: Outlet, count: Int): AirMode {
        var f = face
        var ft = feet
        var w = windshield
        when (o) {
            Outlet.Face -> f = !f
            Outlet.Feet -> ft = !ft
            Outlet.Windshield -> w = !w
        }
        if (!f && !ft && !w) return this
        if (count < 7 && f && w) {
            if (o == Outlet.Windshield) f = false else w = false
        }
        return of(f, ft, w) ?: this
    }

    companion object {
        fun of(code: Int?): AirMode? = entries.firstOrNull { it.code == code }

        fun of(face: Boolean, feet: Boolean, windshield: Boolean): AirMode? =
            entries.firstOrNull { it.face == face && it.feet == feet && it.windshield == windshield }

        /** Aracın bildirdiği mod sayısına göre gösterilecek modlar (bilinmiyorsa 5). */
        fun available(count: Int): List<AirMode> = if (count >= 7) entries.toList() else entries.take(5)

        /**
         * BYD "mod sayısı" değeri: 1 = 5 mod, 2 = 7 mod, 0/başka = bilinmiyor (5 varsayılır).
         * Firmware: `AC_WIND_TOTAL_MODES_NUM_5 = 1`, `AC_WIND_TOTAL_MODES_NUM_7 = 2`.
         */
        fun countFromByd(raw: Int?): Int = if (raw == 2) 7 else 5
    }
}
