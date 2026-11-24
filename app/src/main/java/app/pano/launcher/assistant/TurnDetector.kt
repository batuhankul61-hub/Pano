package app.pano.launcher.assistant

/**
 * Kullanıcının konuşmasının nerede başlayıp bittiğini mikrofon seviyesinden bulur.
 *
 * Neden: Gemini Live'ın kendi konuşma algılaması (otomatik VAD) arka plan gürültüsünde konuşmanın
 * başladığını görüp bittiğini görmüyordu; Google cevap vermeden bekleyip kalıyordu (emülatörde 8
 * denemenin 3'ü). Araçta yol/motor sesiyle bu daha sık olur. Bu yüzden turun başını ve sonunu Pano
 * bildirir (`activityStart` / `activityEnd`).
 *
 * Ortam gürültüsüne uyum: konuşma yokken seviyenin yavaş ortalaması (gürültü tabanı) izlenir; eşikler
 * bunun katlarıdır, böylece gürültülü araçta eşik kendiliğinden yükselir.
 *
 * Android'e bağımlı değildir; her 40 ms'lik parça için [feed] çağrılır.
 */
class TurnDetector(
    /** Konuşma sayılmak için en düşük seviye (sessiz ortamda). */
    private val minStartLevel: Float = 0.02f,
    /** Konuşmanın sürdüğü sayılan en düşük seviye (sessiz ortamda). */
    private val minContinueLevel: Float = 0.012f,
    /** Başlangıç için gürültü tabanının kaç katı gerekir. */
    private val startFactor: Float = 3f,
    /** Konuşmanın sürmesi için gürültü tabanının kaç katı gerekir. */
    private val continueFactor: Float = 2f,
    /** Başlangıç sayılması için üst üste kaç parça eşiği aşmalı (2 × 40 ms; tıkırtılar sayılmaz). */
    private val startChunks: Int = 2,
    /** Bu kadar sessizlik konuşmanın bittiği demektir. */
    private val endSilenceMs: Long = 800,
    /** Tek bir konuşma en fazla bu kadar sürer (gürültü hiç düşmezse bile tur kapanır). */
    private val maxTurnMs: Long = 15_000,
) {
    enum class Action { None, Start, End }

    /** Konuşma sürüyor mu. */
    var speaking = false
        private set

    /** Ortam gürültüsü tahmini (konuşma yokken seviyenin yavaş ortalaması). */
    var noiseFloor = 0f
        private set

    private var above = 0
    private var startedAt = 0L
    private var lastLoud = 0L
    private var calibrating = 0

    private companion object {
        /** Oturum başında ortam ölçümü: 5 × 40 ms. */
        const val CALIBRATION_CHUNKS = 5
    }

    fun startThreshold() = maxOf(minStartLevel, noiseFloor * startFactor)
    private fun continueThreshold() = maxOf(minContinueLevel, noiseFloor * continueFactor)

    /** Bir parçanın seviyesi ve zamanı (ms). Konuşma başladıysa [Action.Start], bittiyse [Action.End]. */
    fun feed(level: Float, now: Long): Action {
        // İlk parçalar yalnızca ortamı ölçer: gürültülü araçta oturum açılır açılmaz yol sesi
        // konuşma sanılmasın.
        if (calibrating < CALIBRATION_CHUNKS) {
            calibrating++
            noiseFloor += (level - noiseFloor) / calibrating
            return Action.None
        }
        if (!speaking) {
            if (level > startThreshold()) {
                if (++above >= startChunks) {
                    speaking = true
                    above = 0
                    startedAt = now
                    lastLoud = now
                    return Action.Start
                }
            } else {
                above = 0
                // Gürültü tabanı yalnızca konuşma yokken, yavaşça öğrenilir.
                noiseFloor += (level - noiseFloor) * 0.05f
            }
            return Action.None
        }
        if (level > continueThreshold()) lastLoud = now
        if (now - lastLoud >= endSilenceMs) {
            speaking = false
            return Action.End
        }
        if (now - startedAt >= maxTurnMs) {
            // Seviye hiç düşmedi: bu büyük ihtimalle konuşma değil, yeni ve yüksek bir gürültü.
            // Tabanı ona çek ki hemen yeni bir tur başlamasın.
            noiseFloor = maxOf(noiseFloor, level)
            speaking = false
            return Action.End
        }
        return Action.None
    }

    /** Asistan konuşurken mikrofon dinlenmez: yarım kalan bir konuşma unutulur (gürültü tabanı kalır). */
    fun reset() {
        speaking = false
        above = 0
    }
}
