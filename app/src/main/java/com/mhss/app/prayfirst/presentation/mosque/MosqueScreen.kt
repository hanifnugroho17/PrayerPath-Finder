package com.mhss.app.prayfirst.presentation.mosque

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.mhss.app.prayfirst.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONException
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.IOException
import java.net.URLEncoder
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextAlign
import androidx.core.graphics.drawable.DrawableCompat

data class MosqueScreenState(
    val errorMessage: String? = null,
    val currentLocation: GeoPoint? = null,
    val isLoadingLocation: Boolean = false,
    val isTrackingLocation: Boolean = false,
    val currentZoom: Double = 15.0,
    val isMapReady: Boolean = false,
    val userMarker: Marker? = null
)

// Constants
private object MapConstants {
    const val MARKER_SIZE_DP = 24
    const val LOCATION_UPDATE_INTERVAL = 2000L
    const val NETWORK_UPDATE_INTERVAL = 5000L
    const val GPS_MIN_DISTANCE = 5f
    const val NETWORK_MIN_DISTANCE = 10f
    const val MOSQUE_SEARCH_RADIUS = 1000

    // Default location (Yogyakarta)
    val DEFAULT_LOCATION = GeoPoint(-7.7956, 110.3695)
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MosqueScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize OSMDroid configuration
    InitializeOSMDroid(context)

    // Location permissions
    val locationPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    // Check permissions first
    if (!locationPermissions.allPermissionsGranted) {
        PermissionRequestScreen(
            onRequestPermissions = { locationPermissions.launchMultiplePermissionRequest() }
        )
        return
    }

    // Main screen state
    var screenState by remember { mutableStateOf(MosqueScreenState()) }

    // Initialize MapView
    val mapView = remember { createMapView(context, screenState.currentZoom) }

