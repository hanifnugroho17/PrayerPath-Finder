package com.mhss.app.prayfirst.presentation.qibla

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import coil.compose.rememberAsyncImagePainter
import com.mhss.app.prayfirst.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun QiblaScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    val userLat = -6.200000
    val userLon = 106.816666

    var azimuth by remember { mutableStateOf(0f) }
    var qiblaDirection by remember { mutableStateOf(0f) }

    val kaabahLat = 21.4225
    val kaabahLon = 39.8262

    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360
        return bearing.toFloat()
    }

    LaunchedEffect(Unit) {
        qiblaDirection = calculateBearing(userLat, userLon, kaabahLat, kaabahLon)
    }

    DisposableEffect(sensorManager, lifecycleOwner) {
        val accelerometerReading = FloatArray(3)
        val magnetometerReading = FloatArray(3)
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when(event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, accelerometerReading, 0, event.values.size)
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, magnetometerReading, 0, event.values.size)
                    }
                }

                if (accelerometerReading.isNotEmpty() && magnetometerReading.isNotEmpty()) {
                    val success = SensorManager.getRotationMatrix(
                        rotationMatrix,
                        null,
                        accelerometerReading,
                        magnetometerReading
                    )

                    if (success) {
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        var azimuthDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()

                        if (azimuthDegrees < 0) azimuthDegrees += 360f
                        azimuth = azimuthDegrees
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            }
        }

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }

        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                sensorManager.unregisterListener(listener)
            }

            override fun onResume(owner: LifecycleOwner) {
                accelerometer?.let {
                    sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
                }
                magnetometer?.let {
                    sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            sensorManager.unregisterListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val compassPainter = rememberAsyncImagePainter(R.drawable.compas)
        val qiblaPointerPainter = rememberAsyncImagePainter(R.drawable.qibla_arrow)

        Box(
            modifier = Modifier.size(300.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = compassPainter,
                contentDescription = "Kompas",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        rotationZ = -azimuth
                    )
            )

            Image(
                painter = qiblaPointerPainter,
                contentDescription = "Jarum Kiblat",
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer(
                        rotationZ = qiblaDirection - azimuth
                    )
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Arah Kiblat: ${qiblaDirection.toInt()}°",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Azimuth: ${azimuth.toInt()}°",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}