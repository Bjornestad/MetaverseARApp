package com.example.metaversearapp.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
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
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.romainguy.kotlin.math.Float3
import kotlin.math.*
import io.github.sceneview.ar.ARSceneView
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
import androidx.compose.material.icons.filled.LocationOff
import com.google.ar.core.Anchor.CloudAnchorState
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

    // --- GPS ENABLED CHECK ---
    // VPS requires the device location provider to be active, not just the
    // permission.  We check on entry and listen for the provider toggling
    // so the prompt disappears the moment the user enables GPS.
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var isGpsEnabled by remember {
        mutableStateOf(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                    isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        onDispose { context.unregisterReceiver(receiver) }
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
    var lastRerouteTime    by remember { mutableLongStateOf(0L) }
    val earthRef   = remember { mutableStateOf<Earth?>(null) }
    val sessionRef = remember { mutableStateOf<Session?>(null) }
    val resolvedAnchorIds = remember { mutableSetOf<String>() }

    // Each entry pairs an ARCore anchor with its geographic position so individual
    // arrows can be detached by proximity rather than bulk-wiping the whole list.
    var destArrows by remember {
        mutableStateOf<List<Pair<Anchor, NavGraphPathfinder.ArrowPoint>>>(emptyList())
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

    // --- CLOUD ANCHOR RESOLUTION ---
    // Polls every 3 s while VPS is tracking. Each poll finds the nearest
    // unresolved cloud-anchor node within 15 m and resolves it. On success,
    // onCloudAnchorResolved rebuilds localArRef (same path as a QR scan),
    // giving cm-accurate arrow placement for the next corridor segment.
    // The next poll then picks up the following anchor along the route —
    // progressive re-calibration as the user walks past each hosted anchor.
    //
    // earthRef is read inside the loop (not a key) because session.earth
    // returns a new Java wrapper each frame and would restart the effect 60×/s.
    LaunchedEffect(
        viewModel.earthTrackingState,
        viewModel.navNodes,
        sessionRef.value
    ) {
        if (viewModel.earthTrackingState != TrackingState.TRACKING) return@LaunchedEffect
        val session = sessionRef.value ?: return@LaunchedEffect

        while (true) {
            delay(3_000L)
            if (viewModel.isResolvingCloudAnchor) continue
            val earth = earthRef.value ?: continue

            val userPose = earth.cameraGeospatialPose
            val candidate = viewModel.navNodes
                .filter { it.cloudAnchorId != null && it.cloudAnchorId !in resolvedAnchorIds }
                .minByOrNull {
                    NavGraphPathfinder.haversine(userPose.latitude, userPose.longitude, it.lat, it.lon)
                } ?: continue

            val dist = NavGraphPathfinder.haversine(
                userPose.latitude, userPose.longitude, candidate.lat, candidate.lon
            )
            if (dist > 15.0) continue

            // Mark in-flight before the async call to prevent duplicate resolves
            // across poll ticks.
            viewModel.isResolvingCloudAnchor = true
            resolvedAnchorIds.add(candidate.cloudAnchorId!!)

            session.resolveCloudAnchorAsync(candidate.cloudAnchorId) { anchor, state ->
                // Callback runs on the GL thread. ARCore operations (getGeospatialPose,
                // pose reads, detach) must happen here before the anchor is invalidated.
                // Compose state mutations go via scope.launch → main thread.
                if (state == CloudAnchorState.SUCCESS) {
                    val geoPose  = earth.getGeospatialPose(anchor.pose)
                    val anchorTx = anchor.pose.tx()
                    val anchorTy = anchor.pose.ty()
                    val anchorTz = anchor.pose.tz()
                    val anchorQx = anchor.pose.qx()
                    val anchorQy = anchor.pose.qy()
                    val anchorQz = anchor.pose.qz()
                    val anchorQw = anchor.pose.qw()
                    anchor.detach()
                    scope.launch {
                        viewModel.onCloudAnchorResolved(
                            geoPose,
                            anchorTx, anchorTy, anchorTz,
                            anchorQx, anchorQy, anchorQz, anchorQw,
                            candidate.lat,
                            candidate.lon,
                            candidate.alt,
                            candidate.cloudAnchorHeading,
                            candidate.cloudAnchorId!!
                        )
                    }
                } else {
                    // Failed — allow retry on next poll
                    scope.launch {
                        resolvedAnchorIds.remove(candidate.cloudAnchorId)
                        viewModel.isResolvingCloudAnchor = false
                    }
                }
            }
        }
    }

    // Full rebuild whenever the path, tracking state, calibration offsets, or local AR
    // reference changes.  Individual arrow cleanup is handled per-proximity in onSessionUpdated.
    //
    // earthRef.value is intentionally NOT a key: session.earth returns a new Java wrapper
    // each ARCore frame, which would restart this effect 60×/s.  earthTrackingState +
    // isCalibrated cover all the transitions we actually care about.
    LaunchedEffect(
        viewModel.destinationPathNodes,
        viewModel.earthTrackingState,
        viewModel.localArRef,
        viewModel.isCalibrated,
        viewModel.latOffset,
        viewModel.lonOffset,
        sessionRef.value
    ) {
        val currentPath = viewModel.destinationPathNodes
        val localRef    = viewModel.localArRef
        val session     = sessionRef.value
        val earth       = earthRef.value

        destArrows.forEach { (anchor, _) -> anchor.detach() }
        destArrows = emptyList()
        if (currentPath.size < 2 || session == null) return@LaunchedEffect

        destArrows = if (localRef != null) {
            NavGraphPathfinder.interpolateArrows(currentPath).mapNotNull { pt ->
                createLocalArrowAnchor(session, localRef, pt.lat, pt.lon, pt.alt, pt.bearing)
                    ?.let { Pair(it, pt) }
            }
        } else {
            if (earth == null || earth.trackingState != TrackingState.TRACKING) return@LaunchedEffect
            // When not yet calibrated, estimate altOffset from the camera's current altitude
            // so arrows appear at a reasonable height even before the first QR/anchor scan.
            val effectiveAltOffset = if (viewModel.isCalibrated) {
                viewModel.altOffset
            } else {
                earth.cameraGeospatialPose.altitude - currentPath.first().alt
            }
            NavGraphPathfinder.interpolateArrows(currentPath).mapNotNull { pt ->
                val correctedBearing = pt.bearing - viewModel.headingOffset
                val q = NavGraphPathfinder.bearingToQuaternion(correctedBearing)
                try {
                    val anchor = earth.createAnchor(
                        pt.lat + viewModel.latOffset,
                        pt.lon + viewModel.lonOffset,
                        pt.alt + effectiveAltOffset - 1.7,
                        q[0], q[1], q[2], q[3]
                    )
                    Pair(anchor, pt)
                } catch (_: Exception) { null }
            }
        }
    }

    // --- WAYPOINT PIN ANCHORS ---
    var startPinAnchor   by remember { mutableStateOf<Anchor?>(null) }
    var endPinAnchor     by remember { mutableStateOf<Anchor?>(null) }
    var testCrumbAnchors by remember { mutableStateOf<List<Anchor>>(emptyList()) }

    // Create / update start pin anchor whenever the pin or tracking state changes
    LaunchedEffect(viewModel.startPin, viewModel.earthTrackingState) {
        startPinAnchor?.detach()
        startPinAnchor = null
        val earth = earthRef.value ?: return@LaunchedEffect
        if (earth.trackingState != TrackingState.TRACKING) return@LaunchedEffect
        val pin = viewModel.startPin ?: return@LaunchedEffect
        startPinAnchor = earth.createAnchor(pin.rawLat, pin.rawLon, pin.alt, 0f, 0f, 0f, 1f)
    }

    // Create / update end pin anchor
    LaunchedEffect(viewModel.endPin, viewModel.earthTrackingState) {
        endPinAnchor?.detach()
        endPinAnchor = null
        val earth = earthRef.value ?: return@LaunchedEffect
        if (earth.trackingState != TrackingState.TRACKING) return@LaunchedEffect
        val pin = viewModel.endPin ?: return@LaunchedEffect
        endPinAnchor = earth.createAnchor(pin.rawLat, pin.rawLon, pin.alt, 0f, 0f, 0f, 1f)
    }

    // Create evenly-spaced directional arrow anchors along the A* test path
    LaunchedEffect(viewModel.testPathNodes, viewModel.earthTrackingState, viewModel.localArRef, sessionRef.value) {
        testCrumbAnchors.forEach { it.detach() }
        testCrumbAnchors = emptyList()
        val path     = viewModel.testPathNodes
        val localRef = viewModel.localArRef
        val session  = sessionRef.value
        if (path.size < 2 || session == null) return@LaunchedEffect

        testCrumbAnchors = if (localRef != null) {
            NavGraphPathfinder.interpolateArrows(path).mapNotNull { pt ->
                createLocalArrowAnchor(session, localRef, pt.lat, pt.lon, pt.alt, pt.bearing)
            }
        } else {
            val earth = earthRef.value ?: return@LaunchedEffect
            if (earth.trackingState != TrackingState.TRACKING) return@LaunchedEffect
            val effectiveAltOffset = if (viewModel.isCalibrated) {
                viewModel.altOffset
            } else {
                earth.cameraGeospatialPose.altitude - path.first().alt
            }
            NavGraphPathfinder.interpolateArrows(path).mapNotNull { pt ->
                val correctedBearing = pt.bearing - viewModel.headingOffset
                val q = NavGraphPathfinder.bearingToQuaternion(correctedBearing)
                try {
                    earth.createAnchor(
                        pt.lat + viewModel.latOffset,
                        pt.lon + viewModel.lonOffset,
                        pt.alt + effectiveAltOffset - 1.7,
                        q[0], q[1], q[2], q[3]
                    )
                } catch (_: Exception) { null }
            }
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
        viewModel.localArRef,
        sessionRef.value
    ) {
        val dest     = viewModel.selectedDestination ?: return@LaunchedEffect
        val localRef = viewModel.localArRef
        val session  = sessionRef.value
        roomAnchor?.detach()
        roomAnchor = if (localRef != null && session != null) {
            createLocalRoomAnchor(session, localRef, dest.lat, dest.lon, dest.alt)
        } else {
            val currentEarth = earthRef.value ?: return@LaunchedEffect
            if (viewModel.earthTrackingState != TrackingState.TRACKING) return@LaunchedEffect
            val floorAlt = (viewModel.geospatialPose?.altitude ?: (dest.alt + viewModel.altOffset)) - 1.7
            currentEarth.createAnchor(
                dest.lat + viewModel.latOffset,
                dest.lon + viewModel.lonOffset,
                floorAlt,
                0f, 0f, 0f, 1f
            )
        }
    }

    if (permissionsGranted && !isGpsEnabled) {
        GpsRequiredScreen(onOpenSettings = {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        })
        return
    }

    if (permissionsGranted) {
        Box(modifier = Modifier.fillMaxSize()) {

            // --- LAYER 1: AR SCENE ---
            if (canRenderAR) {
                ARSceneView(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    sessionConfiguration = { session, config ->
                        config.geospatialMode  = Config.GeospatialMode.ENABLED
                        config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                        config.focusMode       = Config.FocusMode.AUTO
                        config.planeFindingMode = if (showDebug)
                            Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        else
                            Config.PlaneFindingMode.DISABLED
                        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    },
                    onSessionUpdated = { session, frame ->
                        val earth = session.earth
                        earthRef.value = earth
                        // Only write once (null → Session). session.earth returns a new wrapper
                        // each frame (see earthRef comment above), and the SceneView library may
                        // do the same for the Session itself.  Writing every frame would fire a
                        // state change 60×/s and restart every LaunchedEffect that uses
                        // sessionRef.value as a key, clearing destArrows before they can render.
                        if (sessionRef.value == null) sessionRef.value = session

                        viewModel.updateGeospatialState(earth)

                        if (earth != null && earth.trackingState == TrackingState.TRACKING) {
                            val pose = earth.cameraGeospatialPose

                            val now     = System.currentTimeMillis()
                            val userLat = pose.latitude  - viewModel.latOffset
                            val userLon = pose.longitude - viewModel.lonOffset

                            // ── Per-arrow proximity pruning ────────────────────────────
                            // Snapshot on the GL thread; dispatch list mutation to main
                            // thread via scope.launch to avoid races with the arrow-rebuild
                            // LaunchedEffect (which also writes destArrows on the main thread).
                            val arrowsSnapshot = destArrows
                            if (arrowsSnapshot.isNotEmpty()) {
                                val closest = arrowsSnapshot.indexOfFirst { (_, pt) ->
                                    NavGraphPathfinder.haversine(userLat, userLon, pt.lat, pt.lon) < 2.5
                                }
                                if (closest >= 0) {
                                    val pairToRemove = arrowsSnapshot[closest]
                                    pairToRemove.first.detach()
                                    scope.launch {
                                        destArrows = destArrows.filter { it !== pairToRemove }
                                    }
                                }
                            }

                            // ── Off-path re-routing ──────────────────────────────────────
                            // 10 s cooldown; skip when near arrival (size == 1).
                            if (viewModel.selectedDestination != null &&
                                !viewModel.showArrivalBanner &&
                                viewModel.currentPathProgress.size > 1 &&
                                (now - lastRerouteTime) > 10_000L
                            ) {
                                val minDist = viewModel.currentPathProgress.minOf { node ->
                                    NavGraphPathfinder.haversine(userLat, userLon, node.lat, node.lon)
                                }
                                if (minDist > 8.0) {
                                    lastRerouteTime = now
                                    scope.launch { viewModel.requestReroute() }
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
                                                        val cp = frame.camera.pose
                                                        viewModel.onQrScanned(id, pose, cp.tx(), cp.ty(), cp.tz(), cp.qx(), cp.qy(), cp.qz(), cp.qw())
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
                        key(anchor) {
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
                    }

                    // ── Room-destination path arrows (amber) ─────────────────────
                    destArrows.forEach { (anchor, _) ->
                        key(anchor) {
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
            ARUiOverlay(
                viewModel           = viewModel,
                showDebug           = showDebug,
                remainingWaypoints  = if (viewModel.currentPathProgress.size > 1) viewModel.currentPathProgress.size - 1 else 0,
                remainingPath       = viewModel.currentPathProgress
            )

            // Debug + Admin buttons — bottom-right, above the control card
            Column(
                modifier              = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 80.dp, end = 8.dp),
                verticalArrangement   = Arrangement.spacedBy(0.dp),
                horizontalAlignment   = Alignment.End
            ) {
                // Admin — only visible when debug mode is on
                if (showDebug) {
                    IconButton(onClick = onAdminRequest) {
                        Icon(
                            Icons.Default.AdminPanelSettings,
                            contentDescription = "Admin",
                            tint     = ComposeColor.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                // Debug toggle — always visible
                IconButton(onClick = { showDebug = !showDebug }) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = "Toggle Debug",
                        tint     = if (showDebug) ComposeColor(0xFFFFD700)
                                   else ComposeColor.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(26.dp)
                    )
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

private fun createLocalArrowAnchor(
    session: Session,
    ref: ARViewModel.LocalArReference,
    lat: Double, lon: Double, alt: Double, bearingDeg: Double
): Anchor? {
    val dNorth = (lat - ref.refLat) * 111_320.0
    val dEast  = (lon - ref.refLon) * 111_320.0 * cos(Math.toRadians(ref.refLat))
    val eastX  = -ref.northZ;  val eastZ = ref.northX
    val px = ref.tx + (dNorth * ref.northX + dEast * eastX).toFloat()
    val py = ref.ty - 1.7f + (alt - ref.refAlt).toFloat()
    val pz = ref.tz + (dNorth * ref.northZ + dEast * eastZ).toFloat()
    val bRad = Math.toRadians(bearingDeg)
    val dirX = (cos(bRad) * ref.northX + sin(bRad) * eastX).toFloat()
    val dirZ = (cos(bRad) * ref.northZ + sin(bRad) * eastZ).toFloat()
    val phi  = atan2(dirX.toDouble(), dirZ.toDouble())
    return try {
        session.createAnchor(
            Pose(floatArrayOf(px, py, pz),
                 floatArrayOf(0f, sin(phi / 2).toFloat(), 0f, cos(phi / 2).toFloat()))
        )
    } catch (_: Exception) { null }
}

private fun createLocalRoomAnchor(
    session: Session,
    ref: ARViewModel.LocalArReference,
    lat: Double, lon: Double, alt: Double
): Anchor? {
    val dNorth = (lat - ref.refLat) * 111_320.0
    val dEast  = (lon - ref.refLon) * 111_320.0 * cos(Math.toRadians(ref.refLat))
    val eastX  = -ref.northZ;  val eastZ = ref.northX
    val px = ref.tx + (dNorth * ref.northX + dEast * eastX).toFloat()
    val py = ref.ty - 1.7f + (alt - ref.refAlt).toFloat()
    val pz = ref.tz + (dNorth * ref.northZ + dEast * eastZ).toFloat()
    return try {
        session.createAnchor(Pose(floatArrayOf(px, py, pz), floatArrayOf(0f, 0f, 0f, 1f)))
    } catch (_: Exception) { null }
}

@Composable
private fun GpsRequiredScreen(onOpenSettings: () -> Unit) {
    Box(
        modifier         = Modifier.fillMaxSize().background(ComposeColor(0xFF121212)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp).fillMaxWidth(),
            shape    = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors   = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1E1E1E))
        ) {
            Column(
                modifier            = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.LocationOff,
                    contentDescription = null,
                    tint               = ComposeColor(0xFFFF5252),
                    modifier           = Modifier.size(48.dp)
                )
                Text(
                    "GPS Required",
                    style = MaterialTheme.typography.titleLarge,
                    color = ComposeColor.White
                )
                Text(
                    "This app uses Visual Positioning (VPS) to navigate indoors. " +
                    "Please enable GPS / device location and return to the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ComposeColor.White.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Button(
                    onClick  = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF64FFDA))
                ) {
                    Text("Open Location Settings", color = ComposeColor.Black)
                }
            }
        }
    }
}
