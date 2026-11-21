package app.pano.launcher

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.pano.launcher.settings.FavoriteApp

/**
 * Son kullanılan uygulamalar (haritanın altındaki adada favorilerin yanında durur).
 * Android başka uygulamaların açık görevlerini launcher'lara göstermiyor; en yakın bilgi
 * kullanım istatistikleri. Bunun için kullanıcının bir kez "kullanım erişimi" vermesi gerekir;
 * izin yoksa liste boş kalır.
 */
object RecentApps {
    private const val WINDOW_MS = 12 * 60 * 60 * 1000L
    private const val MAX = 4

    private val _state = mutableStateOf<List<FavoriteApp>>(emptyList())
    val state: State<List<FavoriteApp>> = _state

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Listeyi yeniler; launcher öne geldikçe çağrılır. */
    fun refresh(context: Context) {
        if (!hasAccess(context)) return
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val pm = context.packageManager
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        _state.value = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - WINDOW_MS, now)
                .filter { it.lastTimeUsed > now - WINDOW_MS && it.totalTimeInForeground > 0 }
                .sortedByDescending { it.lastTimeUsed }
                .map { it.packageName }
                .distinct()
                .filter { it != context.packageName && it !in homes }
                .mapNotNull { pkg ->
                    val component = pm.getLaunchIntentForPackage(pkg)?.component ?: return@mapNotNull null
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    FavoriteApp(pkg, component.className, label)
                }
                .take(MAX)
        }.getOrDefault(emptyList())
    }
}
