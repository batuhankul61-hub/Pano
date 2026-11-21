package app.pano.launcher.vehicle

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Klima ayarlarını cihazda saklar; uygulama yeniden açıldığında son ayarlar geri gelir.
 * Uygulama genelinde tek bir [ClimateState] örneği vardır ([Settings] örüntüsüne benzer).
 * Kayıt, Compose durumunu izleyen bir snapshotFlow ile yapılır: art arda gelen
 * değişiklikler birleştirilir, yalnızca değer gerçekten değiştiğinde diske yazılır.
 */
object ClimateStore {
    private const val PREFS = "pano_climate"

    // Kayıt biçimi değişirse artırılır; tanınmayan sürümler varsayılanlara döner.
    private const val VERSION = 1

    private var state: ClimateState? = null

    /** Tekil klima durumunu döndürür; ilk çağrıda kayıtlı değerleri yükler. */
    fun load(context: Context): ClimateState {
        state?.let { return it }
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val loaded = ClimateState()
        val s = if (runCatching { restore(p, loaded) }.isSuccess) {
            loaded
        } else {
            // Bozuk kayıt (örn. yanlış türde değer): yarım yüklenmiş durumu at,
            // temiz varsayılanlarla devam et ve kaydı sil.
            p.edit().clear().apply()
            ClimateState()
        }
        state = s

        // İlk değer zaten yüklenen değer olduğu için atlanır; sonrakiler kaydedilir.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            snapshotFlow { Values.of(s) }.drop(1).collect { save(p, it) }
        }
        // BYD araçta klima araçla eşlenir; araç değerleri kayıtlı olanların önüne geçer.
        BydClimate.attach(context, s)
        return s
    }

    private fun restore(p: SharedPreferences, s: ClimateState) {
        if (p.getInt("version", -1) != VERSION) return
        val d = ClimateState()
        s.driver = temp(p.getFloat("driver", d.driver), d.driver)
        s.passenger = temp(p.getFloat("passenger", d.passenger), d.passenger)
        s.fan = p.getInt("fan", d.fan).coerceIn(0, 7)
        s.auto = p.getBoolean("auto", d.auto)
        s.ac = p.getBoolean("ac", d.ac)
        s.face = p.getBoolean("face", d.face)
        s.foot = p.getBoolean("foot", d.foot)
        s.defrost = p.getBoolean("defrost", d.defrost)
        s.rearDefrost = p.getBoolean("rearDefrost", d.rearDefrost)
        s.driverSeat = p.getInt("driverSeat", d.driverSeat).coerceIn(0, 3)
        s.passengerSeat = p.getInt("passengerSeat", d.passengerSeat).coerceIn(0, 3)
        s.sync = p.getBoolean("sync", d.sync)
        s.recirculation = p.getBoolean("recirculation", d.recirculation)
        // Senkron açıksa yolcu her zaman sürücüyle aynı olmalı.
        if (s.sync) s.passenger = s.driver
    }

    private fun save(p: SharedPreferences, v: Values) {
        p.edit()
            .putInt("version", VERSION)
            .putFloat("driver", v.driver)
            .putFloat("passenger", v.passenger)
            .putInt("fan", v.fan)
            .putBoolean("auto", v.auto)
            .putBoolean("ac", v.ac)
            .putBoolean("face", v.face)
            .putBoolean("foot", v.foot)
            .putBoolean("defrost", v.defrost)
            .putBoolean("rearDefrost", v.rearDefrost)
            .putInt("driverSeat", v.driverSeat)
            .putInt("passengerSeat", v.passengerSeat)
            .putBoolean("sync", v.sync)
            .putBoolean("recirculation", v.recirculation)
            .apply()
    }

    /** Sıcaklık 16–30 aralığında ve 0,5 adımlı olmalı; geçersizse varsayılan kullanılır. */
    private fun temp(t: Float, fallback: Float): Float =
        if (t.isNaN() || t < 16f || t > 30f) fallback else Math.round(t * 2) / 2f

    /** snapshotFlow'un karşılaştırabilmesi için tüm alanların değişmez kopyası. */
    private data class Values(
        val driver: Float,
        val passenger: Float,
        val fan: Int,
        val auto: Boolean,
        val ac: Boolean,
        val face: Boolean,
        val foot: Boolean,
        val defrost: Boolean,
        val rearDefrost: Boolean,
        val driverSeat: Int,
        val passengerSeat: Int,
        val sync: Boolean,
        val recirculation: Boolean,
    ) {
        companion object {
            fun of(s: ClimateState) = Values(
                s.driver, s.passenger, s.fan, s.auto, s.ac, s.face, s.foot,
                s.defrost, s.rearDefrost, s.driverSeat, s.passengerSeat, s.sync, s.recirculation,
            )
        }
    }
}
