package app.pano.launcher.vehicle

import android.util.Log

/**
 * Araçta hangi donanımın olduğu. Arayüz buna göre şekillenir: olmayan özellik ekrana gelmez.
 * BYD araçta platformun "var mı" sinyallerinden okunur; başka cihazlarda her şey açık sayılır
 * (önizleme). Alanlar null = bilinmiyor.
 */
data class VehicleFeatures(
    val dualZoneClimate: Boolean = true,
    val seatHeating: Boolean = true,
    val electricTailgate: Boolean? = null,
    val mirrorFold: Boolean? = null,
) {
    companion object {
        private const val TAG = "VehicleFeatures"

        /** Platformda "var/yok" sinyalleri genelde 1 = var. Okunamazsa null. */
        private fun has(field: String): Boolean? = BydBus.readInt("Setting", field)?.let { it == 1 }

        /** BYD veri yolundan okur; [BydBus.init] önceden çağrılmış olmalı. */
        fun readByd(): VehicleFeatures {
            // Ham değerler, yeni sinyal eşlemeleri için kayda geçer.
            val probe = listOf(
                "SET_CAR_DUAL_TEMP_AREA_AC_DET", "AC_TYPE",
                "SET_HAS_DRIVER_SEAT_HEATING", "SET_HAS_PASSENGER_SEAT_HEATING", "SET_HAS_SEAT_HEATING_AND_VENTILATING",
                "SET_CAR_BACK_DOOR_ELECTRIC_MODE_DET", "SET_CAR_BACK_DOOR_ELECTRIC_MODE",
                "SET_HAVE_REARVIEW_MIRROR_AUTO_FOLD", "SET_CAR_EX_MIRROW_FOLD",
                "SET_CAR_DOOR_LOCK", "SET_DR_CHARGER_PORT",
            ).associateWith { BydBus.readInt("Setting", it) }
            Log.i(TAG, "ham: $probe")
            return VehicleFeatures(
                // Algılama sinyali boş gelirse klima tipine bakılır: Atto 2 AC_TYPE=1 bildiriyor ve
                // sürücü sıcaklığı değişince yolcu da aynı değere geçiyor (tek bölge).
                dualZoneClimate = has("SET_CAR_DUAL_TEMP_AREA_AC_DET")
                    ?: BydBus.readInt("Ac", "AC_TYPE")?.let { it != 1 }
                    ?: true,
                seatHeating = (has("SET_HAS_DRIVER_SEAT_HEATING") == true) || (has("SET_HAS_SEAT_HEATING_AND_VENTILATING") == true),
                electricTailgate = has("SET_CAR_BACK_DOOR_ELECTRIC_MODE_DET"),
                mirrorFold = has("SET_HAVE_REARVIEW_MIRROR_AUTO_FOLD"),
            ).also { Log.i(TAG, "özellikler: $it") }
        }
    }
}
