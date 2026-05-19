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
    var lastPathDropTime   by remember { mutableLongStateOf(0L) }
    val earthRef   = remember { mutableStateOf<Earth?>(null) }
    val sessionRef = remember { mutableStateOf<Session?>(null) }
    val resolvedAnchorIds = remember { mutableSetOf<String>() }

    // --- DESTINATION PATH (room selector → A* arrows) ---
    // destPathProgress is a local copy of the ViewModel path that shrinks as
    // the user walks past each waypoint, advancing the visible arrow set.
    var destPathProgress by remember { mutableStateOf<List<NavNode>>(emptyList()) }
    // Each entry pairs an ARCore anchor with its geographic position so individual
    // arrows can be detached by proximity rather than bulk-wiping the whole list.
    var destArrows by remember {
        mutableStateOf<List<Pair<Anchor, NavGraphPathfinder.ArrowPoint>>>(emptyList())
    }

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

    // --- CLOUD ANCHOR RESOLUTION ---
    // When VPS is tracking, find the nearest unresolved cloud-anchor node within 15 m
    // and resolve it. A successful resolve calibrates the lat/lon offsets via the
    // geospatial pose of the resolved anchor (cm-accurate, unlike GPS).
    //
    // earthRef.value is intentionally NOT a key here: session.earth returns a new
    // Java wrapper object on every ARCore frame, which would fire a state change
    // 60×/s and restart this effect continuously.  earthTrackingState covers the
    // only transitions we care about (STOPPED → TRACKING), and earthRef.value is
    // read inside the body where it is always populated by the time tracking is active.
    LaunchedEffect(
        viewModel.earthTrackingState,
        viewModel.navNodes,
        viewModel.isResolvingCloudAnchor,
        sessionRef.value
    ) {
        if (viewModel.earthTrackingState != TrackingState.TRACKING) return@LaunchedEffect
        if (viewModel.isResolvingCloudAnchor) return@LaunchedEffect
        val earth   = earthRef.value ?: return@LaunchedEffect
        val session = sessionRef.value ?: return@LaunchedEffect

        val userPose = earth.cameraGeospatialPose
        val candidate = viewModel.navNodes
            .filter { it.cloudAnchorId != null && it.cloudAnchorId !in resolvedAnchorIds }
            .minByOrNull {
                NavGraphPathfinder.haversine(userPose.latitude, userPose.longitude, it.lat, it.lon)
            } ?: return@LaunchedEffect

        val dist = NavGraphPathfinder.haversine(
            userPose.latitude, userPose.longitude, candidate.lat, candidate.lon
        )
        if (dist > 15.0) return@LaunchedEffect

        // Mark as in-flight immediately to prevent duplicate resolves
        viewModel.isResolvingCloudAnchor = true
        resolvedAnchorIds.add(candidate.cloudAnchorId!!)

        session.resolveCloudAnchorAsync(candidate.cloudAnchorId) { anchor, state ->
            // This callback runs on the GL rendering thread.  All Compose state
            // mutations and non-thread-safe collections (resolvedAnchorIds) must
            // run on the main thread — use scope.launch for that.
            // ARCore anchor/earth operations (getGeospatialPose, detach) are safe
            // on the GL thread and must happen here before the anchor is invalidated.
            if (state == CloudAnchorState.SUCCESS) {
                val geoPose = earth.getGeospatialPose(anchor.pose)
                anchor.detach()
                scope.launch {   // → main thread
                    viewModel.onCloudAnchorResolved(
                        geoPose,
                        candidate.lat,
                        candidate.lon,
                        candidate.cloudAnchorHeading,
                        candidate.cloudAnchorId!!
                    )
                }
            } else {
                // Allow retry on failure
                scope.launch {   // → main thread
                    resolvedAnchorIds.remove(candidate.cloudAnchorId)
                    viewModel.isResolvingCloudAnchor = false
                }
            }
        }
    }

    // Full rebuild whenever the path, tracking state, or local AR reference changes.
    // Individual arrow cleanup is handled per-proximity in onSessionUpdated.
    //
    // earthRef.value is intentionally NOT a key: see the cloud-anchor LaunchedEffect
    // above for a full explanation.  earthRef is read inside the body only.
    LaunchedEffect(viewModel.destinationPathNodes, viewModel.earthTrackingState, viewModel.localArRef, sessionRef.value) {
        val earth    = earthRef.value
        val path     = viewModel.destinationPathNodes
        val localRef = viewModel.localArRef
        val session  = sessionRef.value

        destArrows.forEach { (anchor, _) -> anchor.detach() }
        destArrows = emptyList()
        if (path.size < 2) return@LaunchedEffect

        destArrows = if (localRef != null && session != null) {
            NavGraphPathfinder.interpolateArrows(path).mapNotNull { pt ->
                createLocalArrowAnchor(session, localRef, pt.lat, pt.lon, pt.alt, pt.bearing)
                    ?.let { Pair(it, pt) }
            }
        } else {
            if (earth == null || earth.trackingState != TrackingState.TRACKING) return@LaunchedEffect
            NavGraphPathfinder.interpolateArrows(path).mapNotNull { pt ->
                // Apply headingOffset so VPS compass drift is corrected even on the
                // earth-anchor path (the localArRef path already has this baked in).
                val correctedBearing = pt.bearing + viewModel.headingOffset
                val q = NavGraphPathfinder.bearingToQuaternion(correctedBearing)
                try {
                    val anchor = earth.createAnchor(
                        pt.lat + viewModel.latOffset,
                        pt.lon + viewModel.lonOffset,
                        pt.alt + viewModel.altOffset - 1.7,
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
        if (path.size < 2) return@LaunchedEffect

        testCrumbAnchors = if (localRef != null && session != null) {
            NavGraphPathfinder.interpolateArrows(path).mapNotNull { pt ->
                createLocalArrowAnchor(session, localRef, pt.lat, pt.lon, pt.alt, pt.bearing)
            }
        } else {
            val earth = earthRef.value ?: return@LaunchedEffect
            if (earth.trackingState != TrackingState.TRACKING) return@LaunchedEffect
            NavGraphPathfinder.interpolateArrows(path).mapNotNull { pt ->
                val correctedBearing = pt.bearing + viewModel.headingOffset
                val q = NavGraphPathfinder.bearingToQuaternion(correctedBearing)
                try {
                    earth.createAnchor(
                        pt.lat + viewModel.latOffset,
                        pt.lon + viewModel.lonOffset,
                        pt.alt + viewModel.altOffset - 1.7,
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
                        sessionRef.value = session

                        viewModel.updateGeospatialState(earth)

                        if (earth != null && earth.trackingState == TrackingState.TRACKING) {
                            val pose = earth.cameraGeospatialPose

                            val now    = System.currentTimeMillis()
                            val userLat = pose.latitude  - viewModel.latOffset
                            val userLon = pose.longitude - viewModel.lonOffset

                            // ── Advance nav-node path for HUD bearing + arrival check ──
                            // Throttled to once per 800 ms so we never drop more than one
                            // node per frame and accidentally trigger a full anchor rebuild.
                            if (destPathProgress.size > 1 && (now - lastPathDropTime) > 800L) {
                                val nextNode = destPathProgress[1]
                                val dist = NavGraphPathfinder.haversine(
                                    userLat, userLon, nextNode.lat, nextNode.lon
                                )
                                if (dist < 3.0) {
                                    destPathProgress = destPathProgress.drop(1)
                                    lastPathDropTime = now
                                }
                            }

                            // ── Per-arrow proximity pruning ────────────────────────────
                            // Detach the single nearest arrow within 2.5 m so arrows
                            // disappear one at a time as the user walks through them.
                            //
                            // THREADING NOTE: onSessionUpdated runs on the GL thread.
                            // destArrows is also mutated by the main-thread arrow-rebuild
                            // LaunchedEffect.  To prevent a race:
                            //  1. Take a snapshot of the list on the GL thread.
                            //  2. Detach the anchor immediately (safe on GL thread).
                            //  3. Dispatch the list mutation to the main thread via
                            //     scope.launch, removing by object reference rather than
                            //     index so a concurrent rebuild never causes an
                            //     IndexOutOfBoundsException or stale removal.
                            val arrowsSnapshot = destArrows
                            if (arrowsSnapshot.isNotEmpty()) {
                                val closest = arrowsSnapshot.indexOfFirst { (_, pt) ->
                                    NavGraphPathfinder.haversine(userLat, userLon, pt.lat, pt.lon) < 2.5
                                }
                                if (closest >= 0) {
                                    val pairToRemove = arrowsSnapshot[closest]
                                    pairToRemove.first.detach()
                                    scope.launch {   // → main thread
                                        // Filter by reference: safe even if a rebuild has
                                        // already replaced the list with new Pair objects.
                                        destArrows = destArrows.filter { it !== pairToRemove }
                                    }
                                }
                            }

                            // Arrival check — fire once when the user is within 3 m of
                            // the final destination node and the banner isn't already showing.
                            if (!viewModel.showArrivalBanner &&
                                destPathProgress.size == 1 &&
                                viewModel.selectedDestination != null
                            ) {
                                val finalNode = destPathProgress[0]
                                val dist = NavGraphPathfinder.haversine(
                                    pose.latitude  - viewModel.latOffset,
                                    pose.longitude - viewModel.lonOffset,
                                    finalNode.lat, finalNode.lon
                                )
                                if (dist < 3.0) {
                                    viewModel.onArrived(viewModel.selectedDestination!!.name)
                                    destPathProgress = emptyList()
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
                    destArrows.forEach { (anchor, _) ->
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
            val nextWaypointBearing: Double? = if (destPathProgress.size >= 2) {
                viewModel.geospatialPose?.let { pose ->
                    val next = destPathProgress[1]
                    val dLon = Math.toRadians(next.lon + viewModel.lonOffset - pose.longitude)
                    val lat1 = Math.toRadians(pose.latitude)
                    val lat2 = Math.toRadians(next.lat + viewModel.latOffset)
                    val y    = sin(dLon) * cos(lat2)
                    val x    = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
                    (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
                }
            } else null

            ARUiOverlay(
                viewModel            = viewModel,
                showDebug            = showDebug,
                remainingWaypoints   = if (destPathProgress.size > 1) destPathProgress.size - 1 else 0,
                nextWaypointBearing  = nextWaypointBearing
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
