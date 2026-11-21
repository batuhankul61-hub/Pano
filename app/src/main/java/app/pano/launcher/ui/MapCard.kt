package app.pano.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
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
        Box(Modifier.fillMaxSize().clickable(role = Role.Button) { Launch.maps(context) })

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
                    .clickable(role = Role.Button) { Launch.maps(context) }.padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Başla", color = colors.inverseText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** MapLibre haritasını Compose içine yerleştirir ve ekran yaşam döngüsüne bağlar. */
@Composable
internal fun LiveMap(modifier: Modifier, dark: Boolean, showLocation: Boolean, bottomInset: Float = 0.33f) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

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

    // Harita hazır olup konum katmanı açıldığında dolar; konum güncellemeleri buraya iletilir.
    var locationMap by remember { mutableStateOf<MapLibreMap?>(null) }

    val dayStyle = LocalStyle.current.map.dayUrl
    LaunchedEffect(mapView, dark, showLocation, dayStyle) {
        locationMap = null
        mapView.getMapAsync { map ->
            map.uiSettings.apply {
                setAllGesturesEnabled(false)
                isLogoEnabled = false
                isCompassEnabled = false
                // Atıf yazısını kartın köşesinde kendimiz gösteriyoruz (MapCard).
                isAttributionEnabled = false
            }
            // Alttaki "Navigasyon" kartı haritanın üçte birini kapatıyor; merkezi görünen kısma kaydır.
            map.setPadding(0, 0, 0, (mapView.height * bottomInset).toInt())
            if (map.cameraPosition.zoom < 3) {
                map.cameraPosition = CameraPosition.Builder().target(FALLBACK).zoom(13.0).build()
            }
            map.setStyle(Style.Builder().fromUri(if (dark) STYLE_NIGHT else dayStyle)) { style ->
                if (showLocation && enableLocation(context, map, style)) locationMap = map
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

    AndroidView(factory = { mapView }, modifier = modifier)
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
