package com.example.metaversearapp.ui

import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.metaversearapp.data.AppDatabase
import com.example.metaversearapp.data.QrFeature
import com.example.metaversearapp.data.QrLocation
import com.example.metaversearapp.data.toEntity
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.TrackingState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Color as SceneViewColor
import io.github.sceneview.node.CubeNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ARScreen(db: AppDatabase) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val modelLoader = rememberModelLoader(engine)

    // --- SAFETY & LIFECYCLE STATE ---
    var canRenderAR by remember { mutableStateOf(false) }
    var currentLifecycleState by remember { mutableStateOf(Lifecycle.State.INITIALIZED) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            currentLifecycleState = event.targetState
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    delay(500) // Hardware cooling delay
                    canRenderAR = true
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                canRenderAR = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- APP STATE ---
    var statusText by remember { mutableStateOf("Initializing...") }
    var allLocations by remember { mutableStateOf<List<QrLocation>>(emptyList()) }
    var selectedDestination by remember { mutableStateOf<QrLocation?>(null) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }

    var geospatialPose by remember { mutableStateOf<GeospatialPose?>(null) }
    var earthTrackingState by remember { mutableStateOf(TrackingState.STOPPED) }
    var earthState by remember { mutableStateOf(Earth.EarthState.ENABLED) }
    var isEarthObjectNull by remember { mutableStateOf(true) }

    var latOffset by remember { mutableDoubleStateOf(0.0) }
    var lonOffset by remember { mutableDoubleStateOf(0.0) }
    var altOffset by remember { mutableDoubleStateOf(0.0) }
    var isCalibrated by remember { mutableStateOf(false) }

    var roomAnchor by remember { mutableStateOf<Anchor?>(null) }
    var lastProcessingTime by remember { mutableLongStateOf(0L) }

    val scanner = remember {
        val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        BarcodeScanning.getClient(options)
    }

    // --- DATA SYNC ---
    LaunchedEffect(Unit) {
        try {
            statusText = "Syncing locations..."
            val client = HttpClient(Android) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; coerceInputValues = true }, contentType = ContentType.Any)
                }
            }
            val gistUrl = "https://gist.githubusercontent.com/Bjornestad/3b90e3bd67e9cd9a4bce90fb14f158e9/raw"
            val response: List<QrFeature> = client.get(gistUrl).body()
            db.qrDao().insertAll(response.map { it.toEntity() })
            allLocations = db.qrDao().getAll()
            statusText = "Ready: Select Destination"
            client.close()
        } catch (e: Exception) {
            statusText = "Sync Failed: ${e.localizedMessage}"
            allLocations = db.qrDao().getAll()
        }
    }

    val cameraPermissionState = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        cameraPermissionState.value = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val earthRef = remember { mutableStateOf<Earth?>(null) }

    // --- ANCHOR LOGIC ---
    LaunchedEffect(selectedDestination, isCalibrated, earthTrackingState) {
        val currentEarth = earthRef.value
        val dest = selectedDestination
        if (currentEarth != null && earthTrackingState == TrackingState.TRACKING && dest != null) {
            roomAnchor = currentEarth.createAnchor(
                dest.lat + latOffset,
                dest.lon + lonOffset,
                dest.alt + altOffset,
                0f, 0f, 0f, 1f
            )
        }
    }

    if (cameraPermissionState.value) {
        Box(modifier = Modifier.fillMaxSize()) {

            // --- LAYER 1: AR SCENE (SAFE GATED) ---
            if (canRenderAR) {
                ARScene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    sessionConfiguration = { session, config ->
                        config.geospatialMode = Config.GeospatialMode.ENABLED
                        config.focusMode = Config.FocusMode.AUTO
                    },
                    onSessionUpdated = { session, frame ->
                        val currentEarth = session.earth
                        earthRef.value = currentEarth

                        if (currentEarth != null) {
                            isEarthObjectNull = false
                            earthState = currentEarth.earthState
                            earthTrackingState = currentEarth.trackingState

                            if (earthTrackingState == TrackingState.TRACKING) {
                                val pose = currentEarth.cameraGeospatialPose
                                geospatialPose = pose

                                val currentTime = System.currentTimeMillis()
                                if (isScanning && (currentTime - lastProcessingTime > 1000)) {
                                    try {
                                        if (currentLifecycleState == Lifecycle.State.RESUMED) {
                                            val image = frame.acquireCameraImage()
                                            lastProcessingTime = currentTime

                                            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                context.display?.rotation ?: Surface.ROTATION_0
                                            } else {
                                                @Suppress("DEPRECATION")
                                                (context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                                            }

                                            val inputImage = InputImage.fromMediaImage(image, when(rotation) {
                                                Surface.ROTATION_90 -> 0; Surface.ROTATION_180 -> 270; Surface.ROTATION_270 -> 180; else -> 90
                                            })

                                            scanner.process(inputImage)
                                                .addOnSuccessListener { barcodes ->
                                                    if (barcodes.isNotEmpty()) {
                                                        val id = barcodes[0].rawValue ?: return@addOnSuccessListener
                                                        scope.launch {
                                                            val loc = db.qrDao().getById(id)
                                                            if (loc != null) {
                                                                latOffset = pose.latitude - loc.lat
                                                                lonOffset = pose.longitude - loc.lon
                                                                altOffset = pose.altitude - loc.alt
                                                                isCalibrated = true
                                                                isScanning = false
                                                                statusText = "Calibrated at ${loc.name}"
                                                            }
                                                        }
                                                    }
                                                }
                                                .addOnCompleteListener { image.close() }
                                        }
                                    } catch (e: Exception) { /* Native buffer safety */ }
                                }
                            }
                        }
                    }
                ) {
                    roomAnchor?.let { anchor ->
                        AnchorNode(anchor = anchor) {
                            CubeNode(
                                size = Float3(0.5f, 0.5f, 0.5f),
                                center = Position(0f, 0.25f, 0f),
                                materialInstance = remember(materialLoader) {
                                    materialLoader.createColorInstance(color = SceneViewColor(0.0f, 1.0f, 1.0f, 1.0f))
                                }
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(ComposeColor.Black), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ComposeColor.Cyan)
                }
            }

            // --- LAYER 2: UI OVERLAYS (ALWAYS VISIBLE) ---
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StatusOverlay(statusText)
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("1. Target Destination:", style = MaterialTheme.typography.labelMedium)
                        Box {
                            OutlinedButton(onClick = { isDropdownExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(selectedDestination?.name ?: "Select Room")
                            }
                            DropdownMenu(expanded = isDropdownExpanded, onDismissRequest = { isDropdownExpanded = false }) {
                                allLocations.forEach { loc ->
                                    DropdownMenuItem(
                                        text = { Text(loc.name) },
                                        onClick = {
                                            selectedDestination = loc
                                            isDropdownExpanded = false
                                            statusText = "Targeting ${loc.name}"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        isScanning = !isScanning
                        statusText = if (isScanning) "2. Scan Room QR to Calibrate..." else "Ready"
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) ComposeColor.Red else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isScanning) "Cancel Scan" else "Scan QR to Calibrate")
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (selectedDestination != null && geospatialPose != null && isCalibrated) {
                    NavigationArrow(
                        currentPose = geospatialPose!!,
                        destination = selectedDestination!!,
                        latOffset = latOffset,
                        lonOffset = lonOffset
                    )
                }

                GeospatialBottomOverlay(
                    pose = geospatialPose,
                    trackingState = earthTrackingState,
                    earthState = earthState,
                    isEarthNull = isEarthObjectNull,
                    isCalibrated = isCalibrated
                )
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera Permission Required")
        }
    }
}

// ... NavigationArrow, GeospatialBottomOverlay, StatusOverlay, QrDetailsCard (Your existing composables)
@Composable
fun NavigationArrow(currentPose: GeospatialPose, destination: QrLocation, latOffset: Double, lonOffset: Double) {
    val targetLat = destination.lat + latOffset
    val targetLon = destination.lon + lonOffset
    val lat1 = Math.toRadians(currentPose.latitude)
    val lon1 = Math.toRadians(currentPose.longitude)
    val lat2 = Math.toRadians(targetLat)
    val lon2 = Math.toRadians(targetLon)
    val dLon = lon2 - lon1
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val bearing = Math.toDegrees(atan2(y, x))
    val relativeAngle = (bearing - currentPose.heading).toFloat()

    Card(
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)),
        modifier = Modifier.padding(bottom = 8.dp).size(64.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = null,
                modifier = Modifier.size(40.dp).rotate(relativeAngle),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun GeospatialBottomOverlay(pose: GeospatialPose?, trackingState: TrackingState, earthState: Earth.EarthState, isEarthNull: Boolean, isCalibrated: Boolean) {
    Card(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("VPS Status:", style = MaterialTheme.typography.labelLarge)
                val statusColor = if (isCalibrated) ComposeColor(0xFF4CAF50) else ComposeColor.Gray
                Text(if (isCalibrated) "CALIBRATED" else "UNCALIBRATED", color = statusColor)
            }
            Text("Tracking: ${trackingState.name}", color = if (trackingState == TrackingState.TRACKING) ComposeColor(0xFF4CAF50) else ComposeColor.Red)
            if (pose != null && trackingState == TrackingState.TRACKING) {
                Text(String.format(Locale.US, "Lat: %.6f, Lon: %.6f", pose.latitude, pose.longitude), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun StatusOverlay(status: String) {
    Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
        Text(status, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}