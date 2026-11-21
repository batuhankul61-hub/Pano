package app.pano.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.pano.launcher.Launch
import app.pano.launcher.settings.LocalStyle
import app.pano.launcher.ui.theme.linkColor
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** OpenFreeMap stilleri: ücretsiz, API anahtarı gerekmez, veriler OpenStreetMap'ten. */
// Gündüz stili Ayarlar > Stil > Gündüz haritası'ndan seçiliyor (MapStyle).
private const val STYLE_NIGHT = "https://tiles.openfreemap.org/styles/dark"

/** Konum henüz bilinmiyorsa gösterilecek yer. */
private val FALLBACK = LatLng(41.0082, 28.9784)

internal fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
fun MapCard(modifier: Modifier) {
    val context = LocalContext.current
    val colors = LocalPano.current
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationGranted = hasLocationPermission(context)
    }

    Box(
        modifier.surface(RoundedCornerShape(30.dp), if (colors.isDark) Color(0xFF16181C) else Color(0xFFE8E8ED), colors.isDark, colors.hairline),
    ) {
        LiveMap(Modifier.fillMaxSize(), dark = colors.isDark, showLocation = locationGranted)

        // Harita kartı sadece önizleme; dokununca tam navigasyon uygulaması açılır.
        // Navigasyon uygulaması kartın kendisinden büyüyerek açılır.
        val mapSrc = rememberLaunchSource()
        Box(Modifier.fillMaxSize().launchSource(mapSrc).clickable(role = Role.Button) { Launch.maps(context, mapSrc.anchor()) })

        // OpenStreetMap lisansı gereği harita verisinin kaynağı görünür olmalı.
        Text(
            "© OpenFreeMap © OpenMapTiles © OpenStreetMap",
            color = colors.secondary, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 14.dp),
        )

        if (!locationGranted) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(16.dp).height(44.dp).clip(RoundedCornerShape(22.dp))
                    .background(colors.glass)
                    .clickable(role = Role.Button) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PanoIcons.Location, contentDescription = null, tint = linkColor(), modifier = Modifier.size(20.dp))
                Text("Konumumu göster", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().height(76.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.glass)
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(colors.raised), contentAlignment = Alignment.Center) {
                Icon(PanoIcons.Navigation, contentDescription = null, tint = colors.text, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Navigasyon", color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (locationGranted) "Rota için dokun" else "Konum izni verilmedi",
                    color = colors.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
            }
            Box(
                Modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(colors.text)
                    .clickable(role = Role.Button) { Launch.maps(context, mapSrc.anchor()) }.padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Başla", color = colors.inverseText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Uygulama boyunca yaşayan tek harita. Eskiden ana ekrandan her çıkışta harita yok edilip dönüşte
 * stil ve karolar baştan yükleniyordu; bu ekran geçişlerinde 100+ ms takılmaya yol açıyordu.
 * Artık harita bir kez oluşturuluyor, ekranlar arasında yalnızca yerinden alınıp geri takılıyor.
 */
class MapHolder internal constructor(val view: MapView) {
    internal var map: MapLibreMap? = null
    internal var styleUrl: String? = null
    internal var locationRequested = false
    internal var locationOn = false
}

private val LocalMapHolder = staticCompositionLocalOf<MapHolder?> { null }

/** Haritayı activity ömrü boyunca tutar; PanoApp tüm ekranları bununla sarar. */
@Composable
fun ProvideMapHolder(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember {
        MapLibre.getInstance(context)
        MapHolder(MapView(context).apply { onCreate(null) })
    }
    val mapView = holder.view

    DisposableEffect(lifecycleOwner, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    CompositionLocalProvider(LocalMapHolder provides holder, content = content)
}

/** Paylaşılan haritayı bu karta yerleştirir. Stil ve konum katmanı yalnızca gerçekten değişince yeniden kurulur. */
@Composable
internal fun LiveMap(modifier: Modifier, dark: Boolean, showLocation: Boolean, bottomInset: Float = 0.33f) {
    val context = LocalContext.current
    val holder = checkNotNull(LocalMapHolder.current) { "LiveMap, ProvideMapHolder içinde kullanılmalı" }
    val mapView = holder.view

    // Harita hazır olup konum katmanı açıldığında dolar; konum güncellemeleri buraya iletilir.
    var locationMap by remember { mutableStateOf(if (holder.locationOn) holder.map else null) }

    val styleUrl = if (dark) STYLE_NIGHT else LocalStyle.current.map.dayUrl
    LaunchedEffect(styleUrl, showLocation, bottomInset) {
        mapView.getMapAsync { map ->
            holder.map = map
            map.uiSettings.apply {
                setAllGesturesEnabled(false)
                isLogoEnabled = false
                isCompassEnabled = false
                // Atıf yazısını kartın köşesinde kendimiz gösteriyoruz (MapCard).
                isAttributionEnabled = false
            }
            // Kartın altındaki panel haritanın bir kısmını kapatıyor; merkezi görünen kısma kaydır.
            // Yeniden takılan harita henüz ölçülmemiş olabilir, bu yüzden yerleşimden sonra ayarlanıyor.
            mapView.post {
                map.setPadding(0, 0, 0, (mapView.height * bottomInset).toInt())
                map.triggerRepaint() // yeniden takılan yüzey boş kalmasın
            }
            if (map.cameraPosition.zoom < 3) {
                map.cameraPosition = CameraPosition.Builder().target(FALLBACK).zoom(13.0).build()
            }
            if (holder.styleUrl == styleUrl && holder.locationRequested == showLocation) {
                if (holder.locationOn) locationMap = map
                return@getMapAsync
            }
            holder.styleUrl = styleUrl
            holder.locationRequested = showLocation
            holder.locationOn = false
            locationMap = null
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                if (showLocation && enableLocation(context, map, style)) {
                    holder.locationOn = true
                    locationMap = map
                }
            }
        }
    }

    // Konumu doğrudan Android'in GPS ve ağ kaynaklarından okuyoruz. Google servislerine
    // ihtiyaç duymadığı için Google Play olmayan araç ekranlarında da çalışır.
    val currentMap = locationMap
    DisposableEffect(currentMap) {
        if (currentMap == null) return@DisposableEffect onDispose {}
        val stop = startLocationUpdates(context) { location ->
            currentMap.locationComponent.forceLocationUpdate(location)
        }
        onDispose { stop() }
    }

    // Önceki karttan kalan üst görünümden ayırıp buraya tak.
    AndroidView(factory = { mapView.also { (it.parent as? ViewGroup)?.removeView(it) } }, modifier = modifier)
}

@SuppressLint("MissingPermission")
private fun enableLocation(context: Context, map: MapLibreMap, style: Style): Boolean {
    if (!hasLocationPermission(context)) return false
    return try {
        map.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(false)
                    .build(),
            )
            isLocationComponentEnabled = true
            cameraMode = CameraMode.TRACKING
            renderMode = RenderMode.NORMAL
        }
        true
    } catch (_: SecurityException) {
        false // İzin geri alındıysa harita konumsuz devam eder.
    }
}

/** GPS ve ağ konumunu dinler; dinlemeyi durduran bir fonksiyon döndürür. */
@SuppressLint("MissingPermission")
private fun startLocationUpdates(context: Context, onLocation: (Location) -> Unit): () -> Unit {
    val manager = context.getSystemService(LocationManager::class.java) ?: return {}
    if (!hasLocationPermission(context)) return {}
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    // Hemen bir şey göstermek için en taze bilinen konumla başla.
    providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let(onLocation)

    val listener = LocationListener { onLocation(it) }
    providers.forEach { provider ->
        try {
            manager.requestLocationUpdates(provider, 1_000L, 5f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }
    return { manager.removeUpdates(listener) }
}
