package app.pano.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.pano.launcher.MainActivity
import app.pano.launcher.phone.CallHub
import app.pano.launcher.phone.CallInfo
import app.pano.launcher.phone.CallPhase
import app.pano.launcher.phone.CallScreenText
import app.pano.launcher.settings.LayoutStyle
import app.pano.launcher.settings.Settings as PanoSettings
import app.pano.launcher.ui.CallCard
import app.pano.launcher.ui.CallCardWidth
import app.pano.launcher.ui.theme.LocalPano
import app.pano.launcher.ui.theme.PanoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Görüşme sırasında BYD'nin arama pencereleri yerine Pano'nun kartı.
 *
 * BYD iki pencere açar: görüşmede tam ekran `com.byd.bluetoothcall/.BluetoothCallActivity`, gelen
 * aramada `PhoneCallDialog` penceresi. Pano:
 *  - Arama etkinliği öne gelince önce onu tam ekran Pano kartıyla örter, penceredeki yazıları
 *    (kişi adı, numara, durum, süre) erişilebilirlik ağacından okur, sonra **arkaya alır**: kullanıcı
 *    hangi uygulamadaysa (Pano dahil) o yeniden öne getirilir. BYD etkinliği tekrar tekrar öne
 *    gelirse (15 sn'de 4 kez) arkaya almaktan vazgeçilir, tam ekran Pano kartı onu örtmeye devam eder.
 *  - Etkinlik olmayan arama penceresini (gelen arama diyaloğu) aynı yerde Pano kartıyla örter.
 *    Erişilebilirlik katmanı Android 10'da altındaki pencereleri erişilebilirlik ağacından
 *    gizlemez; BYD penceresi okunmaya ve gerekirse düğmesine basılmaya devam eder.
 *  - Başka bir uygulama öndeyken kart sağ üstte küçük durur. Ufuk'ta Pano öndeyken katman açılmaz,
 *    kartı Pano kendisi çizer ([covering] false).
 *
 * Kart erişilebilirlik hizmetinin `TYPE_ACCESSIBILITY_OVERLAY` katmanında, sistem diyaloglarının da
 * üstünde. Emülatörde aynı yol Android'in çeviricisiyle (`com.android.dialer` InCallActivity) denenir.
 */
object CallOverlay {
    private const val TAG = "CallOverlay"
    private const val PREFS = "pano_call"
    private const val KEY_DIALOG = "incoming_dialog_rect"

    /** Arama penceresi açan paketler: BYD ve (emülatörde deneme için) Android'in çeviricisi. */
    private val CALL_PACKAGES = setOf(
        "com.byd.bluetoothcall", "com.android.dialer", "com.google.android.dialer", "com.android.incallui",
    )

    /** Görüşme etkinliği mi (BYD'nin rehber ekranı gibi telefon uygulamasının diğer ekranları değil). */
    private fun isCallActivity(cls: String) =
        !cls.contains("Contacts", ignoreCase = true) &&
            (cls.contains("InCall") || cls.endsWith("CallActivity") || cls.contains("CallDialog"))

    private enum class Mode { None, Corner, Cover, Full }

    /** Katman açık ve kartı o çiziyor: Pano kendi kartını çizmesin. */
    val covering = mutableStateOf(false)

    private val call = mutableStateOf<CallInfo?>(null)
    private val minHeightPx = mutableStateOf(0)

    private var service: AccessibilityService? = null
    private var scope: CoroutineScope? = null
    private val handler = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var owner: Owner? = null
    private var mode = Mode.None
    private var rect = Rect()

    /** Arama penceresinden önce öndeki uygulama (arkaya alınca bu geri gelir). */
    private var lastPkg: String? = null
    /** Arama etkinliği şu an önde mi (arkaya alınana kadar tam ekran örtülür). */
    private var callActivityFront = false
    private var frontSince = 0L
    /** Arkaya alma zamanlandı (tarama aynı pencere için tekrar zamanlamasın). */
    private var pushPending = false
    private val pushes = ArrayDeque<Long>()
    private var gaveUpPushing = false
    /** Arama penceresinin etkinlik olmayan (diyalog) penceresinin yeri; yoksa null. */
    private var dialogRect: Rect? = null
    /** Bu aramada loglanmış pencere düzenleri (görünüm kimlikleri kümesi). */
    private val loggedTrees = HashSet<Set<String>>()
    private var lastDismiss = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            scan()
            if (live()) handler.postDelayed(this, 500)
        }
    }

    private fun live() = CallHub.state.value.let { it != null && it.phase != CallPhase.Ended }

    fun attach(s: AccessibilityService) {
        detach()
        service = s
        CallHub.init(s)
        CallHub.screenAction = { action -> pressOnScreen(action) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { sc ->
            sc.launch {
                combine(CallHub.state, ForegroundApp.pkg, snapshotFlow { PanoSettings.state.value.layout }) { c, _, _ -> c }.collect { c ->
                    val wasLive = call.value.let { it != null && it.phase != CallPhase.Ended }
                    call.value = c
                    val isLive = live()
                    if (isLive && !wasLive) onCallStarted()
                    if (c == null) onCallGone()
                    evaluate()
                }
            }
        }
    }

    fun detach() {
        scope?.cancel(); scope = null
        handler.removeCallbacksAndMessages(null)
        CallHub.screenAction = null
        removeWindow()
        service = null
    }

    /** Erişilebilirlik olayı: öndeki pencere değişti. */
    fun onWindowChanged(pkg: String?, cls: String?) {
        val s = service ?: return
        if (pkg.isNullOrBlank() || cls.isNullOrBlank()) return
        if (pkg in CALL_PACKAGES) {
            if (live() && isCallActivity(cls) && ForegroundApp.isActivity(s, pkg, cls)) onCallActivityFront()
            return
        }
        if (ForegroundApp.isActivity(s, pkg, cls)) {
            lastPkg = pkg
            if (callActivityFront) { callActivityFront = false; evaluate() }
        }
    }

    /**
     * Erişilebilirlik olayı: pencere listesi değişti. BYD'nin gelen arama penceresi odak almadığı için
     * yalnızca bununla duyulur; 500 ms'lik taramayı beklemeden hemen örtülür (yoksa bir an görünüyordu).
     */
    fun onWindowsChanged() {
        if (service == null) return
        if (live()) { scan(); return }
        // Pencere HFP olayından önce açıldı: arama durumu hemen okunur, gelince tarama başlar.
        if (callWindows().isNotEmpty()) CallHub.refresh()
    }

    /** BYD'nin gelen arama penceresinin son görülen yeri: bir sonraki aramada kart baştan oraya açılır. */
    private fun rememberedDialog(): Rect? {
        val p = service?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return null
        val v = p.getString(KEY_DIALOG, null) ?: return null
        return runCatching { Rect.unflattenFromString(v) }.getOrNull()?.takeIf { !it.isEmpty }
    }

    private fun rememberDialog(r: Rect) {
        if (r.isEmpty || r == rememberedDialog()) return
        service?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY_DIALOG, r.flattenToString())?.apply()
    }

    private fun onCallStarted() {
        loggedTrees.clear()
        lastDismiss = 0L
        gaveUpPushing = false
        pushes.clear()
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        // Arama etkinliği durumdan önce açılmış olabilir.
        val front = ForegroundApp.pkg.value
        if (front != null && front in CALL_PACKAGES) onCallActivityFront()
    }

    private fun onCallGone() {
        handler.removeCallbacks(pollRunnable)
        callActivityFront = false
        pushPending = false
        dialogRect = null
    }

    /** BYD'nin görüşme ekranı öne geldi: hemen örtülür, okunur, sonra arkaya alınır. */
    private fun onCallActivityFront() {
        if (!callActivityFront) frontSince = SystemClock.uptimeMillis()
        callActivityFront = true
        evaluate()
        if (gaveUpPushing || pushPending) return
        pushPending = true
        handler.postDelayed({
            pushPending = false
            scan()
            if (callActivityFront && live()) pushBack()
        }, 400)
    }

    private fun pushBack() {
        val s = service ?: return
        val now = SystemClock.uptimeMillis()
        while (pushes.isNotEmpty() && now - pushes.first() > 15_000) pushes.removeFirst()
        if (pushes.size >= 4) {
            gaveUpPushing = true
            Log.i(TAG, "arama ekranı sürekli öne geliyor: arkaya alınmıyor, Pano kartı örtüyor")
            evaluate()
            return
        }
        pushes.addLast(now)
        val target = lastPkg ?: s.packageName
        val intent = when {
            target == s.packageName -> Intent(s, MainActivity::class.java)
            ForegroundApp.isHome(s, target) -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(target)
            else -> s.packageManager.getLaunchIntentForPackage(target) ?: Intent(s, MainActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { s.startActivity(intent) }
            .onSuccess { Log.i(TAG, "arama ekranı arkaya alındı → $target") }
            .onFailure { Log.w(TAG, "arkaya alınamadı: $it"); gaveUpPushing = true; evaluate() }
    }

    // ── BYD penceresini okuma ──

    private fun callWindows(): List<Pair<AccessibilityWindowInfo, AccessibilityNodeInfo>> {
        val s = service ?: return emptyList()
        return runCatching {
            s.windows.mapNotNull { w ->
                val root = w.root ?: return@mapNotNull null
                if (root.packageName?.toString() in CALL_PACKAGES) w to root else null
            }
        }.getOrDefault(emptyList())
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (out.size > 300 || depth > 30) return
        out += node
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    private fun scan() {
        if (service == null || !live()) return
        val windows = callWindows()
        var dialog: Rect? = null
        var dismiss: AccessibilityNodeInfo? = null
        val texts = ArrayList<CallScreenText.Node>()
        for ((w, root) in windows) {
            val nodes = ArrayList<AccessibilityNodeInfo>().also { collect(root, it) }
            nodes.forEach { n ->
                val t = (n.text ?: n.contentDescription)?.toString()
                if (!t.isNullOrBlank()) texts += CallScreenText.Node(n.viewIdResourceName, t)
            }
            // Pencerenin düzeni değişince (gelen arama → görüşme) ağaç yeniden loglanır.
            val ids = nodes.mapNotNull { it.viewIdResourceName }.toSet()
            if (ids !in loggedTrees) { loggedTrees += ids; logTree(w, nodes) }
            val b = Rect().also { w.getBoundsInScreen(it) }
            val screen = screenSize()
            val small = b.width() * b.height() < screen.first * screen.second * 0.7f
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || small) {
                dialog = b
                dismiss = dismiss ?: nodes.firstOrNull { n -> CallScreenText.isDismissId(n.viewIdResourceName) }
            }
        }
        // Öndeki uygulama değişince her zaman "etkinlik" olayı gelmiyor (görev öne alınınca olayın sınıfı
        // kök görünüm olabiliyor): arama ekranının görünüp görünmediğine pencere listesinden bakılır.
        val fullCallWindow = windows.any { (w, _) ->
            val b = Rect().also { w.getBoundsInScreen(it) }
            val (sw, sh) = screenSize()
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION && b.width() * b.height() >= sw * sh * 0.7f
        }
        if (fullCallWindow && !callActivityFront) onCallActivityFront()
        else if (!fullCallWindow && callActivityFront && !pushPending && SystemClock.uptimeMillis() - frontSince > 300) {
            callActivityFront = false
            evaluate()
        }
        var parsed: CallScreenText.Parsed? = null
        if (texts.isNotEmpty()) {
            parsed = CallScreenText.parse(texts)
            if (!parsed.isEmpty) CallHub.onScreen(parsed)
        }
        // Yalnızca gelen arama penceresinin yeri hatırlanır (giden aramadaki pencere başka yerde, büyük).
        if (call.value?.phase == CallPhase.Incoming) dialog?.let { rememberDialog(it) }
        // BYD'nin küçük penceresi kendi "gizle" düğmesiyle kapatılır: ad ve numara okunduktan sonra
        // (pencere gidince ad da gider), aynı pencere için en çok 1,5 sn'de bir.
        val known = parsed?.let { it.name != null || it.number != null } == true || CallHub.state.value?.number != null
        dismiss?.let { d ->
            val now = SystemClock.uptimeMillis()
            if (known && now - lastDismiss > 1500) {
                lastDismiss = now
                var target: AccessibilityNodeInfo? = d
                while (target != null && !target.isClickable) target = target.parent
                val ok = (target ?: d).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "BYD penceresi gizlendi (${d.viewIdResourceName}) → $ok")
            }
        }
        if (dialog != dialogRect) { dialogRect = dialog; evaluate() }
    }

    /** Araçta alan adlarını bulmak için: arama penceresinin ağacı bir kez loglanır (logcat CallOverlay). */
    private fun logTree(w: AccessibilityWindowInfo, nodes: List<AccessibilityNodeInfo>) {
        val b = Rect().also { w.getBoundsInScreen(it) }
        Log.i(TAG, "arama penceresi: tür=${w.type} yer=$b düğüm=${nodes.size}")
        nodes.forEach { n ->
            val t = n.text?.toString()?.replace(Regex("\\d(?=\\d{2})"), "•")
            Log.i(TAG, "  ${n.className?.toString()?.substringAfterLast('.')} id=${n.viewIdResourceName} " +
                "yazı=$t açıklama=${n.contentDescription} tıklanır=${n.isClickable}")
        }
    }

    /** Hiçbir yol olmazsa BYD penceresindeki düğmeye basılır (alan adları araçta doğrulanmadı). */
    private fun pressOnScreen(action: CallHub.Action): Boolean {
        val (ids, words) = when (action) {
            CallHub.Action.Answer -> listOf("answer", "accept", "pickup", "pick_up") to listOf("cevapla", "yanıtla", "answer", "接听")
            CallHub.Action.Reject -> listOf("reject", "refuse", "decline", "hangup", "hang_up") to listOf("reddet", "decline", "reject", "拒接", "挂断")
            CallHub.Action.HangUp -> listOf("hangup", "hang_up", "end", "terminate") to listOf("kapat", "bitir", "hang up", "end", "挂断")
            CallHub.Action.Mute -> listOf("mute", "mic") to listOf("sessiz", "mute", "静音")
            CallHub.Action.Transfer -> listOf("private", "switch", "transfer", "handset", "voice") to listOf("telefona", "özel", "private", "私密", "切换")
        }
        for ((_, root) in callWindows()) {
            val nodes = ArrayList<AccessibilityNodeInfo>().also { collect(root, it) }
            val hit = nodes.firstOrNull { n ->
                val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase()
                id != null && ids.any { id.contains(it) }
            } ?: nodes.firstOrNull { n ->
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
                t != null && words.any { t == it }
            } ?: continue
            var target: AccessibilityNodeInfo? = hit
            while (target != null && !target.isClickable) target = target.parent
            val ok = (target ?: hit).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "BYD düğmesi ($action, ${hit.viewIdResourceName}) → $ok")
            if (ok) return true
        }
        return false
    }

    // ── Katman ──

    private fun screenSize(): Pair<Int, Int> {
        val wm = service?.getSystemService(WindowManager::class.java) ?: return 1280 to 720
        val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        return m.widthPixels to m.heightPixels
    }

    /** Tasarım tuvalinin (1600×900) bir biriminin piksel karşılığı. */
    private fun scale(): Float = screenSize().let { (w, h) -> min(w / 1600f, h / 900f) }

    private fun evaluate() {
        val s = service ?: return
        val c = call.value
        val ufuk = PanoSettings.state.value.layout == LayoutStyle.Ufuk
        val panoFront = ForegroundApp.pkg.value == s.packageName && ufuk
        // Gelen aramada BYD penceresi henüz bulunmadıysa son görüldüğü yer örtülür: pencere bir an
        // görünmez, kart da açıldıktan sonra yer değiştirmez.
        // Arama bitince BYD penceresi de kapanır: "Arama bitti" kartı onun boş alanını örtmesin.
        val cover = if (c?.phase == CallPhase.Ended) null
            else dialogRect ?: if (c?.phase == CallPhase.Incoming) rememberedDialog() else null
        val newMode = when {
            c == null -> Mode.None
            // Bitti: kartı çizen yer onu kısa süre "Arama bitti" ile gösterir.
            callActivityFront -> Mode.Full
            cover != null -> Mode.Cover
            panoFront -> Mode.None
            else -> Mode.Corner
        }
        val (sw, sh) = screenSize()
        val k = scale()
        val cardW = (CallCardWidth.value * k).toInt()
        val margin = (24 * k).toInt()
        val newRect = when (newMode) {
            Mode.Full -> Rect(0, 0, sw, sh)
            Mode.Cover -> cover!!.let { d ->
                val w = maxOf(d.width(), cardW)
                val left = d.left.coerceIn(0, (sw - w).coerceAtLeast(0))
                Rect(left, d.top, left + w, d.bottom)
            }
            Mode.Corner -> Rect(sw - cardW - margin, margin, sw - margin, margin)
            Mode.None -> Rect()
        }
        if (newMode == mode && newRect == rect) return
        mode = newMode
        rect = newRect
        covering.value = newMode != Mode.None
        if (newMode == Mode.None) { removeWindow(); return }
        minHeightPx.value = if (newMode == Mode.Cover) newRect.height() else 0
        showWindow(s)
    }

    private fun params(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        if (mode == Mode.Full) WindowManager.LayoutParams.MATCH_PARENT else rect.width(),
        if (mode == Mode.Full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = rect.left
        y = rect.top
        title = "Pano arama"
    }

    private val full = mutableStateOf(false)

    private fun showWindow(s: AccessibilityService) {
        val wm = s.getSystemService(WindowManager::class.java) ?: return
        full.value = mode == Mode.Full
        view?.let { v ->
            runCatching { wm.updateViewLayout(v, params()) }.onFailure { Log.w(TAG, "katman güncellenemedi: $it") }
            return
        }
        val o = Owner().also { it.start() }
        val k = scale()
        val v = ComposeView(s).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                PanoTheme(PanoSettings.state.value) {
                    CompositionLocalProvider(LocalDensity provides Density(k, 1f)) {
                        val info = call.value ?: return@CompositionLocalProvider
                        if (full.value) {
                            // BYD'nin tam ekran arama ekranını örter: Pano zemini, ortada kart.
                            Box(Modifier.fillMaxSize().background(LocalPano.current.background), contentAlignment = Alignment.Center) {
                                CallCard(info, Modifier.width(CallCardWidth + 120.dp))
                            }
                        } else {
                            val minH = (minHeightPx.value / k).dp
                            CallCard(info, Modifier.fillMaxWidth().heightIn(min = minH).padding(0.dp))
                        }
                    }
                }
            }
        }
        runCatching { wm.addView(v, params()) }
            .onSuccess { view = v; owner = o; Log.i(TAG, "arama kartı açıldı ($mode $rect)") }
            .onFailure { Log.w(TAG, "arama kartı açılamadı: $it"); o.destroy() }
    }

    private fun removeWindow() {
        val v = view ?: return
        view = null
        covering.value = false
        mode = Mode.None
        runCatching { service?.getSystemService(WindowManager::class.java)?.removeViewImmediate(v) }
        owner?.destroy()
        owner = null
    }

    private class Owner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = saved.savedStateRegistry
        fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
