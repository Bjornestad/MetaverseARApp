package com.example.metaversearapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.metaversearapp.ui.theme.MetaverseARappTheme
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARScene

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MetaverseARappTheme {
                // Main UI Screen
                ARScreen()
            }
        }
    }
}

@Composable
fun ARScreen() {
    var geospatialStatus by remember { mutableStateOf("Initializing AR...") }

    // 1. Add state to track if permissions are ready
    val cameraPermissionState = remember { mutableStateOf(false) }

    // Standard Android permission check
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val cameraGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        cameraPermissionState.value = cameraGranted
    }

    if (cameraPermissionState.value) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARScene(
                modifier = Modifier.fillMaxSize(),
                // 2. CONFIGURE HERE (Only once on startup)
                sessionConfiguration = { session, config ->
                    config.geospatialMode = Config.GeospatialMode.ENABLED
                    config.focusMode = Config.FocusMode.AUTO
                },
                onSessionUpdated = { session, frame ->
                    val earth = session.earth
                    if (earth == null) {
                        geospatialStatus = "Earth NOT available"
                    } else if (earth.earthState != Earth.EarthState.ENABLED) {
                        geospatialStatus = "Earth Error: ${earth.earthState}"
                    } else if (earth.trackingState == TrackingState.TRACKING) {
                        val pose = earth.cameraGeospatialPose
                        geospatialStatus = "VPS Connected!\n" +
                                "Lat: ${"%.5f".format(pose.latitude)}\n" +
                                "Lon: ${"%.5f".format(pose.longitude)}\n" +
                                "Accuracy: ${"%.2f".format(pose.horizontalAccuracy)}m"
                    } else {
                        geospatialStatus = "Waiting for VPS...\nTracking: ${earth.trackingState}"
                    }
                }
            )
            StatusOverlay(geospatialStatus)
        }
    } else {
        // 3. Simple Fallback UI if camera isn't ready
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera Permission Required")
        }
    }
}
@Composable
fun StatusOverlay(status: String) {
    Card(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
    ) {
        Text(text = status, modifier = Modifier.padding(16.dp))
    }
}
