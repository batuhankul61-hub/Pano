package app.pano.launcher.vehicle

import android.util.Log

/** Şarj türü: AC (ev/duvar, tip 2) ya da DC (hızlı şarj). */
enum class ChargeKind { AC, DC }

/**
 * Şarj durumu. Her alan null = bilinmiyor; arayüz yalnızca bilinenleri gösterir.
 * Batarya yüzdesi ve menzil [VehicleState]'te zaten var, burada tekrarlanmaz.
 */
data class ChargeState(
    /** Şarj kablosu (tabanca) takılı mı. */
    val plugged: Boolean? = null,
    /** Akım akıyor mu (şarj ediyor). */
    val charging: Boolean? = null,
    /** Araç şarjın bittiğini (hedefe ulaşıldı) bildiriyor mu. */
    val finished: Boolean? = null,
    val kind: ChargeKind? = null,
    val powerKw: Float? = null,
    val voltageV: Int? = null,
    val currentA: Float? = null,
    /** Hedefe kalan süre (dakika). */
    val remainingMin: Int? = null,
    /** Şarjın duracağı hedef yüzde. */
    val targetPercent: Int? = null,
) {
    /**
     * Şarj görünümü açılsın mı: araç şarj ettiğini ya da kablonun takılı olduğunu söylüyor,
     * ya da batarya gözle görülür güç çekiyor. Kablo çıkınca/akım kesilince false olur.
     */
    val isActive: Boolean
        get() = charging == true || plugged == true || (powerKw ?: 0f) >= MIN_ACTIVE_KW

    companion object {
        /** Bunun altındaki güç ölçüm gürültüsü sayılır. */
        const val MIN_ACTIVE_KW = 0.3f
    }
}

/**
 * Şarj sinyallerinin adları firmware'den kesin olarak çıkarılamadı (ayrıntı: CLAUDE.md, şarj bölümü).
 * Bu yüzden `BYDAutoFeatureIds$Charging` (ve yedek olarak `Energy`/`Statistic`) alanları çalışma
 * anında yansımayla listelenir ve her kavram için ada göre en uygun alan seçilir. Seçim saf bir
 * işlevdir ve birim testlidir; araçta hangi alanın seçildiği ilk okumada loglanır.
 */
internal object ChargeFields {
    data class Picked(
        val plugged: String? = null,
        val state: String? = null,
        val kind: String? = null,
        val power: String? = null,
        val voltage: String? = null,
        val current: String? = null,
        val restTotal: String? = null,
        val restHour: String? = null,
        val restMinute: String? = null,
        val target: String? = null,
    ) {
        val isEmpty: Boolean get() = listOf(plugged, state, kind, power, voltage, current, restTotal, restHour, restMinute, target).all { it == null }
    }

    /** Yazma, ayar, zamanlama ve dışarı güç verme (V2L) alanları okuma için hiçbir zaman seçilmez. */
    private val EXCLUDE = listOf(
        "_SET", "SET_", "DISCHARG", "V2L", "VTOL", "V2V", "BOOK", "APPOINT", "SCHEDULE", "RESERV", "TIMER",
        "LIMIT_SET", "MAX", "PERMIT", "ENABLE", "SWITCH", "REQUEST", "CMD", "FAULT", "ERROR", "WARN",
    )

    private fun ok(name: String) = EXCLUDE.none { it in name }
    private fun charge(name: String) = "CHARG" in name

    /** Adlardan ilk uyan; [names] sırası korunur, daha kısa (daha genel) ad öne alınır. */
    private fun first(names: List<String>, test: (String) -> Boolean): String? =
        names.filter { ok(it) && test(it) }.minByOrNull { it.length }

    fun pick(all: Collection<String>): Picked {
        val names = all.map { it.uppercase() }.distinct()
        val gun = { n: String -> ("GUN" in n || "CONNECT" in n || "PLUG" in n) }
        val plugged = first(names) { n ->
            gun(n) && ("STATE" in n || "STATUS" in n || "CONNECT" in n) && "TYPE" !in n && "LOCK" !in n
        }
        val kind = first(names) { n ->
            ("GUN_TYPE" in n || "CHARGE_TYPE" in n || "CHARGING_TYPE" in n || "CHARGE_MODE" in n || "CHARGING_MODE" in n) &&
                "LOCK" !in n
        }
        val rest = { n: String -> ("REST" in n || "REMAIN" in n || "LEFT" in n) && "TIME" in n }
        val restHour = first(names) { n -> rest(n) && "HOUR" in n }
        val restMinute = first(names) { n -> rest(n) && "MIN" in n }
        val restTotal = first(names) { n -> rest(n) && "HOUR" !in n && "MIN" !in n && "SEC" !in n }
        return Picked(
            plugged = plugged,
            state = first(names) { n ->
                charge(n) && ("STATE" in n || "STATUS" in n) && n != plugged && n != kind &&
                    !gun(n) && listOf("CAP", "PORT", "DOOR", "LOCK", "LIGHT", "MODE", "TYPE").none { it in n }
            },
            kind = kind,
            power = first(names) { n -> charge(n) && "POWER" in n && "STATE" !in n },
            voltage = first(names) { n -> charge(n) && "VOLT" in n && "STATE" !in n },
            current = first(names) { n -> charge(n) && "CURRENT" in n && "STATE" !in n },
            restTotal = restTotal.takeIf { restHour == null },
            restHour = restHour,
            restMinute = restMinute.takeIf { restHour != null },
            target = first(names) { n -> "SOC" in n && ("TARGET" in n || "UPPER" in n || "LIMIT" in n || "GOAL" in n) },
        )
    }
}

