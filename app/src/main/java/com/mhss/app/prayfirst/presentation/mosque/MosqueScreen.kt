package com.mhss.app.prayfirst.presentation.mosque

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

data class MosqueInfo(
    val geoPoint: GeoPoint,
    val name: String,
    val osmId: Long
)

data class MosqueScreenState(
    val errorMessage: String? = null,
    val currentLocation: GeoPoint? = null,
    val isLoadingLocation: Boolean = false,
    val isTrackingLocation: Boolean = false,
    val currentZoom: Double = 15.0,
    val isMapReady: Boolean = false,
    val userMarker: Marker? = null
)

private object MapConstants {
    const val MARKER_SIZE_DP = 24
    const val LOCATION_UPDATE_INTERVAL = 2000L
    const val NETWORK_UPDATE_INTERVAL = 5000L
    const val GPS_MIN_DISTANCE = 5f
    const val NETWORK_MIN_DISTANCE = 10f
    const val MOSQUE_SEARCH_RADIUS = 1000
    val DEFAULT_LOCATION = GeoPoint(-7.7956, 110.3695)
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MosqueScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    InitializeOSMDroid(context)

    val locationPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    if (!locationPermissions.allPermissionsGranted) {
        PermissionRequestScreen(
            onRequestPermissions = { locationPermissions.launchMultiplePermissionRequest() }
        )
        return
    }

    var screenState by remember { mutableStateOf(MosqueScreenState()) }
    val mapView = remember { createMapView(context, screenState.currentZoom) }

    val locationManager = remember {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    var locationListener by remember { mutableStateOf<LocationListener?>(null) }

    MapLifecycleEffect(
        lifecycleOwner = lifecycleOwner,
        mapView = mapView,
        locationManager = locationManager,
        locationListener = locationListener
    )

    Box(modifier = Modifier.fillMaxSize()) {
        MapViewComposable(
            mapView = mapView,
            onMapReady = { screenState = screenState.copy(isMapReady = true) },
        )

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
            screenState.currentLocation?.let { location ->
                LocationInfoCard(
                    location = location,
                    isTracking = screenState.isTrackingLocation
                )
            }

            screenState.errorMessage?.let { message ->
                InfoCard(
                    message = message,
                    isError = message.contains("Error", ignoreCase = true) ||
                            message.contains("Gagal", ignoreCase = true) ||
                            message.contains("Tidak", ignoreCase = true)
                )
            }
        }
    }

