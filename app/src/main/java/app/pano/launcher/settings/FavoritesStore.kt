package app.pano.launcher.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/** Favori uygulama: hangi activity açılacağı ve şeritte erişilebilirlik için adı. */
data class FavoriteApp(val packageName: String, val className: String, val label: String) {
    val key: String get() = "$packageName/$className"
}

/**
 * Ana panodaki favori şeridinin içeriği. Sıra korunur, en fazla [MAX] uygulama tutulur.
 * Cihazda saklanır; uygulama yeniden açıldığında aynı favoriler geri gelir.
 */
object FavoritesStore {
    const val MAX = 6

    private const val PREFS = "pano_favorites"
    private const val KEY = "apps"
    private var prefs: SharedPreferences? = null
    private val _state = mutableStateOf(emptyList<FavoriteApp>())
    val state: State<List<FavoriteApp>> = _state

    enum class Result { Added, Removed, Full }

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        // Her satır: paket \t activity \t ad
        _state.value = p.getString(KEY, null).orEmpty()
            .lines()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) null
                else FavoriteApp(parts[0], parts[1], parts.getOrElse(2) { parts[0] })
            }
            .distinctBy { it.key }
            .take(MAX)
    }

    fun isFavorite(packageName: String, className: String): Boolean =
        _state.value.any { it.packageName == packageName && it.className == className }

    /** Favoriyse çıkarır, değilse sona ekler. Liste doluysa hiçbir şey değişmez. */
    fun toggle(app: FavoriteApp): Result {
        val list = _state.value
        val result = when {
            list.any { it.key == app.key } -> { save(list.filterNot { it.key == app.key }); Result.Removed }
            list.size >= MAX -> Result.Full
            else -> { save(list + app); Result.Added }
        }
        return result
    }

    private fun save(list: List<FavoriteApp>) {
        _state.value = list
        prefs?.edit()
            ?.putString(KEY, list.joinToString("\n") { "${it.packageName}\t${it.className}\t${it.label.replace('\n', ' ').replace('\t', ' ')}" })
            ?.apply()
    }
}