/**
 * Durum kodlarının anlamını, BYD cihaz sınıfındaki sabitlerin **adlarından** çözer
 * (ör. değer 2 ↔ `CHARGING_GUN_STATE_CONNECTED_AC`). Sabit yoksa anlam bilinmez (null),
 * uydurulmaz.
 */
internal object ChargeCodes {
    data class Meaning(val plugged: Boolean? = null, val charging: Boolean? = null, val finished: Boolean? = null, val kind: ChargeKind? = null)

    private val NEGATIVE = listOf("NONE", "NO_", "_NO", "NOT", "DISCONNECT", "UNCONNECT", "UNPLUG", "OFF", "IDLE", "INVALID", "STOP")
    private val FINISHED = listOf("FINISH", "COMPLETE", "FULL", "_END", "DONE")

    /**
     * [value] koduna, [hints] kelimelerinden birini içeren sabitlerden aynı değere sahip olanların
     * adıyla anlam verir. Birden çok sabit aynı değeri taşıyorsa en uzun (en özgül) ad kullanılır.
     */
    fun meaning(value: Int, constants: Map<String, Int>, hints: List<String>): Meaning? {
        val name = constants.entries
            .filter { (n, v) -> v == value && hints.any { it in n.uppercase() } && "_SET" !in n }
            .maxByOrNull { it.key.length }?.key?.uppercase() ?: return null
        val kind = when {
            Regex("(^|_)DC(_|$)").containsMatchIn(name) -> ChargeKind.DC
            Regex("(^|_)AC(_|$)").containsMatchIn(name) -> ChargeKind.AC
            else -> null
        }
        if (FINISHED.any { it in name }) return Meaning(plugged = null, charging = false, finished = true, kind = kind)
        if (NEGATIVE.any { it in name }) return Meaning(plugged = false, charging = false, kind = kind)
        val chargingWord = "CHARGING" in name || "_ING" in name || "WORK" in name
        val connectWord = "CONNECT" in name || "INSERT" in name || "PLUG" in name || kind != null
        return Meaning(
            plugged = if (connectWord || chargingWord) true else null,
            charging = if (chargingWord && "GUN" !in name) true else null,
            kind = kind,
        )
    }
}

/** Ham değerleri birime çeviren saf işlevler (ölçekler araçta doğrulanacak; bkz. CLAUDE.md). */
internal object ChargeScale {
    /** kW. Platform `getDouble` kW veriyor varsayılır; int okuma 400'ün üstündeyse W sayılır. */
    fun powerKw(asDouble: Double?, asInt: Int?): Float? {
        asDouble?.takeIf { it in 0.0..400.0 && it != 0.0 }?.let { return it.toFloat() }
        return when (asInt) {
            null -> asDouble?.takeIf { it == 0.0 }?.toFloat()
            in 0..400 -> asInt.toFloat()
            in 401..400_000 -> asInt / 1000f
            else -> null
        }
    }

    fun minutes(total: Int?, hour: Int?, minute: Int?): Int? = when {
        hour != null && hour in 0..72 -> hour * 60 + (minute?.takeIf { it in 0..59 } ?: 0)
        total != null && total in 0..72 * 60 -> total
        else -> null
    }

    fun target(value: Int?): Int? = value?.takeIf { it in 50..100 }
    fun voltage(value: Double?): Int? = value?.takeIf { it in 1.0..1000.0 }?.let { Math.round(it).toInt() }
    fun current(value: Double?): Float? = value?.takeIf { kotlin.math.abs(it) in 0.1..700.0 }?.let { kotlin.math.abs(it).toFloat() }
}

/** BYD veri yolundan şarj durumunu okur. BYD değilse ya da hiçbir şarj sinyali yoksa null. */
internal object BydCharging {
    private const val TAG = "BydBus"
    private const val CHARGING_DEVICE = 1009
    private val DEVICE_CLASSES = listOf("charging.BYDAutoChargingDevice", "energy.BYDAutoEnergyDevice")
    private val GROUPS = listOf("Charging", "Energy", "Statistic")