    LaunchedEffect(screenState.isMapReady, screenState.currentLocation) {
        if (screenState.isMapReady) {
            val locationToUse = screenState.currentLocation ?: MapConstants.DEFAULT_LOCATION

            if (screenState.currentLocation == null) {
                mapView.controller.setCenter(locationToUse)
                mapView.controller.setZoom(screenState.currentZoom)
                mapView.invalidate()
            }

            kotlinx.coroutines.delay(500)

            loadNearbyMosques(
                location = locationToUse,
                mapView = mapView,
                context = context,
                userMarker = screenState.userMarker,
                onResult = { mosqueCount, error ->
                    screenState = screenState.copy(
                        errorMessage = error ?: if (mosqueCount == 0) {
                            "Tidak ada masjid ditemukan dalam radius ${MapConstants.MOSQUE_SEARCH_RADIUS / 1000}km."
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
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
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
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            Log.d("MosqueScreen", "AndroidView update called, invalidating.")
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
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = if (isTracking) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = if (isTracking) "Stop Tracking" else "Start Tracking",
                modifier = Modifier.size(24.dp),
                tint = if (isTracking) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.primary
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Text(
            text = message,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
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
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(0.8f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Lokasi: ${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (isTracking) {
                Text(
                    text = "• Real-time tracking aktif",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp)
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
    DisposableEffect(lifecycleOwner, mapView) {
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
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            Log.d("MosqueScreen", "MapLifecycleEffect onDispose: Detaching MapView and cleaning listener.")
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
            cleanupLocationListener(locationManager, locationListener)
        }
    }
}

private fun createMapView(context: Context, initialZoom: Double): MapView {
    Log.d("MosqueScreen", "Creating MapView instance")
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(initialZoom)
        minZoomLevel = 8.0
        maxZoomLevel = 19.0
    }
}

private fun cleanupLocationListener(
    locationManager: LocationManager,
    locationListener: LocationListener?
) {
    locationListener?.let { listener ->
        try {
            locationManager.removeUpdates(listener)
            Log.d("MosqueScreen", "Location listener removed successfully.")
        } catch (e: SecurityException) {
            Log.e("MosqueScreen", "SecurityException removing location updates", e)
        } catch (e: Exception) {
            Log.e("MosqueScreen", "Exception removing location updates", e)
        }
    }
}

@SuppressLint("MissingPermission")
private fun startLocationTracking(
    context: Context,
    locationManager: LocationManager,
    mapView: MapView,
    screenState: MosqueScreenState,
    onStateUpdate: (MosqueScreenState) -> Unit,
    onListenerCreated: (LocationListener) -> Unit
) {
    onStateUpdate(screenState.copy(isLoadingLocation = true, errorMessage = null))

    LocationTrackingManager.startTracking(
        context = context,
        locationManager = locationManager,
        onLocationReceived = { location ->
            val newUserMarker = updateUserMarker(mapView, location, context, screenState.userMarker)
            onStateUpdate(
                screenState.copy(
                    currentLocation = location,
                    isLoadingLocation = false,
                    isTrackingLocation = true,
                    userMarker = newUserMarker,
                    errorMessage = null
                )
            )
            mapView.controller.animateTo(location)
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
        }
    )
}

private fun stopLocationTracking(
    locationManager: LocationManager,
    locationListener: LocationListener?,
    onStateUpdate: (MosqueScreenState) -> Unit
) {
    cleanupLocationListener(locationManager, locationListener)
    onStateUpdate(MosqueScreenState().copy(isTrackingLocation = false, isLoadingLocation = false, errorMessage = "Tracking lokasi dihentikan."))
    Log.d("MosqueScreen", "Stopped location tracking via UI.")
}


private fun updateUserMarker(
    mapView: MapView,
    location: GeoPoint,
    context: Context,
    currentUserMarker: Marker?
): Marker {
    currentUserMarker?.let {
        mapView.overlays.remove(it)
    }

    val newMarker = createUserMarker(mapView, location, context)
    mapView.overlays.add(0, newMarker)
    return newMarker
}


private fun createUserMarker(mapView: MapView, location: GeoPoint, context: Context): Marker {
    return Marker(mapView).apply {
        position = location
        title = "Lokasi Anda"
        snippet = "Posisi saat ini"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        try {
            val userDrawable = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_mylocation)
            userDrawable?.let { drawable ->
                val blueColor = ContextCompat.getColor(context, R.color.purple_500)
                val mutableDrawable = DrawableCompat.wrap(drawable.mutate()).mutate()
                DrawableCompat.setTint(mutableDrawable, blueColor)

                val sizeInPixels =
                    (MapConstants.MARKER_SIZE_DP * context.resources.displayMetrics.density).toInt()
                val finalSize = if (sizeInPixels > 0) sizeInPixels else MapConstants.MARKER_SIZE_DP

                val bitmap = Bitmap.createBitmap(finalSize, finalSize, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                mutableDrawable.setBounds(0, 0, canvas.width, canvas.height)
                mutableDrawable.draw(canvas)
                icon = BitmapDrawable(context.resources, bitmap)

                Log.d("MarkerDebug", "User marker size set to: ${finalSize}px")
            }
        } catch (e: Exception) {
            Log.w("MosqueScreen", "Could not load user location icon", e)
            try {
                icon = ContextCompat.getDrawable(context, org.osmdroid.library.R.drawable.person)
            } catch (ex: Exception) {
                Log.e("MarkerDebug", "Failed to set fallback user icon", ex)
            }
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
        onSuccess = { mosquesInfo ->
            Log.d("MosqueScreen", "Mosques found: ${mosquesInfo.size}")

            val overlaysToRemove = mapView.overlays.filterNot { it == userMarker }
            mapView.overlays.removeAll(overlaysToRemove)

            mosquesInfo.forEach { mosqueInfo ->
                val marker = createMosqueMarker(mapView, mosqueInfo, location, context)
                mapView.overlays.add(marker)
            }

            mapView.invalidate()
            onResult(mosquesInfo.size, null)
        },
        onError = { error ->
            Log.e("MosqueScreen", "Error fetching mosques: $error")
            val overlaysToRemove = mapView.overlays.filterNot { it == userMarker }
            mapView.overlays.removeAll(overlaysToRemove)
            mapView.invalidate()
            onResult(0, error)
        }
    )
}

private fun createMosqueMarker(
    mapView: MapView,
    mosqueInfo: MosqueInfo,
    userLocation: GeoPoint,
    context: Context
): Marker {
    return Marker(mapView).apply {
        position = mosqueInfo.geoPoint
        title = mosqueInfo.name

        val distance = userLocation.distanceToAsDouble(mosqueInfo.geoPoint)
        snippet = "Jarak: ${String.format("%.0f", distance)} meter\nID OSM: ${mosqueInfo.osmId}"

        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        try {
            val originalDrawable = ContextCompat.getDrawable(context, R.drawable.mosquee_fill)
            if (originalDrawable != null) {
                val iconSizePx =
                    (MapConstants.MARKER_SIZE_DP * context.resources.displayMetrics.density).toInt()
                val finalSizePx = if (iconSizePx <= 0) MapConstants.MARKER_SIZE_DP else iconSizePx // Pastikan tidak 0 atau negatif
                val bitmap = Bitmap.createBitmap(finalSizePx, finalSizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                originalDrawable.setBounds(0, 0, canvas.width, canvas.height)
                originalDrawable.draw(canvas)
                icon = BitmapDrawable(context.resources, bitmap)
                Log.d(
                    "MarkerDebug",
                    "Mosque marker for '${mosqueInfo.name}' size set to: ${finalSizePx}px"
                )
            } else {
                Log.w("MosqueScreen", "R.drawable.mosquee_fill is null for '${mosqueInfo.name}', using default.")
                this.icon = ContextCompat.getDrawable(context, org.osmdroid.library.R.drawable.marker_default)
            }
        } catch (e: Exception) {
            Log.w(
                "MosqueScreen",
                "Could not load or resize mosque_fill icon for '${mosqueInfo.name}', using default. Error: ${e.message}",
                e
            )
            try {
                this.icon = ContextCompat.getDrawable(context, org.osmdroid.library.R.drawable.marker_default)
            } catch (ex: Exception) {
                Log.e("MarkerDebug", "Failed to set even fallback system icon for '${mosqueInfo.name}'", ex)
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

            // Try to get last known location first (hanya jika belum ada listener aktif)
            var hasReceivedInitialLocation = false
            try {
                val lastKnownLocationGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val lastKnownLocationNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                val bestLastKnownLocation = when {
                    lastKnownLocationGPS != null && lastKnownLocationNetwork != null -> {
                        if (lastKnownLocationGPS.time > lastKnownLocationNetwork.time) lastKnownLocationGPS else lastKnownLocationNetwork
                    }
                    lastKnownLocationGPS != null -> lastKnownLocationGPS
                    lastKnownLocationNetwork != null -> lastKnownLocationNetwork
                    else -> null
                }

                bestLastKnownLocation?.let { location ->
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    onLocationReceived(geoPoint)
                    hasReceivedInitialLocation = true
                    Log.d("LocationTracking", "Using best last known location: $geoPoint from ${location.provider}")
                }
            } catch (se: SecurityException){
                Log.e("LocationTrackingManager", "SecurityException on getLastKnownLocation", se)
                onError("Izin lokasi diperlukan untuk mendapatkan lokasi terakhir.")
            }


            val locationListener = createLocationListener(onLocationReceived, onError, locationManager)
            onListenerCreated(locationListener)

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MapConstants.LOCATION_UPDATE_INTERVAL,
                    MapConstants.GPS_MIN_DISTANCE,
                    locationListener
                )
                Log.d("LocationTracking", "Requested GPS location updates.")
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MapConstants.NETWORK_UPDATE_INTERVAL,
                    MapConstants.NETWORK_MIN_DISTANCE,
                    locationListener
                )
                Log.d("LocationTracking", "Requested Network location updates.")
            }

            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                onError("Tidak ada provider lokasi yang aktif setelah mencoba meminta update.")
                return
            }

            if(!hasReceivedInitialLocation && (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))){
                Log.d("LocationTracking", "No last known location, waiting for updates...")
            }


            Log.d("LocationTracking", "Real-time location tracking setup initiated.")

        } catch (e: SecurityException) {
            Log.e("LocationTrackingManager", "SecurityException during startTracking", e)
            onError("Izin lokasi tidak diberikan atau dicabut.")
        } catch (e: Exception) {
            Log.e("LocationTrackingManager", "Exception during startTracking", e)
            onError("Error memulai tracking lokasi: ${e.localizedMessage}")
        }
    }

    private fun createLocationListener(
        onLocationReceived: (GeoPoint) -> Unit,
        onError: (String) -> Unit,
        locationManager: LocationManager
    ): LocationListener {
        return object : LocationListener {
            private var lastLocationTime: Long = 0
            private val locationDebounceInterval = 1000L

            override fun onLocationChanged(location: Location) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastLocationTime < locationDebounceInterval && location.provider == LocationManager.NETWORK_PROVIDER) {

                }
                lastLocationTime = currentTime

                val geoPoint = GeoPoint(location.latitude, location.longitude)
                onLocationReceived(geoPoint)
                Log.d(
                    "LocationTracking",
                    "Location updated from ${location.provider}: $geoPoint, Acc: ${location.accuracy}m, Time: ${location.time}"
                )
            }

            override fun onProviderEnabled(provider: String) {
                Log.i("LocationTracking", "Provider enabled: $provider")
                if (provider == LocationManager.GPS_PROVIDER) {
                    try {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            MapConstants.LOCATION_UPDATE_INTERVAL,
                            MapConstants.GPS_MIN_DISTANCE,
                            this // listener ini sendiri
                        )
                        Log.i("LocationTracking", "Re-requested GPS updates as it was enabled.")
                    } catch (se: SecurityException){
                        Log.e("LocationTracking", "SecurityException re-requesting GPS on enable.", se)
                    }
                }
            }

            override fun onProviderDisabled(provider: String) {
                Log.w("LocationTracking", "Provider disabled: $provider")
                val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                if (!gpsEnabled && !networkEnabled) {
                    onError("Semua provider lokasi (GPS & Network) nonaktif.")
                } else if (provider == LocationManager.GPS_PROVIDER && networkEnabled) {
                    Log.i("LocationTracking", "GPS disabled, Network provider is still active.")
                } else if (provider == LocationManager.NETWORK_PROVIDER && gpsEnabled){
                    Log.i("LocationTracking", "Network disabled, GPS provider is still active.")
                }
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                val statusString = when (status) {
                    android.location.LocationProvider.AVAILABLE -> "AVAILABLE"
                    android.location.LocationProvider.OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                    android.location.LocationProvider.TEMPORARILY_UNAVAILABLE -> "TEMPORARILY_UNAVAILABLE"
                    else -> "UNKNOWN_STATUS ($status)"
                }
                Log.d("LocationTracking", "Provider status changed: $provider, Status: $statusString")
            }
        }
    }
}

object MosqueSearchManager {
    fun searchNearby(
        location: GeoPoint,
        radius: Int,
        onSuccess: (List<MosqueInfo>) -> Unit, // DIUBAH: List<GeoPoint> -> List<MosqueInfo>
        onError: (String) -> Unit
    ) {
        // Query Overpass API untuk mencari tempat ibadah muslim dengan tag nama
        val query = """
            [out:json][timeout:25];
            (
              node["amenity"="place_of_worship"]["religion"="muslim"](around:$radius,${location.latitude},${location.longitude});
              way["amenity"="place_of_worship"]["religion"="muslim"](around:$radius,${location.latitude},${location.longitude});
              relation["amenity"="place_of_worship"]["religion"="muslim"](around:$radius,${location.latitude},${location.longitude});
            );
            out center tags; 
        """.trimIndent() // 'out center tags;' untuk mendapatkan tags juga untuk way/relation

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
        onSuccess: (List<MosqueInfo>) -> Unit,
        onError: (String) -> Unit
    ) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "NamaAplikasiAnda/1.0 (Android; Kontak: emailanda@example.com)") // User-Agent yang baik
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("MosqueSearch", "OkHttp onFailure: ${e.message}", e)
                CoroutineScope(Dispatchers.Main).launch {
                    onError("Gagal terhubung ke server pencarian: ${e.localizedMessage}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBodyString = it.body?.string() // Baca body sekali saja

                    if (!it.isSuccessful) {
                        Log.e("MosqueSearch", "OkHttp onResponse unsuccessful: ${it.code}. Body: $responseBodyString")
                        CoroutineScope(Dispatchers.Main).launch {
                            onError("Error dari server pencarian: ${it.code} ${it.message}. Detail: ${responseBodyString?.take(200) ?: "No body"}")
                        }
                        return
                    }

                    if (responseBodyString.isNullOrEmpty()) {
                        Log.w("MosqueSearch", "Response body is null or empty.")
                        CoroutineScope(Dispatchers.Main).launch {
                            onError("Tidak ada data diterima dari server pencarian.")
                        }
                        return
                    }

                    try {
                        val mosques = parseMosqueData(responseBodyString)
                        CoroutineScope(Dispatchers.Main).launch {
                            onSuccess(mosques)
                        }
                    } catch (e: JSONException) {
                        Log.e("MosqueSearch", "JSON Parsing error", e)
                        CoroutineScope(Dispatchers.Main).launch {
                            onError("Gagal memproses data masjid (Format JSON tidak valid): ${e.localizedMessage}")
                        }
                    } catch (e: Exception) {
                        Log.e("MosqueSearch", "General error in onResponse post-processing", e)
                        CoroutineScope(Dispatchers.Main).launch {
                            onError("Terjadi kesalahan saat memproses data masjid: ${e.localizedMessage}")
                        }
                    }
                }
            }
        })
    }

    private fun parseMosqueData(json: String): List<MosqueInfo> {
        val mosques = mutableListOf<MosqueInfo>()
        val root = JSONObject(json)
        val elements = root.getJSONArray("elements")

        for (i in 0 until elements.length()) {
            val elem = elements.getJSONObject(i)
            val type = elem.getString("type")
            val osmId = elem.getLong("id")

            val tags = elem.optJSONObject("tags")
            val name = tags?.optString("name", "Masjid (Tanpa Nama)")?.trim() ?: "Masjid (Tanpa Nama)"
            val finalName = if (name.isEmpty()) "Masjid (Tanpa Nama)" else name


            var lat: Double? = null
            var lon: Double? = null

            when (type) {
                "node" -> {
                    if (elem.has("lat") && elem.has("lon")) {
                        lat = elem.getDouble("lat")
                        lon = elem.getDouble("lon")
                    }
                }
                "way", "relation" -> {
                    val center = elem.optJSONObject("center")
                    if (center != null && center.has("lat") && center.has("lon")) {
                        lat = center.getDouble("lat")
                        lon = center.getDouble("lon")
                    } else {
                        val geometry = elem.optJSONArray("geometry")
                        if (geometry != null && geometry.length() > 0) {
                            val firstPoint = geometry.getJSONObject(0) // Ambil titik pertama sebagai fallback
                            if (firstPoint.has("lat") && firstPoint.has("lon")) {
                                lat = firstPoint.getDouble("lat")
                                lon = firstPoint.getDouble("lon")
                                Log.w("MosqueSearch", "Using first geometry point for way/relation ID $osmId, Name: $finalName. 'center' object might be missing.")
                            }
                        }
                    }
                }
            }

            if (lat != null && lon != null) {
                mosques.add(MosqueInfo(GeoPoint(lat, lon), finalName, osmId))
            } else {
                Log.w("MosqueSearch", "Skipping element without valid coordinates. ID: $osmId, Name: $finalName, Type: $type")
            }
        }
        Log.d("MosqueSearch", "Parsed ${mosques.size} mosques with names.")
        return mosques
    }
}