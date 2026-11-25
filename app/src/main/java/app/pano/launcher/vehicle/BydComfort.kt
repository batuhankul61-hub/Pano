package app.pano.launcher.vehicle

import android.util.Log

/**
 * Koltuk ısıtma / havalandırma ve direksiyon ısıtma (hızlı ayarlar paneli).
 *
 * Biçimler DiLink 3.0 firmware'indeki `BYDAutoSettingDevice`'ten (cihaz türü 1023):
 *  - Okuma: `getSeatHeatingState(koltuk)` → `SET_DRIVER_SEAT_HEATING_STATE` /
 *    `SET_PASSENGER_SEAT_HEATING_STATE`; havalandırma `SET_*_SEAT_VENTILATING_STATE`.
 *    Değerler `SEAT_HEATING_OFF` = 1, `_LOW` = 2, `_HIGH` = 3 (`SEAT_HEATING_LEVEL3` = 4 üç kademeli
 *    koltuk için, araçta görülmedi).
 *  - Var mı: `SET_HAS_DRIVER_SEAT_HEATING` vb.; BYD'nin `hasFeature`'ı 1, 2 ya da 3'ü "var" sayıyor.
 *  - Direksiyon: `getSteeringWheelHeatingState` → ham kimlik 1116733454
 *    (`SET_STEERING_WHEEL_HEAT_STATE`): 0 geçersiz, 1 kapalı, 2 açık. Yazma `..._SET`'e 1 ya da 2.
 *  - Yazma: `setSeatHeatingState(koltuk, değer)` → `SET_*_SEAT_HEATING_STATE_SET` (1..4).
 *
 * Okuma her zaman; yazma yalnızca "Araca komut gönder" ayarı açık, BYD veri yolu var ve araç park
 * halindeyken ([BydDrive.canWrite]). Yazma araçta HENÜZ DENENMEDİ.
 */
object BydComfort {
    private const val TAG = "BydComfort"
    private const val SETTING_DEVICE = 1023
    private const val STEERING_HEAT_RAW = 1116733454

    enum class Seat(val label: String, val heat: String, val vent: String, val hasHeat: String, val hasVent: String) {
        Driver(
            "Sürücü", "SET_DRIVER_SEAT_HEATING_STATE", "SET_DRIVER_SEAT_VENTILATING_STATE",
            "SET_HAS_DRIVER_SEAT_HEATING", "SET_HAS_DRIVER_SEAT_VENTILATING",
        ),
        Passenger(
            "Yolcu", "SET_PASSENGER_SEAT_HEATING_STATE", "SET_PASSENGER_SEAT_VENTILATING_STATE",
            "SET_HAS_PASSENGER_SEAT_HEATING", "SET_HAS_PASSENGER_SEAT_VENTILATING",
        ),
    }

    /** Bir koltuğun okunabilen kademeleri; null = bu araçta yok ya da okunamıyor. */
    data class SeatState(val heat: Int?, val vent: Int?)

    data class State(
        val driver: SeatState = SeatState(null, null),
        val passenger: SeatState = SeatState(null, null),
        /** Direksiyon ısıtma; null = yok ya da okunamıyor. */
        val steeringHeat: Boolean? = null,
    ) {
        val isEmpty: Boolean
            get() = driver.heat == null && driver.vent == null && passenger.heat == null &&
                passenger.vent == null && steeringHeat == null

        fun seat(s: Seat) = if (s == Seat.Driver) driver else passenger
    }

    /** Ham koltuk değeri → kademe (0 kapalı, 1 düşük, 2 yüksek, 3 en yüksek); geçersizse null. */
    fun seatLevel(raw: Int?): Int? = when (raw) {
        1, 2, 3, 4 -> raw - 1
        else -> null
    }

    /** Kademe → yazılacak ham değer. */
    fun seatRaw(level: Int): Int = level.coerceIn(0, 3) + 1

    /** Ham direksiyon değeri: 1 kapalı, 2 açık; 0 / 65535 geçersiz. */
    fun steeringOn(raw: Int?): Boolean? = when (raw) {
        2 -> true
        1 -> false
        else -> null
    }

    /** "Var mı" sinyali: 1, 2, 3 var; 0 yok; okunamazsa null (bilinmiyor). */
    fun present(raw: Int?): Boolean? = when (raw) {
        1, 2, 3 -> true
        0 -> false
        else -> null
    }

    /**
     * Bir özelliğin gösterilip gösterilmeyeceği: durum okunabilmeli ve "var mı" sinyali açıkça
     * "yok" dememeli.
     */
    fun visible(level: Int?, has: Boolean?): Int? = level?.takeIf { has != false }

    private fun setting(field: String): Int? =
        BydBus.readFor(SETTING_DEVICE, "Setting", field) ?: BydBus.readInt("Setting", field)

    @Volatile private var lastRaw: String? = null

    /** Araçtan okur; BYD yoksa boş durum. Ana iş parçacığında çağırma. */
    fun read(): State {
        if (!BydBus.isAvailable) return State()
        val raw = StringBuilder()
        fun seat(s: Seat): SeatState {
            val heat = setting(s.heat)
            val vent = setting(s.vent)
            val hasHeat = setting(s.hasHeat)
            val hasVent = setting(s.hasVent)
            raw.append("${s.name}: ısı=$heat/$hasHeat hava=$vent/$hasVent ")
            return SeatState(
                heat = visible(seatLevel(heat), present(hasHeat)),
                vent = visible(seatLevel(vent), present(hasVent)),
            )
        }
        val driver = seat(Seat.Driver)
        val passenger = seat(Seat.Passenger)
        // Kimlik sınıfta sabit yazılı; adla bulunamazsa ham kimlikle okunur.
        val steeringRaw = setting("SET_STEERING_WHEEL_HEAT_STATE") ?: BydBus.readRaw(STEERING_HEAT_RAW)
        raw.append("direksiyon=$steeringRaw")
        val line = raw.toString()
        if (line != lastRaw) {
            lastRaw = line
            Log.i(TAG, "konfor ham: $line")
        }
        return State(driver, passenger, steeringOn(steeringRaw))
    }

    /** Koltuk ısıtma kademesi; dönüş platform sonuç kodu (0 = kabul) ya da gönderilmediyse null. */
    fun setSeatHeat(seat: Seat, level: Int): Int? = write("${seat.heat}_SET", seatRaw(level))

    fun setSeatVent(seat: Seat, level: Int): Int? = write("${seat.vent}_SET", seatRaw(level))

    fun setSteeringHeat(on: Boolean): Int? = write("SET_STEERING_WHEEL_HEAT_STATE_SET", if (on) 2 else 1)

    private fun write(field: String, value: Int): Int? {
        if (!BydDrive.canWrite()) return null.also { Log.i(TAG, "$field gönderilmedi (ayar kapalı / sürüşte / BYD yok)") }
        val id = BydBus.idOf("Setting", field) ?: return null.also { Log.w(TAG, "$field kimliği yok") }
        return BydBus.writeOne(SETTING_DEVICE, id, value)
    }
}
