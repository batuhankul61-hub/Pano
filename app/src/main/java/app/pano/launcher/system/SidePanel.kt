package app.pano.launcher.system

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent

/**
 * Tesla tarzı sağ panel: başka bir uygulama (Spotify, YouTube…) Pano'nun sağ panelinin tam
 * sınırlarında bir pencere (Android'in "freeform" penceresi) olarak açılır; Pano tam ekran kalır,
 * sol çubuk, harita ve alt şerit kullanılmaya devam eder.
 *
 * Android 10'da Pano'ya dokunmak Pano'yu öne alır ve pencere arkada kalır. Bu yüzden: Pano'ya
 * dokunulduğu için öne geçtiysek, parmak kalkınca pencere animasyonsuz yeniden öne getirilir.
 * Pano başka bir yoldan öne geçtiyse (uygulama ✕ ile kapandı, ana ekran tuşu…) ya da Pano'da
 * sağ panelde kendi ekranı açıldıysa, pencere geri getirilmez.
 *
 * Gereken: cihazda freeform pencere desteği (BYD DiLink 3.0'da açık: enable_freeform_support=1).
 */
object SidePanel {
    private const val TAG = "SidePanel"
    private const val WINDOWING_MODE_FREEFORM = 5
    /** Öne geçişten bu kadar önce Pano'ya dokunulduysa geçiş dokunmadan sayılır. */
    private const val TOUCH_WINDOW_MS = 600L

    /** Sağ panelin ekrandaki sınırları (piksel); CarPlay düzeni ölçüp yazar. */
    @Volatile var bounds: Rect? = null

    /** Sağ panelde şu an açık tutulan uygulama; null = yok. */
    @Volatile var current: Intent? = null
        private set

    private var lastDownAt = 0L
    private var pendingRaise = false
    private val handler = Handler(Looper.getMainLooper())

    val isOpen: Boolean get() = current != null

    /**
     * Android, yeni pencerenin bir köşesi var olan (gizli de olsa) bir pencerenin köşesine 4 px'ten
     * yakınsa onu yana kaydırıyor ("çakışmayı önleme"); paneldeki her yeni uygulama 80 px sola
     * kayıyordu. Bu yüzden her uygulamaya birkaç piksel farklı bir yuva verilir (en fazla 10 px):
     * hiçbir köşe çakışmaz, pencere panelde kalır. Yuvalar son kullanılan uygulamalara dağıtılır.
     */
    private val SLOTS = listOf(0 to 0, 5 to 0, 0 to 5, 5 to 5, 10 to 0, 0 to 10, 10 to 5, 5 to 10, 10 to 10)
    private val slotOf = LinkedHashMap<String, Int>()

    private fun slotFor(pkg: String?): Pair<Int, Int> {
        if (pkg == null) return SLOTS[0]
        slotOf[pkg]?.let { s -> slotOf.remove(pkg); slotOf[pkg] = s; return SLOTS[s] }
        // En eski kullanılanın yuvası boşa çıkar; boş yuva yoksa onu devral.
        val used = slotOf.values.toSet()
        val free = SLOTS.indices.firstOrNull { it !in used } ?: slotOf.entries.first().let { e -> slotOf.remove(e.key); e.value }
        slotOf[pkg] = free
        return SLOTS[free]
    }

    /** Uygulamayı sağ panelde açacak seçenekler; panel ölçülmediyse ya da gizli API yoksa null. */
    /**
     * Android freeform pencereyi durum çubuğunun altına koymaz (çubuk görünürken aşağı iter).
     * Çubuklar her yerde gizli değilse panel penceresi durum çubuğunun altından başlar; böylece
     * pencere istenen yere oturur, çerçeve de hizalı kalır.
     */
    @Volatile var statusBarHeight = 0
    @Volatile var barsHiddenEverywhere = false

