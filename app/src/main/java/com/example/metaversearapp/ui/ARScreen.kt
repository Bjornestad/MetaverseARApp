package com.example.metaversearapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.metaversearapp.data.AppDatabase
import com.example.metaversearapp.ui.components.ARUiOverlay
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Earth
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.AdminPanelSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ARScreen(
    db: AppDatabase,
    viewModel: ARViewModel = viewModel(factory = ARViewModel.Factory(db)),
    onAdminRequest: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val modelLoader = rememberModelLoader(engine)

    // --- PERMISSIONS ---
    val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    var permissionsGranted by remember {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) launcher.launch(permissions)
    }

    // --- SAFETY & LIFECYCLE STATE ---
    var canRenderAR by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    delay(500)
                    if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED) {
                        canRenderAR = true
                    }
                }
            } else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                canRenderAR = false
                viewModel.isScanning = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            canRenderAR = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var roomAnchor by remember { mutableStateOf<Anchor?>(null) }
    var lastProcessingTime by remember { mutableLongStateOf(0L) }
    val earthRef = remember { mutableStateOf<Earth?>(null) }

    // --- DESTINATION PATH (room selector → A* arrows) ---
    // destPathProgress is a local copy of the ViewModel path that shrinks as
    // the user walks past each waypoint, advancing the visible arrow set.
    var destPathProgress by remember { mutableStateOf<List<NavNode>>(emptyList()) }
    var destArrowAnchors by remember { mutableStateOf<List<Anchor>>(emptyList()) }

    // Reset local progress whenever the ViewModel recomputes the path
    LaunchedEffect(viewModel.destinationPathNodes) {
        destPathProgress = viewModel.destinationPathNodes
    }

    // Re-trigger path computation the moment VPS locks (destination may have been
    // chosen before tracking was available, so the first compute() returned early)
    LaunchedEffect(viewModel.earthTrackingState, viewModel.selectedDestination) {
        if (viewModel.earthTrackingState == TrackingState.TRACKING &&
            viewModel.selectedDestination != null &&
            viewModel.destinationPathNodes.isEmpty()
        ) {
            viewModel.computeDestinationPath()
        }
    }

    // Build / rebuild destination arrow anchors whenever progress or earth changes
    LaunchedEffect(destPathProgress, earthRef.value, viewModel.earthTrackingState) {
        destArrowAnchors.forEach { it.detach() }
        destArrowAnchors = emptyList()
        val earth = earthRef.value ?: return@LaunchedEffect
        if (earth.trackingState != TrackingState.TRACKING) return@LaunchedEffect
        val path = destPathProgress
        if (path.size < 2) return@LaunchedEffect
        val floorAlt = viewModel.geospatialPose?.altitude?.minus(1.7) ?: return@LaunchedEffect

        destArrowAnchors = NavGraphPathfinder.interpolateArrows(path).mapNotNull { pt ->
            val q = NavGraphPathfinder.bearingToQuaternion(pt.bearing)
            try {
                earth.createAnchor(
                    pt.lat + viewModel.latOffset,
                    pt.lon + viewModel.lonOffset,
                    floorAlt,
                    q[0], q[1], q[2], q[3]
                )
            } catch (_: Exception) { null }
        }
    }

    // --- WAYPOINT PIN ANCHORS ---
    var startPinAnchor   by remember { mutableStateOf<Anchor?>(null) }
    var endPinAnchor     by remember { mutableStateOf<Anchor?>(null) }
    var testCrumbAnchors by remember { mutableStateOf<List<Anchor>>(emptyList()) }

    // Create / update start pin anchor whenever the pin or earth changes
    LaunchedEffect(viewModel.startPin, earthRef.value, viewModel.earthTrackingState) {
        startPinAnchor?.detach()
        startPinAnchor = null
        val earth = earthRef.value ?: return@LaunchedEffect
        if (earth.trackingState != TrackingState.TRACKING) return@LaunchedEffect
        val pin = viewModel.startPin ?: return@LaunchedEffect
        startPinAnchor = earth.createAnchor(pin.rawLat, pin.rawLon, pin.alt, 0f, 0f, 0f, 1f)
    }

    // Create / update end pin anchor
    LaunchedEffect(viewModel.endPin, earthRef.value, viewModel.earthTrackingState) {
        endPinAnchor?.detach()
        endPinAnchor = null
        val earth = earthRef.value ?: return@LaunchedEffect
        if (earth.trackingState != TrackingState.TRACKING) return@LaunchedEffect
        val pin = viewModel.endPin ?: return@LaunchedEffect
        endPinAnchor = earth.createAnchor(pin.rawLat, pin.rawLon, pin.alt, 0f, 0f, 0f, 1f)
    }

    // Create evenly-spaced directional arrow anchors along the A* test path
    LaunchedEffect(viewModel.testPathNodes, earthRef.value, viewModel.earthTrackingState) {
        testCrumbAnchors.forEach { it.detach() }
        testCrumbAnchors = emptyList()
        val earth = earthRef.value ?: return@LaunchedEffect
        if (earth.trackingState != TrackingState.TRACKING) return@LaunchedEffect
        val path = viewModel.testPathNodes
        if (path.size < 2) return@LaunchedEffect
        val floorAlt = viewModel.geospatialPose?.altitude?.minus(1.7) ?: return@LaunchedEffect

        testCrumbAnchors = NavGraphPathfinder.interpolateArrows(path).mapNotNull { pt ->
            val q = NavGraphPathfinder.bearingToQuaternion(pt.bearing)
            try {
                earth.createAnchor(
                    pt.lat + viewModel.latOffset,
                    pt.lon + viewModel.lonOffset,
                    floorAlt,
                    q[0], q[1], q[2], q[3]
                )
            } catch (_: Exception) { null }
        }
    }

    val scanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        BarcodeScanning.getClient(options)
    }

    DisposableEffect(scanner) {
        onDispose { scanner.close() }
    }

    // --- DESTINATION ANCHOR LOGIC ---
    LaunchedEffect(
        viewModel.selectedDestination,
        viewModel.isCalibrated,
        viewModel.latOffset,
        viewModel.lonOffset,
        viewModel.altOffset,
        viewModel.earthTrackingState,
        earthRef.value
    ) {
        val currentEarth = earthRef.value
        val dest = viewModel.selectedDestination
        if (currentEarth != null && viewModel.earthTrackingState == TrackingState.TRACKING && dest != null) {
            val floorAlt = (viewModel.geospatialPose?.altitude ?: (dest.alt + viewModel.altOffset)) - 1.7
            roomAnchor?.detach()
            roomAnchor = currentEarth.createAnchor(
                dest.lat + viewModel.latOffset,
                dest.lon + viewModel.lonOffset,
                floorAlt,
                0f, 0f, 0f, 1f
            )
        }
    }

    if (permissionsGranted) {
        Box(modifier = Modifier.fillMaxSize()) {

            // --- LAYER 1: AR SCENE ---
            if (canRenderAR) {
                ARScene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    sessionConfiguration = { session, config ->
                        config.geospatialMode = Config.GeospatialMode.ENABLED
                        config.focusMode = Config.FocusMode.AUTO
                        config.planeFindingMode = if (showDebug)
                            Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        else
                            Config.PlaneFindingMode.DISABLED
                        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    },
                    onSessionUpdated = { session, frame ->
                        val earth = session.earth
                        earthRef.value = earth
                        viewModel.updateGeospatialState(earth)

                        if (earth != null && earth.trackingState == TrackingState.TRACKING) {
                            val pose = earth.cameraGeospatialPose

                            // Advance destination path as the user walks past each node.
                            // Once within 3 m of the NEXT node, drop the current leading
                            // node so the first arrow disappears behind the user.
                            if (destPathProgress.size > 1) {
                                val nextNode = destPathProgress[1]
                                val dist = NavGraphPathfinder.haversine(
                                    pose.latitude  - viewModel.latOffset,
                                    pose.longitude - viewModel.lonOffset,
                                    nextNode.lat, nextNode.lon
                                )
                                if (dist < 3.0) {
                                    destPathProgress = destPathProgress.drop(1)
                                }
                            }

                            val currentTime = System.currentTimeMillis()
                            if (viewModel.isScanning && (currentTime - lastProcessingTime > 1000)) {
                                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    try {
                                        val image = frame.acquireCameraImage()
                                        lastProcessingTime = currentTime

                                        try {
                                            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                context.display?.rotation ?: Surface.ROTATION_0
                                            } else {
                                                @Suppress("DEPRECATION")
                                                (context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                                            }

                                            val inputImage = InputImage.fromMediaImage(image, when (rotation) {
                                                Surface.ROTATION_90 -> 0; Surface.ROTATION_180 -> 270; Surface.ROTATION_270 -> 180; else -> 90
                                            })

                                            scanner.process(inputImage)
                                                .addOnSuccessListener { barcodes ->
                                                    if (barcodes.isNotEmpty()) {
                                                        val id = barcodes[0].rawValue ?: return@addOnSuccessListener
                                                        viewModel.onQrScanned(id, pose)
                                                    }
                                                }
                                                .addOnCompleteListener {
                                                    try { image.close() } catch (_: Exception) {}
                                                }
                                        } catch (e: Exception) {
                                            image.close()
                                            throw e
                                        }
                                    } catch (e: Exception) {
                                        // Silent catch for ARCore buffer/session exceptions
                                    }
                                }
                            }
                        }
                    }
                ) {
                    // ── Materials ─────────────────────────────────────────────
                    val yellowMaterial = remember(materialLoader) {
                        materialLoader.createColorInstance(
                            color = SceneViewColor(1.0f, 0.9f, 0.0f, 1.0f),
                            metallic = 0.0f, roughness = 1.0f, reflectance = 0.5f
                        )
                    }
                    val greenPinMaterial = remember(materialLoader) {
                        materialLoader.createColorInstance(
                            color = SceneViewColor(0.15f, 0.85f, 0.25f, 1.0f),
                            metallic = 0.0f, roughness = 0.7f, reflectance = 0.4f
                        )
                    }
                    val redPinMaterial = remember(materialLoader) {
                        materialLoader.createColorInstance(
                            color = SceneViewColor(0.95f, 0.18f, 0.1f, 1.0f),
                            metallic = 0.0f, roughness = 0.7f, reflectance = 0.4f
                        )
                    }
                    val cyanWaypointMaterial = remember(materialLoader) {
                        materialLoader.createColorInstance(
                            color = SceneViewColor(0.0f, 0.9f, 0.85f, 0.95f),
                            metallic = 0.0f, roughness = 0.6f, reflectance = 0.5f
                        )
                    }
                    // Amber arrows for the room-destination path
                    val amberDestMaterial = remember(materialLoader) {
                        materialLoader.createColorInstance(
                            color = SceneViewColor(1.0f, 0.7f, 0.0f, 0.95f),
                            metallic = 0.0f, roughness = 0.6f, reflectance = 0.5f
                        )
                    }

                    // ── Destination marker (yellow) ────────────────────────────
                    roomAnchor?.let { anchor ->
                        AnchorNode(anchor = anchor) {
                            CubeNode(
                                size = Float3(0.5f, 3.5f, 0.5f),
                                center = Position(0f, 2.75f, 0f),
                                materialInstance = yellowMaterial
                            )
                            CubeNode(
                                size = Float3(0.5f, 0.5f, 0.5f),
                                center = Position(0f, 0.25f, 0f),
                                materialInstance = yellowMaterial
                            )
                        }
                    }

                    // ── Start pin (green) ──────────────────────────────────────
                    startPinAnchor?.let { anchor ->
                        AnchorNode(anchor = anchor) {
                            // Stem
                            CubeNode(
                                size = Float3(0.12f, 1.8f, 0.12f),
                                center = Position(0f, 0.9f, 0f),
                                materialInstance = greenPinMaterial
                            )
                            // Head
                            CubeNode(
                                size = Float3(0.32f, 0.32f, 0.32f),
                                center = Position(0f, 1.96f, 0f),
                                materialInstance = greenPinMaterial
                            )
                        }
                    }

                    // ── End pin (red) ──────────────────────────────────────────
                    endPinAnchor?.let { anchor ->
                        AnchorNode(anchor = anchor) {
                            // Stem
                            CubeNode(
                                size = Float3(0.12f, 1.8f, 0.12f),
                                center = Position(0f, 0.9f, 0f),
                                materialInstance = redPinMaterial
                            )
                            // Head
                            CubeNode(
                                size = Float3(0.32f, 0.32f, 0.32f),
                                center = Position(0f, 1.96f, 0f),
                                materialInstance = redPinMaterial
                            )
                        }
                    }

                    // ── A* test-path arrows (cyan) ────────────────────────────
                    // Stepped "▶" arrowhead — four axis-aligned cubes.
                    // 20 cm tall so they're visible when looking forward, not just
                    // straight down.  +Z faces the next node (baked into the anchor
                    // quaternion).
                    //   Shaft:     Z  –0.25 →  0.15   (40 cm)
                    //   Wide base: Z   0.15 →  0.25   (10 cm, 35 cm wide)
                    //   Medium:    Z   0.25 →  0.35   (10 cm, 22 cm wide)
                    //   Tip:       Z   0.35 →  0.45   (10 cm,  8 cm wide)
                    testCrumbAnchors.forEach { anchor ->
                        AnchorNode(anchor = anchor) {
                            CubeNode(
                                size   = Float3(0.08f, 0.20f, 0.40f),
                                center = Position(0f, 0.10f, -0.05f),
                                materialInstance = cyanWaypointMaterial
                            )
                            CubeNode(
                                size   = Float3(0.35f, 0.20f, 0.10f),
                                center = Position(0f, 0.10f, 0.20f),
                                materialInstance = cyanWaypointMaterial
                            )
                            CubeNode(
                                size   = Float3(0.22f, 0.20f, 0.10f),
                                center = Position(0f, 0.10f, 0.30f),
                                materialInstance = cyanWaypointMaterial
                            )
                            CubeNode(
                                size   = Float3(0.08f, 0.20f, 0.10f),
                                center = Position(0f, 0.10f, 0.40f),
                                materialInstance = cyanWaypointMaterial
                            )
                        }
                    }

                    // ── Room-destination path arrows (amber) ─────────────────────
                    // Same stepped "▶" shape; amber distinguishes destination-nav
                    // from test-pin arrows (cyan).
                    destArrowAnchors.forEach { anchor ->
                        AnchorNode(anchor = anchor) {
                            CubeNode(
                                size   = Float3(0.08f, 0.20f, 0.40f),
                                center = Position(0f, 0.10f, -0.05f),
                                materialInstance = amberDestMaterial
                            )
                            CubeNode(
                                size   = Float3(0.35f, 0.20f, 0.10f),
                                center = Position(0f, 0.10f, 0.20f),
                                materialInstance = amberDestMaterial
                            )
                            CubeNode(
                                size   = Float3(0.22f, 0.20f, 0.10f),
                                center = Position(0f, 0.10f, 0.30f),
                                materialInstance = amberDestMaterial
                            )
                            CubeNode(
                                size   = Float3(0.08f, 0.20f, 0.10f),
                                center = Position(0f, 0.10f, 0.40f),
                                materialInstance = amberDestMaterial
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ComposeColor.Black),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = ComposeColor.Cyan)
                }
            }

            // --- LAYER 2: UI OVERLAYS ---
            ARUiOverlay(viewModel, showDebug = showDebug)

            // Admin + debug toggle buttons
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 2.dp,
                        end = 8.dp
                    ),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Debug toggle
                IconButton(onClick = { showDebug = !showDebug }) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = "Toggle Debug",
                        tint = if (showDebug)
                            ComposeColor(0xFFFFD700)   // gold when active
                        else
                            ComposeColor.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(26.dp)
                    )
                }
                // Admin
                IconButton(onClick = onAdminRequest) {
                    Icon(
                        Icons.Default.AdminPanelSettings,
                        contentDescription = "Admin",
                        tint = ComposeColor.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Destination path progress chip
            if (destPathProgress.size > 1) {
                val remaining = destPathProgress.size - 1
                Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)) {
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        colors   = CardDefaults.cardColors(
                            containerColor = ComposeColor(0xFF2A1A00).copy(alpha = 0.9f)
                        )
                    ) {
                        Text(
                            "🟡 Route: $remaining waypoints remaining",
                            modifier = Modifier.padding(10.dp),
                            color    = ComposeColor(0xFFFFCC02),
                            style    = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera and Location Permissions Required")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { launcher.launch(permissions) }) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}
