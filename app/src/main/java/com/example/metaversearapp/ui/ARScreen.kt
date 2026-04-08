package com.example.metaversearapp.ui

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.metaversearapp.data.AppDatabase
import com.example.metaversearapp.data.QrLocation
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.TrackingState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.sceneview.ar.ARScene
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ARScreen(db: AppDatabase) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Initializing...") }
    var currentLocation by remember { mutableStateOf<QrLocation?>(null) }
    var allLocations by remember { mutableStateOf<List<QrLocation>>(emptyList()) }
    var selectedDestination by remember { mutableStateOf<QrLocation?>(null) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    
    // Detailed Geospatial Debug State
    var geospatialPose by remember { mutableStateOf<GeospatialPose?>(null) }
    var earthTrackingState by remember { mutableStateOf(TrackingState.STOPPED) }
    var earthState by remember { mutableStateOf(Earth.EarthState.ENABLED) }
    var isEarthObjectNull by remember { mutableStateOf(true) }
    var sessionGeospatialSupport by remember { mutableStateOf("Checking...") }
    
    var lastProcessingTime by remember { mutableLongStateOf(0L) }

    val scanner = remember { 
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options) 
    }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                statusText = "Syncing from GitHub..."
                val client = HttpClient(Android) {
                    install(ContentNegotiation) { 
                        json(Json { 
                            ignoreUnknownKeys = true 
                            coerceInputValues = true 
                        }, contentType = ContentType.Any)
                    }
                }
                val gistUrl = "https://gist.githubusercontent.com/Bjornestad/6e249fc366e2a43f830659f8914a06fb/raw"
                val response: List<QrLocation> = client.get(gistUrl).body()
                db.qrDao().insertAll(response)
                allLocations = db.qrDao().getAll()
                statusText = "Ready to Scan"
                client.close()
            } catch (e: Exception) {
                statusText = "Sync Failed: ${e.localizedMessage}"
                allLocations = db.qrDao().getAll()
            }
        }
    }

    val cameraPermissionState = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        cameraPermissionState.value = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    if (cameraPermissionState.value) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARScene(
                modifier = Modifier.fillMaxSize(),
                sessionConfiguration = { session, config ->
                    val isSupported = session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
                    sessionGeospatialSupport = if (isSupported) "Supported" else "NOT Supported on this device"
                    
                    config.geospatialMode = Config.GeospatialMode.ENABLED
                    config.focusMode = Config.FocusMode.AUTO
                },
                onSessionUpdated = { session, frame ->
                    // Update Geospatial Data
                    val earth = session.earth
                    if (earth != null) {
                        isEarthObjectNull = false
                        earthState = earth.earthState
                        earthTrackingState = earth.trackingState
                        if (earthTrackingState == TrackingState.TRACKING) {
                            geospatialPose = earth.cameraGeospatialPose
                        }
                    } else {
                        isEarthObjectNull = true
                        earthTrackingState = TrackingState.STOPPED
                    }

                    val currentTime = System.currentTimeMillis()
                    if (isScanning && (currentTime - lastProcessingTime > 1000)) {
                        try {
                            val image = frame.acquireCameraImage() 
                            lastProcessingTime = currentTime
                            
                            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                context.display?.rotation ?: Surface.ROTATION_0
                            } else {
                                @Suppress("DEPRECATION")
                                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                            }

                            val rotationDegrees = when (rotation) {
                                Surface.ROTATION_0 -> 90
                                Surface.ROTATION_90 -> 0
                                Surface.ROTATION_180 -> 270
                                Surface.ROTATION_270 -> 180
                                else -> 90
                            }
                            
                            val inputImage = InputImage.fromMediaImage(image, rotationDegrees)
                            scanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    if (barcodes.isNotEmpty()) {
                                        val id = barcodes[0].rawValue ?: return@addOnSuccessListener
                                        scope.launch {
                                            val loc = db.qrDao().getById(id)
                                            if (loc != null) {
                                                currentLocation = loc
                                                statusText = "Found: ${loc.qrID}"
                                                isScanning = false 
                                            } else {
                                                statusText = "QR ID '$id' not in database"
                                            }
                                        }
                                    } else {
                                        statusText = "Scanning... (Searching for QR)"
                                    }
                                }
                                .addOnFailureListener { e ->
                                    statusText = "Scan Error: ${e.localizedMessage}"
                                }
                                .addOnCompleteListener {
                                    image.close()
                                }
                        } catch (e: Exception) {}
                    }
                }
            )
            
            // Top UI
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StatusOverlay(statusText)
                
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { 
                        isScanning = !isScanning 
                        if (isScanning) {
                            statusText = "Scanning... Point camera at QR Code"
                            currentLocation = null
                        } else {
                            statusText = "Scan cancelled"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isScanning) "Cancel Scan" else "Scan QR Code")
                }

                currentLocation?.let { loc ->
                    QrDetailsCard(loc)
                    
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Navigation Target",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            
                            Box(modifier = Modifier.padding(top = 8.dp)) {
                                OutlinedButton(
                                    onClick = { isDropdownExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(selectedDestination?.qrID ?: "Choose Destination")
                                }
                                
                                DropdownMenu(
                                    expanded = isDropdownExpanded,
                                    onDismissRequest = { isDropdownExpanded = false },
                                    modifier = Modifier.fillMaxWidth(0.9f)
                                ) {
                                    allLocations.filter { it.qrID != loc.qrID }.forEach { location ->
                                        DropdownMenuItem(
                                            text = { Text("${location.qrID} (${location.building})") },
                                            onClick = {
                                                selectedDestination = location
                                                isDropdownExpanded = false
                                                statusText = "Navigating to ${location.qrID}"
                                            }
                                        )
                                    }
                                }
                            }
                            
                            selectedDestination?.let { dest ->
                                Text(
                                    text = "Destination: ${dest.floor}, ${dest.building}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Bottom UI - Geospatial Status
            GeospatialBottomOverlay(
                pose = geospatialPose,
                trackingState = earthTrackingState,
                earthState = earthState,
                isEarthNull = isEarthObjectNull,
                supportInfo = sessionGeospatialSupport,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera Permission Required")
        }
    }
}

@Composable
fun GeospatialBottomOverlay(
    pose: GeospatialPose?,
    trackingState: TrackingState,
    earthState: Earth.EarthState,
    isEarthNull: Boolean,
    supportInfo: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "VPS Diagnostic:",
                    style = MaterialTheme.typography.labelLarge
                )
                val statusColor = if (trackingState == TrackingState.TRACKING) Color(0xFF4CAF50) else Color(0xFFF44336)
                Text(
                    text = trackingState.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.1f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            
            Text("Device Support: $supportInfo", style = MaterialTheme.typography.labelSmall)
            Text(
                "Earth State: ${earthState.name} ${if (isEarthNull) "(Object is NULL)" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = if (earthState == Earth.EarthState.ENABLED && !isEarthNull) Color.Unspecified else Color.Red
            )

            if (pose != null && trackingState == TrackingState.TRACKING) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = String.format(Locale.US, "Lat: %.6f, Lon: %.6f", pose.latitude, pose.longitude),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = String.format(Locale.US, "Alt: %.2fm | Accuracy: %.2fm", pose.altitude, pose.horizontalAccuracy),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = String.format(Locale.US, "Yaw Accur: %.1f° | Heading: %.1f°", pose.orientationYawAccuracy, pose.heading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                val debugHint = when {
                    isEarthNull -> "CRITICAL: ARCore session failed to create Earth object."
                    earthState == Earth.EarthState.ERROR_NOT_AUTHORIZED -> "AUTH ERROR: SHA-1 or Package Name mismatch in Cloud Console."
                    earthState == Earth.EarthState.ERROR_GEOSPATIAL_MODE_DISABLED -> "MODE ERROR: Geospatial mode not enabled in config."
                    trackingState == TrackingState.PAUSED -> "PAUSED: Move camera around to find buildings."
                    else -> "LOCALIZING: Point camera at building facades across the street."
                }
                Text(
                    text = debugHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun QrDetailsCard(loc: QrLocation) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SCANNED LOCATION", 
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = loc.qrID, 
                style = MaterialTheme.typography.headlineSmall
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    DetailItem("Building", loc.building)
                    DetailItem("Floor", loc.floor)
                    DetailItem("Direction", loc.direction)
                }
                Column {
                    DetailItem("Lat", String.format(Locale.US, "%.5f", loc.lat))
                    DetailItem("Lon", String.format(Locale.US, "%.5f", loc.lon))
                    DetailItem("Alt", "${loc.alt}m")
                }
            }
        }
    }
}

@Composable
fun DetailItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun StatusOverlay(status: String) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
    ) {
        Text(
            text = status, 
            modifier = Modifier.padding(16.dp), 
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
