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
import app.pano.launcher.settings.MapContrast
import app.pano.launcher.settings.RouteColor
import app.pano.launcher.settings.Settings
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.SymbolLayer
import app.pano.launcher.ui.theme.linkColor
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano
import androidx.compose.runtime.collectAsState
import app.pano.launcher.navigation.NavPhase
import app.pano.launcher.navigation.PanoNav
import app.pano.launcher.navigation.Route
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnLocationCameraTransitionListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** OpenFreeMap stilleri: ücretsiz, API anahtarı gerekmez, veriler OpenStreetMap'ten. */
// Gündüz ve gece stilleri Ayarlar > Harita'dan seçiliyor (MapStyle, MapNightStyle).

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
    /** Kameranın en son hangi navigasyon aşamasına göre ayarlandığı (aynı aşamada tekrar ayarlanmasın). */
    internal var cameraPhase: String? = null
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

    val settings = LocalStyle.current
    val styleUrl = if (dark) settings.mapNight.url else settings.map.dayUrl
    val look = MapLook(dark, settings.mapContrast == MapContrast.High, settings.mapText.scale)
    // Stil ve görünüm ayarı birlikte anahtar: biri değişince stil yeniden yüklenip ayar uygulanır.
    val styleKey = "$styleUrl|$look"
    val routeColor = settings.routeColor
    LaunchedEffect(styleKey, showLocation, bottomInset) {
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
            if (holder.styleUrl == styleKey && holder.locationRequested == showLocation) {
                if (holder.locationOn) locationMap = map
                return@getMapAsync
            }
            holder.styleUrl = styleKey
            holder.locationRequested = showLocation
            holder.locationOn = false
            locationMap = null
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                applyLook(style, look)
                if (showLocation && enableLocation(context, map, style)) {
                    holder.locationOn = true
                    locationMap = map
                }
                // Stil değişince katmanlar silinir; rota varsa yeniden çiz.
                drawRoute(style, PanoNav.state.value?.route, Settings.state.value.routeColor)
                holder.cameraPhase = null
            }
        }
    }

    // Pano navigasyonu: rotayı çiz, önizlemede rotanın tamamını göster, yönlendirmede aracı izle.
    val nav by PanoNav.state.collectAsState()
    val route = nav?.route
    val phase = nav?.phase
    LaunchedEffect(route, phase, locationMap, routeColor) {
        mapView.getMapAsync { map ->
            val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
            drawRoute(style, route, routeColor)
            applyNavCamera(holder, map, mapView, route, phase, bottomInset)
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

private const val ROUTE_SOURCE = "pano-route"
private const val ROUTE_CASING = "pano-route-casing"
private const val ROUTE_LINE = "pano-route-line"
/** MapLibre konum katmanının en alttaki katmanı; rota onun altına çizilir ki araç oku üstte kalsın. */
private const val LOCATION_BOTTOM_LAYER = "mapbox-location-shadow-layer"

/** Rotayı haritaya çizer; [route] null ise siler. */
private fun drawRoute(style: Style, route: Route?, color: RouteColor) {
    if (route == null) {
        style.removeLayer(ROUTE_LINE)
        style.removeLayer(ROUTE_CASING)
        style.removeSource(ROUTE_SOURCE)
        return
    }
    val line = LineString.fromLngLats(route.lats.indices.map { Point.fromLngLat(route.lons[it], route.lats[it]) })
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
    if (source != null) {
        source.setGeoJson(line)
        style.getLayer(ROUTE_CASING)?.setProperties(PropertyFactory.lineColor(color.casing))
        style.getLayer(ROUTE_LINE)?.setProperties(PropertyFactory.lineColor(color.line))
        return
    }
    style.addSource(GeoJsonSource(ROUTE_SOURCE, line))
    val casing = LineLayer(ROUTE_CASING, ROUTE_SOURCE).withProperties(
        PropertyFactory.lineColor(color.casing),
        PropertyFactory.lineWidth(12f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
    val top = LineLayer(ROUTE_LINE, ROUTE_SOURCE).withProperties(
        PropertyFactory.lineColor(color.line),
        PropertyFactory.lineWidth(7f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
    if (style.getLayer(LOCATION_BOTTOM_LAYER) != null) {
        style.addLayerBelow(casing, LOCATION_BOTTOM_LAYER)
        style.addLayerAbove(top, ROUTE_CASING)
    } else {
        style.addLayer(casing)
        style.addLayer(top)
    }
}

/** Haritanın okunabilirlik ayarları (Ayarlar > Harita). */
private data class MapLook(val dark: Boolean, val highContrast: Boolean, val textScale: Float)

/** Rota dışındaki yol katmanları değil (raylar, iskeleler, düşük yakınlıktaki silik yollar). */
private fun isRoadLayer(id: String) =
    listOf("rail", "pier", "ferry", "aeroway", "subtle", "area").none { id.contains(it) }

private fun c(hex: String) = Expression.color(android.graphics.Color.parseColor(hex))

/**
 * Yüklenen stile okunabilirlik ayarlarını uygular. OpenFreeMap'in gece stili yolları neredeyse
 * arka planla aynı tonda çiziyor; yüksek kontrastta yollar sınıfına göre belirgin renk alır,
 * yazılar zıt renkli kalın kenarlıkla çizilir. Bir katman beklenmedik biçimdeyse olduğu gibi bırakılır.
 */
private fun applyLook(style: Style, look: MapLook) {
    if (!look.highContrast && look.textScale == 1f) return
    val d = look.dark
    for (layer in style.layers) runCatching {
        when (layer) {
            is LineLayer -> if (look.highContrast && layer.sourceLayer == "transportation" && isRoadLayer(layer.id)) {
                if (layer.id.contains("casing")) {
                    layer.setProperties(PropertyFactory.lineColor(if (d) "#3A3F47" else "#7D838C"), PropertyFactory.lineOpacity(1f))
                } else {
                    layer.setProperties(
                        PropertyFactory.lineColor(
                            Expression.match(
                                Expression.get("class"),
                                c(if (d) "#9AA0A8" else "#B9BEC6"),
                                Expression.stop("motorway", c(if (d) "#F0A23A" else "#E5821A")),
                                Expression.stop("trunk", c(if (d) "#E6CF78" else "#F2BE22")),
                                Expression.stop("primary", c(if (d) "#E6CF78" else "#F2BE22")),
                                Expression.stop("secondary", c(if (d) "#D2D6DC" else "#FFE68A")),
                                Expression.stop("tertiary", c(if (d) "#D2D6DC" else "#FFE68A")),
                                Expression.stop("path", c(if (d) "#6B7079" else "#C9CDD3")),
                            ),
                        ),
                        PropertyFactory.lineOpacity(1f),
                    )
                }
            }
            is SymbolLayer -> {
                if (look.textScale != 1f) scaleTextSize(layer, look.textScale)
                if (look.highContrast && !layer.id.contains("shield") && !layer.id.contains("way")) {
                    layer.setProperties(
                        PropertyFactory.textColor(if (d) "#F2F2F7" else "#111114"),
                        PropertyFactory.textHaloColor(if (d) "#000000" else "#FFFFFF"),
                        PropertyFactory.textHaloWidth(2f),
                        PropertyFactory.textHaloBlur(0f),
                    )
                }
            }
        }
    }
}

/**
 * Yazı boyutunu büyütür. Boyut çoğunlukla yakınlığa göre değişen bir ifade; yakınlık ifadesi
 * başka bir ifadenin içine konamadığı için çarpım yerine ifadedeki çıktı sayıları büyütülür.
 */
private fun scaleTextSize(layer: SymbolLayer, scale: Float) {
    val size = layer.textSize
    if (!size.isExpression) {
        layer.setProperties(PropertyFactory.textSize((size.value ?: 16f) * scale))
        return
    }
    val json = com.google.gson.JsonParser.parseString(size.expression.toString()).asJsonArray
    val op = json[0].asString
    val firstOutput = when (op) { "interpolate" -> 4; "step" -> 2; else -> return }
    var i = firstOutput
    while (i < json.size()) {
        val v = json[i]
        if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) json.set(i, com.google.gson.JsonPrimitive(v.asFloat * scale))
        i += 2 // [.., girdi, çıktı, girdi, çıktı ..]
    }
    layer.setProperties(PropertyFactory.textSize(Expression.raw(json.toString())))
}

/**
 * Kamera: önizlemede rotanın tamamı, yönlendirmede araç arkadan ve yakından (gidiş yönüne dönük),
 * navigasyon yokken her zamanki kuzey yukarı takip. Yalnızca aşama değişince ayarlanır.
 */
private fun applyNavCamera(holder: MapHolder, map: MapLibreMap, view: MapView, route: Route?, phase: NavPhase?, bottomInset: Float) {
    val key = "${phase}-${route?.hashCode()}"
    if (holder.cameraPhase == key) return
    holder.cameraPhase = key
    val location = if (holder.locationOn) map.locationComponent else null
    val h = view.height
    when {
        phase == NavPhase.Preview && route != null -> {
            location?.cameraMode = CameraMode.NONE
            location?.renderMode = RenderMode.NORMAL
            map.setPadding(0, 0, 0, (h * bottomInset).toInt())
            val bounds = LatLngBounds.Builder().apply { route.lats.indices.forEach { include(LatLng(route.lats[it], route.lons[it])) } }.build()
            val pad = (minOf(view.width, h) * 0.12f).toInt()
            map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad, (h * 0.22f).toInt(), pad, pad + (h * bottomInset).toInt()), 600)
        }
        phase == NavPhase.Active && route != null -> {
            // Araç ekranın alt üçte birinde, önü görünsün; üstte manevra kartı var.
            map.setPadding(0, (h * 0.45f).toInt(), 0, (h * bottomInset).toInt())
            location?.renderMode = RenderMode.GPS
            location?.track(CameraMode.TRACKING_GPS, zoom = 16.5, tilt = 45.0)
        }
        else -> {
            map.setPadding(0, 0, 0, (h * bottomInset).toInt())
            location?.renderMode = RenderMode.NORMAL
            location?.track(CameraMode.TRACKING, zoom = 13.0, tilt = 0.0)
            if (location == null) map.easeCamera(CameraUpdateFactory.tiltTo(0.0))
        }
    }
}

/** Kamera modunu değiştirir; yakınlaştırma ve eğim geçiş bitince uygulanır (geçiş sırasında yok sayılıyor). */
private fun LocationComponent.track(mode: Int, zoom: Double, tilt: Double) {
    setCameraMode(mode, object : OnLocationCameraTransitionListener {
        override fun onLocationCameraTransitionFinished(cameraMode: Int) {
            zoomWhileTracking(zoom, 800)
            tiltWhileTracking(tilt, 800)
        }
        override fun onLocationCameraTransitionCanceled(cameraMode: Int) {}
    })
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
