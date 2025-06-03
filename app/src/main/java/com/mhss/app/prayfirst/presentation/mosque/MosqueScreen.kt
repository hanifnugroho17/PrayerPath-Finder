package com.mhss.app.prayfirst.presentation.mosque

import android.Manifest
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.mhss.app.prayfirst.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import java.io.IOException

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MosqueScreen() {
    val context = LocalContext.current

    // Request location permission state from Accompanist
    val locationPermissionState = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)

    // State untuk error message
    var errorMessage by remember { mutableStateOf<String?>(null) }

    if (!locationPermissionState.status.isGranted) {
        // UI minta izin lokasi
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Aplikasi membutuhkan izin lokasi untuk menemukan masjid terdekat.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { locationPermissionState.launchPermissionRequest() }) {
                Text("Minta Izin Lokasi")
            }
        }
        return
    }

    // Jika sudah dapat izin lokasi, setup peta dan pencarian
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize()
    )

    // Lokasi user (hardcoded Jakarta)
    val userLocation = GeoPoint(-6.200000, 106.816666)

    LaunchedEffect(mapView) {
        // Center map to user location
        mapView.controller.setCenter(userLocation)

        searchNearbyMosques(userLocation.latitude, userLocation.longitude) { mosques ->
            // Bersihkan marker lama
            mapView.overlays.clear()

            // Tambah marker lokasi user
            val userMarker = Marker(mapView).apply {
                position = userLocation
                title = "Lokasi Anda"
            }
            mapView.overlays.add(userMarker)

            // Tambah marker masjid
            mosques.forEach { geoPoint ->
                val marker = Marker(mapView).apply {
                    position = geoPoint
                    title = "Masjid"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = context.getDrawable(R.drawable.mosque) // Pastikan drawable ini ada
                }
                mapView.overlays.add(marker)
            }
            mapView.invalidate()
        }
    }

    // Tampilkan error jika ada
    if (errorMessage != null) {
        Text(
            text = "Error: $errorMessage",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

fun searchNearbyMosques(lat: Double, lon: Double, radius: Int = 1000, onResult: (List<GeoPoint>) -> Unit) {
    val query = """
        [out:json];
        node["amenity"="place_of_worship"]["religion"="muslim"](around:$radius,$lat,$lon);
        out;
    """.trimIndent()

    val url = "https://overpass-api.de/api/interpreter?data=${java.net.URLEncoder.encode(query, "UTF-8")}"

    val client = OkHttpClient()
    val request = Request.Builder().url(url).build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            CoroutineScope(Dispatchers.Main).launch {
                onResult(emptyList())
            }
        }

        override fun onResponse(call: Call, response: Response) {
            response.body?.string()?.let { jsonStr ->
                val mosques = parseMosquesFromJson(jsonStr)
                CoroutineScope(Dispatchers.Main).launch {
                    onResult(mosques)
                }
            } ?: CoroutineScope(Dispatchers.Main).launch {
                onResult(emptyList())
            }
        }
    })
}

fun parseMosquesFromJson(json: String): List<GeoPoint> {
    val mosques = mutableListOf<GeoPoint>()
    val root = JSONObject(json)
    val elements = root.getJSONArray("elements")

    for(i in 0 until elements.length()) {
        val elem = elements.getJSONObject(i)
        val lat = elem.getDouble("lat")
        val lon = elem.getDouble("lon")
        mosques.add(GeoPoint(lat, lon))
    }
    return mosques
}