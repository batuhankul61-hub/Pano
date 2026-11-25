package app.pano.launcher.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import java.text.Normalizer
import java.util.Locale
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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

/** Aramada büyük/küçük harf ve Türkçe karakter farkını yok sayar (ı/i, ş/s, ğ/g, ü/u, ö/o, ç/c). */
private fun normalizeForSearch(s: String): String =
    Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('ı', 'i')

/** Büyük arama alanı (64 dp). Renkler tema metninden türetilir; hem düz hem cam zeminde okunur. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val colors = LocalPano.current
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(colors.text.copy(alpha = if (colors.isDark) 0.12f else 0.08f))
            .padding(start = 22.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PanoIcons.Search, null, tint = colors.secondary, modifier = Modifier.size(28.dp))
        Box(Modifier.weight(1f).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Uygulama ara", color = colors.secondary, fontSize = 22.sp)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = colors.text, fontSize = 22.sp),
                cursorBrush = SolidColor(colors.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Aramayı temizle") { onChange("") },
                contentAlignment = Alignment.Center,
            ) { Icon(PanoIcons.Close, "Temizle", tint = colors.secondary, modifier = Modifier.size(26.dp)) }
        }
    }
}

/**
 * Yüklü tüm uygulamalar. Dokununca açılır, uzun basınca favorilere eklenir ya da çıkarılır.
 * Favoriler en üstte kendi başlıklarıyla, favori sırasıyla durur; arama yaparken liste tek parçadır.
 */
@Composable
fun AppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalPano.current
    FavoritesStore.load(context)
    val favorites by FavoritesStore.state
    val apps by produceState(emptyList<AppEntry>()) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }

    var query by rememberSaveable { mutableStateOf("") }
    val indexed = remember(apps) { apps.map { it to normalizeForSearch(it.label) } }
    val shown = remember(indexed, query) {
        val q = normalizeForSearch(query.trim())
        if (q.isEmpty()) apps else indexed.filter { q in it.second }.map { it.first }
    }
    val searching = query.isNotBlank()
    // Favoriler kaydedildikleri sırayla; kaldırılmış uygulamalar atlanır.
    val favoriteApps = remember(apps, favorites) {
        favorites.mapNotNull { f -> apps.firstOrNull { it.packageName == f.packageName && it.className == f.className } }
    }
    val rest = remember(apps, favoriteApps) { apps - favoriteApps.toSet() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        ScreenHeader(
            "Uygulamalar", onBack,
            subtitle = if (apps.isEmpty()) null
            else if (!searching) "${apps.size} uygulama · favori için uzun bas"
            else "${shown.size} / ${apps.size} uygulama",
        )
        SearchField(query, { query = it })
        if (apps.isNotEmpty() && shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("“${query.trim()}” için uygulama bulunamadı", color = colors.secondary, fontSize = 20.sp, textAlign = TextAlign.Center)
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(32.dp)),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Sabit ilk öğe: liste en üstteyken favori eklenince kaydırma ona tutunur, yeni Favoriler
            // bölümü görünür kalır (yoksa ilk uygulamaya tutunup aşağı kayıyordu).
            item(key = "ust", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().height(1.dp)) }
            if (searching || favoriteApps.isEmpty()) {
                items(shown, key = { it.packageName + "/" + it.className }) { app -> AppCell(app, favorites) }
            } else {
                item(key = "baslik-favoriler", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Favoriler") }
                items(favoriteApps, key = { "fav/" + it.packageName + "/" + it.className }) { app -> AppCell(app, favorites) }
                item(key = "baslik-tumu", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle("Tüm uygulamalar", Modifier.padding(top = 12.dp))
                }
                items(rest, key = { it.packageName + "/" + it.className }) { app -> AppCell(app, favorites) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val colors = LocalPano.current
    Text(
        text, color = colors.secondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.fillMaxWidth().padding(start = 12.dp),
    )
}

/** Tek uygulama: dokununca açılır, uzun basınca favorilere eklenir / çıkarılır. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(app: AppEntry, favorites: List<FavoriteApp>) {
    val context = LocalContext.current
    val colors = LocalPano.current
    val haptics = LocalHapticFeedback.current
    val favorite = favorites.any { it.packageName == app.packageName && it.className == app.className }
    val source = rememberLaunchSource()
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
            ) { Launch.app(context, app.packageName, app.className, source.anchor()) }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Uygulama tam bu ikonun yerinden büyüyerek açılır.
        AppIcon(app.packageName, app.className, 80.dp, Modifier.launchSource(source), favorite = favorite)
        Text(
            app.label, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp, end = 8.dp),
        )
    }
}
