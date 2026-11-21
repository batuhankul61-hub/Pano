package app.pano.launcher.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.Launch
import app.pano.launcher.settings.FavoriteApp
import app.pano.launcher.settings.FavoritesStore
import app.pano.launcher.ui.theme.LocalPano
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val label: String, val packageName: String, val className: String)

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map {
            AppEntry(
                label = it.loadLabel(pm).toString(),
                packageName = it.activityInfo.packageName,
                className = it.activityInfo.name,
            )
        }
        .sortedBy { it.label.lowercase() }
}

/** Yüklü tüm uygulamalar. Dokununca açılır, uzun basınca favorilere eklenir ya da çıkarılır. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalPano.current
    val haptics = LocalHapticFeedback.current
    FavoritesStore.load(context)
    val favorites by FavoritesStore.state
    val apps by produceState(emptyList<AppEntry>()) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        ScreenHeader("Uygulamalar", onBack, subtitle = if (apps.isEmpty()) null else "${apps.size} uygulama · favori için uzun bas")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(32.dp)),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(apps, key = { it.packageName + "/" + it.className }) { app ->
                val favorite = favorites.any { it.packageName == app.packageName && it.className == app.className }
                Column(
                    Modifier.clip(RoundedCornerShape(20.dp))
                        .combinedClickable(
                            role = Role.Button,
                            onLongClickLabel = if (favorite) "Favorilerden çıkar" else "Favorilere ekle",
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val msg = when (FavoritesStore.toggle(FavoriteApp(app.packageName, app.className, app.label))) {
                                    FavoritesStore.Result.Added -> "${app.label} favorilere eklendi"
                                    FavoritesStore.Result.Removed -> "${app.label} favorilerden çıkarıldı"
                                    FavoritesStore.Result.Full -> "En fazla ${FavoritesStore.MAX} favori eklenebilir"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            },
                        ) { Launch.app(context, app.packageName, app.className) }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(app.packageName, app.className, 80.dp, favorite = favorite)
                    Text(
                        app.label, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp, end = 8.dp),
                    )
                }
            }
        }
    }
}
