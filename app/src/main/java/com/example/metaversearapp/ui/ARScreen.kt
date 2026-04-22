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
import com.example.metaversearapp.data.NavGraphPathfinder
import com.example.metaversearapp.data.NavNode
import androidx.compose.material.icons.filled.AdminPanelSettings
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
fun ARScreen(db: AppDatabase, onAdminRequest: () -> Unit = {}) {
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
                    delay(500) // Hardware warmup delay
                    // Guard: only enable AR if we are STILL in resumed state after the delay.
                    // Without this check, a quick home-swipe during the 500ms window causes
                    // the delayed coroutine to set canRenderAR=true after ON_PAUSE has already
                    // fired, mounting ARScene while the app is in background. That triggers the
                    // MediaPipe RET_CHECK race condition logged in scheduler.cc.
                    if (currentLifecycleState == Lifecycle.State.RESUMED) {
                        canRenderAR = true
                    }
                }
            } else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                canRenderAR = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            canRenderAR = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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

    // --- PATH / BREADCRUMBS ---
    // Loaded once after calibration, re-computed when destination changes.
    var allNavNodes by remember { mutableStateOf<List<NavNode>>(emptyList()) }
    var allNavEdges by remember { mutableStateOf<List<com.example.metaversearapp.data.NavEdge>>(emptyList()) }
    var pathNodes   by remember { mutableStateOf<List<NavNode>>(emptyList()) }
    // Live list of anchors for the breadcrumb trail. Stored as Compose state so
    // recomposition removes detached crumbs from the ARScene content block.
    var crumbAnchors by remember { mutableStateOf<List<Anchor>>(emptyList()) }

    // Load nav graph from DB once
    LaunchedEffect(Unit) {
        allNavNodes = db.navDao().getAllNodes()
        allNavEdges = db.navDao().getAllEdges()
    }

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

    // --- ANCHOR LOGIC (destination cube) ---
    LaunchedEffect(selectedDestination, isCalibrated, earthTrackingState) {
        roomAnchor?.detach()
        roomAnchor = null

        val currentEarth = earthRef.value
        val dest = selectedDestination
        if (currentEarth != null && earthTrackingState == TrackingState.TRACKING
            && dest != null && isCalibrated) {
            try {
                roomAnchor = currentEarth.createAnchor(
                    dest.lat + latOffset,
                    dest.lon + lonOffset,
                    dest.alt + altOffset,
                    0f, 0f, 0f, 1f
                )
            } catch (_: Exception) { }
        }
    }

    // --- PATH COMPUTATION + BREADCRUMB ANCHORS ---
    LaunchedEffect(selectedDestination, isCalibrated, earthTrackingState) {
        // Detach all existing crumb anchors first
        crumbAnchors.forEach { it.detach() }
        crumbAnchors = emptyList()
        pathNodes    = emptyList()

        val currentEarth = earthRef.value
        val pose = geospatialPose
        val dest = selectedDestination

        if (currentEarth == null || !isCalibrated || pose == null ||
            dest == null || earthTrackingState != TrackingState.TRACKING) return@LaunchedEffect
        if (allNavNodes.isEmpty()) return@LaunchedEffect

        // Find nearest node to user's calibrated position
        val userLat  = pose.latitude  - latOffset
        val userLon  = pose.longitude - lonOffset
        val startNode = NavGraphPathfinder.nearestNode(allNavNodes, userLat, userLon)
            ?: return@LaunchedEffect

        // Find nearest node to destination
        val goalNode = allNavNodes.firstOrNull { it.anchorQrId == dest.qrID }
            ?: NavGraphPathfinder.nearestNode(allNavNodes, dest.lat, dest.lon)
            ?: return@LaunchedEffect

        val path = NavGraphPathfinder.aStar(allNavNodes, allNavEdges, startNode.id, goalNode.id)
        if (path.isEmpty()) return@LaunchedEffect

        pathNodes = path

        // Create anchors for each waypoint (skip first — that's roughly where user stands)
        val newAnchors = mutableListOf<Anchor>()
        for (node in path.drop(1)) {
            try {
                val anchor = currentEarth.createAnchor(
                    node.lat + latOffset,
                    node.lon + lonOffset,
                    node.alt + altOffset,
                    0f, 0f, 0f, 1f
                )
                newAnchors.add(anchor)
            } catch (_: Exception) { }
        }
        crumbAnchors = newAnchors
    }

    if (cameraPermissionState.value) {
        Box(modifier = Modifier.fillMaxSize()) {

            // --- LAYER 1: AR SCENE (SAFE GATED) ---
            // Double-gate: canRenderAR (delayed flag) AND actual lifecycle state.
            // canRenderAR alone is not enough — it could theoretically become stale
            // if the state machine is re-entered before Compose re-composes.
            if (canRenderAR && currentLifecycleState == Lifecycle.State.RESUMED) {
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

                                // Tick off passed breadcrumbs
                                if (crumbAnchors.isNotEmpty() && pathNodes.size > 1) {
                                    val nextNode = pathNodes[1]
                                    val dist = NavGraphPathfinder.haversine(
                                        pose.latitude  - latOffset,
                                        pose.longitude - lonOffset,
                                        nextNode.lat, nextNode.lon
                                    )
                                    if (dist < 3.0) { // within 3 metres → passed it
                                        crumbAnchors.firstOrNull()?.detach()
                                        crumbAnchors = crumbAnchors.drop(1)
                                        pathNodes    = pathNodes.drop(1)
                                    }
                                }

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
                    // Destination marker — larger cyan cube
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
                    // Breadcrumb trail — small green dots floating at chest height
                    crumbAnchors.forEachIndexed { index, anchor ->
                        AnchorNode(anchor = anchor) {
                            CubeNode(
                                size   = Float3(0.12f, 0.12f, 0.12f),
                                center = Position(0f, 1.2f, 0f),
                                materialInstance = materialLoader.createColorInstance(
                                    // First crumb is brighter — next immediate waypoint
                                    color = if (index == 0)
                                        SceneViewColor(0.0f, 1.0f, 0.4f, 1.0f)
                                    else
                                        SceneViewColor(0.0f, 0.7f, 0.3f, 0.8f)
                                )
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
            // Admin mode button — top-right corner
            IconButton(
                onClick  = onAdminRequest,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 8.dp)
            ) {
                Icon(
                    Icons.Default.AdminPanelSettings,
                    contentDescription = "Admin",
                    tint = ComposeColor.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(28.dp)
                )
            }

            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Show path info when navigating
                if (pathNodes.isNotEmpty() && isCalibrated) {
                    val remaining = pathNodes.size - 1
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        colors   = CardDefaults.cardColors(
                            containerColor = ComposeColor(0xFF1A3A1A).copy(alpha = 0.9f)
                        )
                    ) {
                        Text(
                            "🟢 Path: $remaining waypoints remaining",
                            modifier = Modifier.padding(10.dp),
                            color    = ComposeColor(0xFF64FFDA),
                            style    = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
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