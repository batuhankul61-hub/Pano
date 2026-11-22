package app.pano.launcher.system

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Sistem çubuklarını (üst durum çubuğu + aracın alt çubuğu) yalnızca Pano'da değil, sağ panelde
 * ya da tam ekran açılan uygulamalarda da gizler. Android 10'daki genel "sürükleyici mod" ayarıyla
 * (`policy_control = immersive.full=*`) yapılır; çubuklar ekran kenarından kaydırınca kısa süre
 * görünür. Bu ayarı yazmak için Pano'ya bir kez bilgisayardan izin verilmesi gerekir:
 *
 *     adb shell pm grant app.pano.launcher android.permission.WRITE_SECURE_SETTINGS
 *
 * İzin yoksa yalnızca Pano'nun kendi penceresinde gizlenir.
 */
object SystemBars {
    private const val TAG = "SystemBars"
    private const val KEY = "policy_control"
    private const val IMMERSIVE_ALL = "immersive.full=*"

    fun canApplyGlobal(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Çubuklar şu an tüm uygulamalarda gizli mi (sağ panel penceresinin yeri buna göre). */
    fun hiddenEverywhere(context: Context): Boolean =
        Settings.Global.getString(context.contentResolver, KEY) == IMMERSIVE_ALL

    fun applyGlobal(context: Context, hide: Boolean) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id != 0) SidePanel.statusBarHeight = context.resources.getDimensionPixelSize(id)
        try {
            applyGlobalInner(context, hide)
        } finally {
            SidePanel.barsHiddenEverywhere = hiddenEverywhere(context)
        }
    }

    private fun applyGlobalInner(context: Context, hide: Boolean) {
        if (!canApplyGlobal(context)) return
        val cr = context.contentResolver
        val now = Settings.Global.getString(cr, KEY)
        val want = if (hide) IMMERSIVE_ALL else null
        // Pano'nun koymadığı bir değeri (başka bir uygulamanınki) kapatırken ezme.
        if (!hide && now != IMMERSIVE_ALL) return
        if (now == want) return
        runCatching { Settings.Global.putString(cr, KEY, want) }
            .onSuccess { Log.i(TAG, "sistem çubukları her yerde ${if (hide) "gizli" else "görünür"}") }
            .onFailure { Log.w(TAG, "ayar yazılamadı: $it") }
    }
}
