package app.pano.launcher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Öndeki uygulamanın paketi. Kaynağı Pano'nun erişilebilirlik hizmetinin ([PanoAccessibility])
 * "pencere durumu değişti" olayları; hizmet kapalıysa değer null kalır (bilinmiyor).
 *
 * Yalnızca etkinlik (Activity) pencereleri sayılır: ses paneli, bildirim, başka uygulamaların
 * "üstte göster" katmanları gibi pencereler de olay üretir ama öndeki uygulamayı değiştirmez.
 */
object ForegroundApp {
    private const val TAG = "ForegroundApp"

    private val _pkg = MutableStateFlow<String?>(null)
    val pkg: StateFlow<String?> = _pkg.asStateFlow()

    /** Sınıf adı → etkinlik mi; aynı pencereler sık geldiği için önbellek. */
    private val activityCache = HashMap<String, Boolean>()

    private var homePackages: Set<String>? = null

    /** Erişilebilirlik olayından çağrılır. */
    fun onWindowChanged(context: Context, pkg: String?, cls: String?) {
        if (pkg.isNullOrBlank() || cls.isNullOrBlank()) return
        if (isActivity(context, pkg, cls) && _pkg.value != pkg) {
            _pkg.value = pkg
            Log.d(TAG, "önde: $pkg")
        }
    }

    /** Pencere bir etkinliğe (Activity) mi ait; diyalog, katman vb. değil. */
    fun isActivity(context: Context, pkg: String, cls: String): Boolean = activityCache.getOrPut("$pkg/$cls") {
        runCatching { context.packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
    }

    /**
     * Aracın kendi ana ekranı (Pano dışındaki HOME uygulamaları). Ayarlar'ın "FallbackHome"u da
     * HOME olarak görünür; Ayarlar uygulaması önde diye ana ekran sanılmasın diye dışarıda kalır.
     */
    fun homePackages(context: Context): Set<String> = homePackages ?: run {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val all = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
        all.mapNotNull { it.activityInfo }
            .filterNot { it.packageName == context.packageName || it.name.endsWith("FallbackHome") }
            .map { it.packageName }
            .toSet()
            .also { homePackages = it; Log.i(TAG, "ana ekran paketleri: $it") }
    }

    /** Öndeki uygulama aracın ana ekranı mı; bilinmiyorsa false. */
    fun isHome(context: Context, pkg: String?): Boolean = pkg != null && pkg in homePackages(context)
}