    private class Resolved(val ids: Map<String, Int>, val picked: ChargeFields.Picked, val constants: Map<String, Int>)

    @Volatile private var resolved: Resolved? = null
    @Volatile private var lastRaw: String? = null

    private fun resolve(): Resolved = resolved ?: synchronized(this) {
        resolved ?: run {
            // Önce Charging grubu; orada bulunamayan kavram için Energy/Statistic.
            val byGroup = GROUPS.associateWith { BydBus.groupFields(it) }
            val ids = LinkedHashMap<String, Int>()
            GROUPS.forEach { g -> byGroup[g].orEmpty().forEach { (n, id) -> ids.putIfAbsent(n, id) } }
            val primary = ChargeFields.pick(byGroup["Charging"].orEmpty().keys)
            val backup = ChargeFields.pick(ids.keys)
            val picked = ChargeFields.Picked(
                plugged = primary.plugged ?: backup.plugged,
                state = primary.state ?: backup.state,
                kind = primary.kind ?: backup.kind,
                power = primary.power ?: backup.power,
                voltage = primary.voltage ?: backup.voltage,
                current = primary.current ?: backup.current,
                restTotal = primary.restTotal ?: backup.restTotal.takeIf { primary.restHour == null },
                restHour = primary.restHour ?: backup.restHour.takeIf { primary.restTotal == null },
                restMinute = primary.restMinute ?: backup.restMinute.takeIf { primary.restTotal == null },
                target = primary.target ?: backup.target,
            )
            val constants = DEVICE_CLASSES.fold(emptyMap<String, Int>()) { acc, cls -> acc + BydBus.deviceConstants(cls) }
            // Teşhis: Charging grubundaki tüm alanlar ve seçim bir kez kayda yazılır.
            Log.i(TAG, "şarj alanları (${byGroup["Charging"].orEmpty().size}): ${byGroup["Charging"].orEmpty().keys.sorted().joinToString(",")}")
            Log.i(TAG, "şarj seçimi: $picked; sabit sayısı=${constants.size}")
            Resolved(ids, picked, constants).also { resolved = it }
        }
    }

    fun read(): ChargeState? {
        val r = resolve()
        if (r.picked.isEmpty) return null
        fun int(name: String?) = name?.let { r.ids[it] }?.let { BydBus.readId(it, CHARGING_DEVICE) }
        fun dbl(name: String?) = name?.let { r.ids[it] }?.let { BydBus.readDoubleId(it, CHARGING_DEVICE) }
        val p = r.picked

        val pluggedRaw = int(p.plugged)
        val stateRaw = int(p.state)
        val kindRaw = int(p.kind)
        val powerD = dbl(p.power)
        val powerI = int(p.power)
        val voltD = dbl(p.voltage) ?: int(p.voltage)?.toDouble()
        val currD = dbl(p.current) ?: int(p.current)?.toDouble()
        val restT = int(p.restTotal)
        val restH = int(p.restHour)
        val restM = int(p.restMinute)
        val targetRaw = int(p.target)

        val gunHints = listOf("GUN", "CONNECT", "PLUG")
        val stateHints = listOf("CHARG")
        val gun = pluggedRaw?.let { ChargeCodes.meaning(it, r.constants, gunHints) }
        val state = stateRaw?.let { ChargeCodes.meaning(it, r.constants, stateHints) }
        val kindMeaning = kindRaw?.let { ChargeCodes.meaning(it, r.constants, listOf("TYPE", "MODE", "GUN")) }

        var power = ChargeScale.powerKw(powerD, powerI)
        val volt = ChargeScale.voltage(voltD)
        val curr = ChargeScale.current(currD)
        if (power == null && volt != null && curr != null) power = volt * curr / 1000f

        // Değer değiştikçe tek satır: hangi alanın hangi ölçekte geldiği araçta buradan okunur.
        val raw = "tak=${p.plugged}:$pluggedRaw durum=${p.state}:$stateRaw tür=${p.kind}:$kindRaw " +
            "güç=${p.power}:$powerD/$powerI V=$voltD A=$currD kalan=$restT/$restH:$restM hedef=$targetRaw"
        if (raw != lastRaw) {
            lastRaw = raw
            Log.i(TAG, "şarj ham: $raw")
        }

        val finished = state?.finished ?: gun?.finished
        return ChargeState(
            plugged = gun?.plugged ?: state?.plugged?.takeIf { it },
            charging = state?.charging ?: gun?.charging ?: if (finished == true) false else null,
            finished = finished,
            kind = kindMeaning?.kind ?: gun?.kind ?: state?.kind,
            powerKw = power,
            voltageV = volt,
            currentA = curr,
            remainingMin = ChargeScale.minutes(restT, restH, restM),
            targetPercent = ChargeScale.target(targetRaw),
        )
    }
}