    fun options(pkg: String? = current?.let { it.component?.packageName ?: it.`package` }): Bundle? {
        val measured = bounds ?: return null
        val r = if (!barsHiddenEverywhere && measured.top < statusBarHeight) Rect(measured).apply { top = statusBarHeight } else measured
        val (dx, dy) = slotFor(pkg)
        return runCatching {
            // Pencere panelden 10 px küçük, yuvaya göre yalnızca yeri değişir: iki yuva ya hem sol hem
            // sağ kenarda ya da hem üst hem alt kenarda ≥5 px ayrışır, hiçbir köşe çakışmaz.
            val window = Rect(r.left + dx, r.top + dy, r.right - (10 - dx), r.bottom - (10 - dy))
            val o = ActivityOptions.makeBasic().setLaunchBounds(window)
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(o, WINDOWING_MODE_FREEFORM)
            lastWindow = window
            o.toBundle()
        }.onFailure { Log.w(TAG, "pencere seçenekleri: $it") }.getOrNull()
    }

    /** Paneldeki uygulama penceresinin istenen sınırları (çerçeve buna oturur). */
    private var lastWindow: Rect? = null

    /** Compose için: sağ panelde başka bir uygulama var mı (Pano o alanı boş bırakır). */
    val openState = androidx.compose.runtime.mutableStateOf(false)

    /** [Launch] uygulamayı panelde açtıktan sonra çağırır. */
    fun opened(activity: Activity, intent: Intent) {
        current = Intent(intent)
        openState.value = true
        Log.i(TAG, "sağ panelde: ${intent.component ?: intent.`package`} istenen=$lastWindow panel=$bounds çubuk=$statusBarHeight her_yerde_gizli=$barsHiddenEverywhere")
        val window = lastWindow ?: return
        val label = runCatching {
            val pm = activity.packageManager
            val pkg = intent.component?.packageName ?: intent.`package` ?: pm.resolveActivity(intent, 0)?.activityInfo?.packageName
            pm.getApplicationLabel(pm.getApplicationInfo(pkg!!, 0)).toString()
        }.getOrDefault("")
        // Pano'nun panel alanını boşaltması bir kare sürsün; çerçeve köşeleri o görüntüden alır.
        handler.postDelayed({ if (current != null) PanelFrame.show(activity, Rect(window), label) }, 350)
    }

    /** Pano'da başka bir şey sağ paneli kullanacak (ana pano, Pano'nun kendi ekranları): bırak. */
    fun close() {
        if (current != null) Log.i(TAG, "sağ panel bırakıldı")
        current = null
        openState.value = false
        pendingRaise = false
        PanelFrame.hide()
    }

    /** Çerçevedeki ✕: uygulama arkaya alınır, Pano'nun kartları geri gelir (uygulama kapanmaz). */
    fun dismiss(activity: Activity) {
        close()
        runCatching {
            activity.startActivity(
                Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---- MainActivity'den ----------------------------------------------------

    private var fingerDown = false

    fun onTouch(activity: Activity, ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDownAt = SystemClock.uptimeMillis()
                fingerDown = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingerDown = false
                if (pendingRaise) {
                    pendingRaise = false
                    // Dokunuşun kendi işi (örn. Nereye? ekranını açmak → close()) önce yapılsın.
                    handler.postDelayed({ raise(activity) }, 60)
                }
            }
        }
    }

    /**
     * Pano öne geçti (pencere odağı). Odak, dokunuştan hemen önce de gelebildiği için karar kısa
     * bir beklemeden sonra verilir: yakın zamanda Pano'ya dokunulduysa pencere geri getirilir.
     */
    fun onTopResumed(activity: Activity, top: Boolean) {
        if (!top || current == null) return
        val focusAt = SystemClock.uptimeMillis()
        handler.postDelayed({
            if (current == null) return@postDelayed
            if (lastDownAt >= focusAt - TOUCH_WINDOW_MS) {
                if (fingerDown) pendingRaise = true else raise(activity)
            } else {
                // Uygulama kapandı ya da başka bir yoldan Pano'ya dönüldü.
                close()
            }
        }, 200)
    }

    private fun raise(activity: Activity) {
        val intent = current ?: return
        if (activity.isFinishing) return
        val opts = options() ?: return
        runCatching {
            activity.startActivity(
                Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                opts,
            )
            activity.overridePendingTransition(0, 0)
        }.onFailure { Log.w(TAG, "öne getirilemedi: $it"); close() }
    }
}