    // Location manager and listener
    val locationManager = remember {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    var locationListener by remember { mutableStateOf<LocationListener?>(null) }

    // Lifecycle management
    MapLifecycleEffect(
        lifecycleOwner = lifecycleOwner,
        mapView = mapView,
        locationManager = locationManager,
        locationListener = locationListener
    )

    // Main UI
    Box(modifier = Modifier.fillMaxSize()) {
        // Map view
        MapViewComposable(
            mapView = mapView,
            onMapReady = { screenState = screenState.copy(isMapReady = true) },
        )

        // Location tracking button
        LocationTrackingButton(
            modifier = Modifier.align(Alignment.BottomEnd),
            isTracking = screenState.isTrackingLocation,
            isLoading = screenState.isLoadingLocation,
            onToggleTracking = { shouldTrack ->
                if (shouldTrack) {
                    startLocationTracking(
                        context = context,
                        locationManager = locationManager,
                        mapView = mapView,
                        screenState = screenState,
                        onStateUpdate = { newState -> screenState = newState },
                        onListenerCreated = { listener -> locationListener = listener }
                    )
                } else {
                    stopLocationTracking(
                        locationManager = locationManager,
                        locationListener = locationListener,
                        onStateUpdate = { newState -> screenState = newState }
                    )
                }
            }
        )

        Column(
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
//            Location info
            screenState.currentLocation?.let { location ->
                LocationInfoCard(
                    location = location,
                    isTracking = screenState.isTrackingLocation
                )
            }

            screenState.errorMessage?.let { message ->
                InfoCard(
//                    modifier = Modifier.align(Alignment.TopCenter),
                    message = message,
                    isError = message.contains("Error") || message.contains("Gagal")
                )
            }
        }
    }

    // Load mosques when map is ready
    LaunchedEffect(screenState.isMapReady, screenState.currentLocation) {
        if (screenState.isMapReady) {
            val locationToUse = screenState.currentLocation ?: MapConstants.DEFAULT_LOCATION

            if (screenState.currentLocation == null) {
                mapView.controller.setCenter(locationToUse)
                mapView.controller.setZoom(screenState.currentZoom)
                mapView.invalidate()
            }

            kotlinx.coroutines.delay(500) // Allow map to render

            loadNearbyMosques(
                location = locationToUse,
                mapView = mapView,
                context = context,
                userMarker = screenState.userMarker,
                onResult = { mosqueCount, error ->
                    screenState = screenState.copy(
                        errorMessage = error ?: if (mosqueCount == 0) {
                            "Tidak ada masjid ditemukan dalam radius 1km."
                        } else {
                            "Ditemukan $mosqueCount masjid di sekitar Anda."
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun InitializeOSMDroid(context: Context) {
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
    }
}

@Composable
private fun PermissionRequestScreen(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Aplikasi membutuhkan izin lokasi untuk menemukan masjid terdekat.",
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRequestPermissions) {
            Text("Minta Izin Lokasi")
        }
    }
}

@Composable
private fun MapViewComposable(
    mapView: MapView,
    onMapReady: () -> Unit
) {
    AndroidView(
        factory = {
            Log.d("MosqueScreen", "AndroidView factory: returning MapView")
            mapView.also {
                onMapReady()
                it.onResume()
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            Log.d("MosqueScreen", "AndroidView update called")
            view.invalidate()
        }
    )
}

@Composable
private fun LocationTrackingButton(
    modifier: Modifier = Modifier,
    isTracking: Boolean,
    isLoading: Boolean,
    onToggleTracking: (Boolean) -> Unit
) {
    FloatingActionButton(
        onClick = { onToggleTracking(!isTracking) },
        modifier = modifier.padding(16.dp),
        containerColor = if (isTracking) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = if (isTracking) "Stop Tracking" else "Start Tracking",
                modifier = Modifier.size(24.dp),
                tint = if (isTracking) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun InfoCard(
    modifier: Modifier = Modifier,
    message: String,
    isError: Boolean
) {
    Card(
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Text(
            text = "Info: $message",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun LocationInfoCard(
    modifier: Modifier = Modifier,
    location: GeoPoint,
    isTracking: Boolean
) {
    Card(
        modifier = modifier.padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "Lokasi: ${String.format("%.4f", location.latitude)}, ${
                    String.format(
                        "%.4f",
                        location.longitude
                    )
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
            if (isTracking) {
                Text(
                    text = "• Real-time tracking aktif",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun MapLifecycleEffect(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    mapView: MapView,
    locationManager: LocationManager,
    locationListener: LocationListener?
) {
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d("MosqueScreen", "MapView onResume")
                    mapView.onResume()
                }

                Lifecycle.Event.ON_PAUSE -> {
                    Log.d("MosqueScreen", "MapView onPause")
                    mapView.onPause()
                }

                Lifecycle.Event.ON_DESTROY -> {
                    Log.d("MosqueScreen", "MapView onDetach")
                    mapView.onDetach()
                    cleanupLocationListener(locationManager, locationListener)
                }

                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
            cleanupLocationListener(locationManager, locationListener)
        }
    }
}

// Helper functions
private fun createMapView(context: Context, zoom: Double): MapView {
    Log.d("MosqueScreen", "Creating MapView instance")
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(zoom)
        minimumHeight = 100
        minimumWidth = 100
    }
}

private fun cleanupLocationListener(
    locationManager: LocationManager,
    locationListener: LocationListener?
) {
    locationListener?.let { listener ->
        try {
            locationManager.removeUpdates(listener)
        } catch (e: SecurityException) {
            Log.e("MosqueScreen", "Error removing location updates", e)
        }
    }
}

private fun startLocationTracking(
    context: Context,
    locationManager: LocationManager,
    mapView: MapView,
    screenState: MosqueScreenState,
    onStateUpdate: (MosqueScreenState) -> Unit,
    onListenerCreated: (LocationListener) -> Unit
) {
    onStateUpdate(screenState.copy(isLoadingLocation = true))

    LocationTrackingManager.startTracking(
        context = context,
        locationManager = locationManager,
        onLocationReceived = { location ->
            val newUserMarker = updateUserMarker(mapView, location, context, screenState.userMarker)
            onStateUpdate(
                screenState.copy(
                    currentLocation = location,
                    isLoadingLocation = false,
                    userMarker = newUserMarker
                )
            )
            mapView.controller.setCenter(location)
            mapView.invalidate()
        },
        onError = { error ->
            onStateUpdate(
                screenState.copy(
                    errorMessage = error,
                    isLoadingLocation = false,
                    isTrackingLocation = false
                )
            )
        },
        onListenerCreated = { listener ->
            onListenerCreated(listener)
            onStateUpdate(screenState.copy(isTrackingLocation = true))
        }
    )
}

private fun stopLocationTracking(
    locationManager: LocationManager,
    locationListener: LocationListener?,
    onStateUpdate: (MosqueScreenState) -> Unit
) {
    locationListener?.let { listener ->
        try {
            locationManager.removeUpdates(listener)
            onStateUpdate(MosqueScreenState(isTrackingLocation = false, isLoadingLocation = false))
            Log.d("MosqueScreen", "Stopped location tracking")
        } catch (e: SecurityException) {
            onStateUpdate(MosqueScreenState(errorMessage = "Gagal menghentikan tracking lokasi"))
        }
    }
}

private fun updateUserMarker(
    mapView: MapView,
    location: GeoPoint,
    context: Context,
    currentUserMarker: Marker?
): Marker {
    return currentUserMarker?.apply {
        position = location
    } ?: createUserMarker(mapView, location, context).also { newMarker ->
        currentUserMarker?.let { oldMarker ->
            mapView.overlays.remove(oldMarker)
        }
        mapView.overlays.add(0, newMarker)
    }
}

private fun createUserMarker(mapView: MapView, location: GeoPoint, context: Context): Marker {
    return Marker(mapView).apply {
        position = location
        title = "Lokasi Anda"
        snippet = "Posisi saat ini"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        try {
            val userDrawable = context.getDrawable(android.R.drawable.ic_menu_mylocation)
            userDrawable?.let { drawable ->
                val blueColor = android.graphics.Color.BLUE
                val mutableDrawable = DrawableCompat.wrap(drawable.mutate())
                DrawableCompat.setTint(mutableDrawable, blueColor)

                val sizeInPixels =
                    (MapConstants.MARKER_SIZE_DP * context.resources.displayMetrics.density).toInt()

                val resizedDrawable = drawable.mutate()
                resizedDrawable.setBounds(0, 0, sizeInPixels, sizeInPixels)
                icon = resizedDrawable

                Log.d("MarkerDebug", "User marker size set to: ${sizeInPixels}px")
            }
        } catch (e: Exception) {
            Log.w("MosqueScreen", "Could not load user location icon", e)
        }
    }
}

private fun loadNearbyMosques(
    location: GeoPoint,
    mapView: MapView,
    context: Context,
    userMarker: Marker?,
    onResult: (mosqueCount: Int, error: String?) -> Unit
) {
    MosqueSearchManager.searchNearby(
        location = location,
        radius = MapConstants.MOSQUE_SEARCH_RADIUS,
        onSuccess = { mosques ->
            Log.d("MosqueScreen", "Mosques found: ${mosques.size}")

            // Clear all markers except user marker
            val overlaysToKeep = mutableListOf<org.osmdroid.views.overlay.Overlay>()
            userMarker?.let { overlaysToKeep.add(it) }

            mapView.overlays.clear()
            mapView.overlays.addAll(overlaysToKeep)

            // Add mosque markers
            mosques.forEach { mosqueLocation ->
                val marker = createMosqueMarker(mapView, mosqueLocation, location, context)
                mapView.overlays.add(marker)
            }

            mapView.invalidate()
            onResult(mosques.size, null)
        },
        onError = { error ->
            Log.e("MosqueScreen", "Error fetching mosques: $error")
            onResult(0, error)
        }
    )
}

private fun createMosqueMarker(
    mapView: MapView,
    mosqueLocation: GeoPoint,
    userLocation: GeoPoint,
    context: Context
): Marker {
    return Marker(mapView).apply {
        position = mosqueLocation
        title = "Masjid"

        val distance = userLocation.distanceToAsDouble(mosqueLocation)
        snippet = "Jarak: ${String.format("%.0f", distance)} meter"

        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        try {

            val originalDrawable = ContextCompat.getDrawable(context, R.drawable.mosquee_fill)

            if (originalDrawable != null) {
                val iconSizePx =
                    (MapConstants.MARKER_SIZE_DP * context.resources.displayMetrics.density).toInt()
                val finalSizePx = if (iconSizePx <= 0) 1 else iconSizePx
                val bitmap = Bitmap.createBitmap(finalSizePx, finalSizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                originalDrawable.setBounds(0, 0, canvas.width, canvas.height)
                originalDrawable.draw(canvas)
                icon = BitmapDrawable(context.resources, bitmap)

                Log.d(
                    "MarkerDebug",
                    "Mosque marker size set to: ${finalSizePx}px (target ${MapConstants.MARKER_SIZE_DP}dp)"
                )
            } else {
                Log.w(
                    "MosqueScreen",
                    "R.drawable.mosquee_fill is null, using default OSMDroid icon."
                )
                setIcon(ContextCompat.getDrawable(context, android.R.drawable.ic_menu_mapmode))
            }
        } catch (e: Exception) {
            Log.w(
                "MosqueScreen",
                "Could not load or resize mosque_fill icon, using default. Error: ${e.message}",
                e
            )
            try {
                setIcon(ContextCompat.getDrawable(context, android.R.drawable.ic_menu_mapmode))
            } catch (ex: Exception) {
                Log.e("MarkerDebug", "Failed to set even fallback system icon", ex)
            }
        }
    }
}


object LocationTrackingManager {
    @SuppressLint("MissingPermission")
    fun startTracking(
        context: Context,
        locationManager: LocationManager,
        onLocationReceived: (GeoPoint) -> Unit,
        onError: (String) -> Unit,
        onListenerCreated: (LocationListener) -> Unit
    ) {
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            ) {
                onError("GPS dan Network Provider tidak aktif. Silakan aktifkan lokasi.")
                return
            }

            // Try to get last known location first
            val lastKnownLocation =
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            lastKnownLocation?.let { location ->
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                onLocationReceived(geoPoint)
                Log.d("LocationTracking", "Using last known location: $geoPoint")
            }

            val locationListener = createLocationListener(onLocationReceived, onError)

            // Request updates
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MapConstants.LOCATION_UPDATE_INTERVAL,
                    MapConstants.GPS_MIN_DISTANCE,
                    locationListener
                )
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MapConstants.NETWORK_UPDATE_INTERVAL,
                    MapConstants.NETWORK_MIN_DISTANCE,
                    locationListener
                )
            }

            onListenerCreated(locationListener)
            Log.d("LocationTracking", "Real-time location tracking started")

        } catch (e: SecurityException) {
            onError("Izin lokasi tidak diberikan")
        } catch (e: Exception) {
            onError("Error memulai tracking lokasi: ${e.localizedMessage}")
        }
    }

    private fun createLocationListener(
        onLocationReceived: (GeoPoint) -> Unit,
        onError: (String) -> Unit
    ): LocationListener {
        return object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                onLocationReceived(geoPoint)
                Log.d(
                    "LocationTracking",
                    "Location updated: $geoPoint, Accuracy: ${location.accuracy}m"
                )
            }

            override fun onProviderEnabled(provider: String) {
                Log.d("LocationTracking", "Provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.d("LocationTracking", "Provider disabled: $provider")
                if (provider == LocationManager.GPS_PROVIDER) {
                    onError("GPS dinonaktifkan. Menggunakan Network Provider.")
                }
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                Log.d("LocationTracking", "Provider status changed: $provider, Status: $status")
            }
        }
    }
}

object MosqueSearchManager {
    fun searchNearby(
        location: GeoPoint,
        radius: Int,
        onSuccess: (List<GeoPoint>) -> Unit,
        onError: (String) -> Unit
    ) {
        val query = """
            [out:json][timeout:25];
            (
              node["amenity"="place_of_worship"]["religion"="muslim"](around:$radius,${location.latitude},${location.longitude});
              way["amenity"="place_of_worship"]["religion"="muslim"](around:$radius,${location.latitude},${location.longitude});
              relation["amenity"="place_of_worship"]["religion"="muslim"](around:$radius,${location.latitude},${location.longitude});
            );
            out center;
        """.trimIndent()

        val encodedQuery = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (e: Exception) {
            Log.e("MosqueSearch", "URL Encoding failed", e)
            onError("Gagal membuat query: ${e.localizedMessage}")
            return
        }

        val url = "https://overpass-api.de/api/interpreter?data=$encodedQuery"
        Log.d("MosqueSearch", "Overpass URL: $url")

        performSearch(url, onSuccess, onError)
    }

    private fun performSearch(
        url: String,
        onSuccess: (List<GeoPoint>) -> Unit,
        onError: (String) -> Unit
    ) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "PrayFirst/1.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("MosqueSearch", "OkHttp onFailure", e)
                CoroutineScope(Dispatchers.Main).launch {
                    onError("Gagal terhubung ke server: ${e.localizedMessage}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.e("MosqueSearch", "OkHttp onResponse unsuccessful: ${response.code}")
                        CoroutineScope(Dispatchers.Main).launch {
                            onError("Error dari server: ${response.code} ${response.message}")
                        }
                        return
                    }

                    val jsonStr = response.body?.string()
                    if (jsonStr.isNullOrEmpty()) {
                        CoroutineScope(Dispatchers.Main).launch {
                            onError("Tidak ada data diterima dari server.")
                        }
                        return
                    }

                    try {
                        val mosques = parseMosqueData(jsonStr)
                        CoroutineScope(Dispatchers.Main).launch {
                            onSuccess(mosques)
                        }
                    } catch (e: JSONException) {
                        Log.e("MosqueSearch", "JSON Parsing error", e)
                        CoroutineScope(Dispatchers.Main).launch {
                            onError("Gagal memproses data masjid: ${e.localizedMessage}")
                        }
                    } catch (e: Exception) {
                        Log.e("MosqueSearch", "General error in onResponse", e)
                        CoroutineScope(Dispatchers.Main).launch {
                            onError("Terjadi kesalahan: ${e.localizedMessage}")
                        }
                    }
                }
            }
        })
    }

    private fun parseMosqueData(json: String): List<GeoPoint> {
        val mosques = mutableListOf<GeoPoint>()
        val root = JSONObject(json)
        val elements = root.getJSONArray("elements")

        for (i in 0 until elements.length()) {
            val elem = elements.getJSONObject(i)
            val type = elem.getString("type")

            when (type) {
                "node" -> {
                    if (elem.has("lat") && elem.has("lon")) {
                        val lat = elem.getDouble("lat")
                        val lon = elem.getDouble("lon")
                        mosques.add(GeoPoint(lat, lon))
                    }
                }

                "way", "relation" -> {
                    val center = elem.optJSONObject("center")
                    if (center != null && center.has("lat") && center.has("lon")) {
                        val lat = center.getDouble("lat")
                        val lon = center.getDouble("lon")
                        mosques.add(GeoPoint(lat, lon))
                    } else {
                        val geometry = elem.optJSONArray("geometry")
                        if (geometry != null && geometry.length() > 0) {
                            val firstPoint = geometry.getJSONObject(0)
                            if (firstPoint.has("lat") && firstPoint.has("lon")) {
                                val lat = firstPoint.getDouble("lat")
                                val lon = firstPoint.getDouble("lon")
                                mosques.add(GeoPoint(lat, lon))
                            }
                        }
                    }
                }
            }
        }

        Log.d("MosqueSearch", "Parsed ${mosques.size} mosques")
        return mosques
    }
}