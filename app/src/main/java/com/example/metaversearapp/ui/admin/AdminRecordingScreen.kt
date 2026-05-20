package com.example.metaversearapp.ui.admin

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.metaversearapp.data.AppDatabase
import com.example.metaversearapp.data.NavEdge
import com.example.metaversearapp.data.NavGraphPathfinder
import com.example.metaversearapp.ui.components.TiledMiniMap
import com.example.metaversearapp.data.NavNode
import com.example.metaversearapp.data.NodeType
import com.example.metaversearapp.data.QrLocation
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.Config
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Color as SceneViewColor
import io.github.sceneview.node.CubeNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import dev.romainguy.kotlin.math.Float3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun AdminRecordingScreen(
    db: AppDatabase,
    currentFloor: String,
    onFloorChange: (String) -> Unit,
    onFinished: () -> Unit
) {
    val scope          = rememberCoroutineScope()
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // AR engine
    val engine         = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val modelLoader    = rememberModelLoader(engine)

    // Lifecycle gate (same pattern as ARScreen)
    var canRenderAR           by remember { mutableStateOf(false) }
    var currentLifecycleState by remember { mutableStateOf(Lifecycle.State.INITIALIZED) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            currentLifecycleState = event.targetState
            when (event) {
                Lifecycle.Event.ON_RESUME -> scope.launch {
                    delay(500)
                    if (currentLifecycleState == Lifecycle.State.RESUMED) canRenderAR = true
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP  -> canRenderAR = false
                else                     -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { canRenderAR = false; lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ── VPS state ──────────────────────────────────────────────────────────────
    var geospatialPose     by remember { mutableStateOf<GeospatialPose?>(null) }
    var earthTrackingState by remember { mutableStateOf(TrackingState.STOPPED) }

    // ── Recording state ────────────────────────────────────────────────────────
    var isRecording      by remember { mutableStateOf(false) }
    var sessionNodes     by remember { mutableIntStateOf(0) }
    var sessionEdges     by remember { mutableIntStateOf(0) }
    var lastRecordedNode by remember { mutableStateOf<NavNode?>(null) }
    var lastCaptureTime  by remember { mutableLongStateOf(0L) }
    var statusMsg        by remember { mutableStateOf("Tap ▶ to record") }
    var lastNodeType     by remember { mutableStateOf(NodeType.WAYPOINT) }

    // Auto-bridging: snapshot of existing nodes taken at recording-segment start.
    // Duplicate edges are handled by Room's composite primary key (ON CONFLICT REPLACE)
    // so we don't need a separate bridgedNodeIds set — every nearby node gets a bridge
    // attempt on every new node, and the DB deduplicates silently.
    var preloadedNodes by remember { mutableStateOf<List<NavNode>>(emptyList()) }
    var preloadedEdges by remember { mutableStateOf<List<NavEdge>>(emptyList()) }

    // ── Cloud Anchor state ─────────────────────────────────────────────────────
    var arSession        by remember { mutableStateOf<Session?>(null) }
    var lastCameraPose   by remember { mutableStateOf<Pose?>(null) }
    var cloudHostState   by remember { mutableStateOf<HostState>(HostState.Idle) }
    // Hosted cloud anchor visuals — kept alive (not detached) so they render in the scene
    var cloudAnchorVisuals by remember { mutableStateOf<List<Anchor>>(emptyList()) }

    // ── Hardware compass heading (rotation-vector sensor, GPS-independent) ────────
    // Continuously updated so the door QR scan can snapshot the compass heading
    // the moment a QR is detected, storing the direction users face at that door.
    var sensorHeading by remember { mutableStateOf<Double?>(null) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rotMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                SensorManager.getOrientation(rotMatrix, orientation)
                // orientation[0] = azimuth in radians (-π … π), 0 = north
                sensorHeading = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(listener) }
    }

    // ── Door QR scan state ─────────────────────────────────────────────────────
    // showDoorQrPrompt: "Scan QR / Skip?" dialog shown after marking a door node.
    // isDoorScanActive: camera is actively looking for the door's QR code.
    var showDoorQrPrompt by remember { mutableStateOf(false) }
    var isDoorScanActive by remember { mutableStateOf(false) }
    var pendingDoorNode  by remember { mutableStateOf<NavNode?>(null) }
    var lastScanTime     by remember { mutableLongStateOf(0L) }

    val scanner = remember {
        val opts = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        BarcodeScanning.getClient(opts)
    }

    // ── DB actions ─────────────────────────────────────────────────────────────

    /**
     * Updates the last recorded node's type in the DB.
     * For STAIR_TOP / STAIR_BOTTOM: also scans for the complementary stair
     * node on a different floor within [STAIR_CONNECT_RADIUS_M] and creates
     * a bridging edge so A* can route between floors across separate walks.
     * For DOOR: surfaces [DoorLinkPickerDialog] when nearby QR anchors exist.
     */
    fun markLastNodeAs(type: NodeType) {
        val node = lastRecordedNode ?: run {
            statusMsg = "No waypoint yet — start recording first"
            return
        }
        scope.launch {
            val updated = node.copy(type = type)
            db.navDao().updateNode(updated)
            lastRecordedNode = updated
            lastNodeType     = type
            statusMsg = "Marked as ${type.name.replace('_', ' ').lowercase()
                .replaceFirstChar { it.uppercase() }}"

            // Auto-connect stair nodes:
            //  - STAIR_TOP / STAIR_BOTTOM link to each other across floors (existing behaviour)
            //  - STAIR_MIDDLE links to all adjacent stair-type nodes (top, mid, bottom)
            //    within 3-D STAIR_CONNECT_RADIUS_M, making dense mid-node chains that
            //    give the arrow interpolation enough points to follow the staircase.
            if (type == NodeType.STAIR_TOP || type == NodeType.STAIR_BOTTOM) {
                val complementType = if (type == NodeType.STAIR_TOP)
                    NodeType.STAIR_BOTTOM else NodeType.STAIR_TOP
                val candidates = db.navDao().getNodesByType(complementType.name)
                var connected  = 0
                for (candidate in candidates) {
                    val horizDist = NavGraphPathfinder.haversine(
                        node.lat, node.lon, candidate.lat, candidate.lon
                    )
                    if (horizDist <= STAIR_CONNECT_RADIUS_M && candidate.floor != node.floor) {
                        val weight = NavGraphPathfinder.distance3d(
                            node.lat, node.lon, node.alt,
                            candidate.lat, candidate.lon, candidate.alt
                        )
                        db.navDao().insertEdge(NavEdge(node.id, candidate.id, weight))
                        sessionEdges++
                        connected++
                    }
                }
                if (connected > 0) {
                    statusMsg = "Marked + linked to $connected stair(s) on other floor"
                }
            }

            if (type == NodeType.STAIR_MIDDLE) {
                // Connect this mid-point to any nearby stair node (top, mid, or bottom)
                // that was recorded in a previous session.  This stitches together
                // partial staircase recordings and gives the path enough 3-D waypoints
                // for arrows to track smoothly up/down the stairs.
                val stairTypes = listOf(
                    NodeType.STAIR_TOP.name,
                    NodeType.STAIR_MIDDLE.name,
                    NodeType.STAIR_BOTTOM.name
                )
                val candidates = stairTypes
                    .flatMap { db.navDao().getNodesByType(it) }
                    .distinctBy { it.id }
                    .filter { it.id != node.id }
                var connected = 0
                for (candidate in candidates) {
                    val dist3d = NavGraphPathfinder.distance3d(
                        node.lat, node.lon, node.alt,
                        candidate.lat, candidate.lon, candidate.alt
                    )
                    if (dist3d <= STAIR_CONNECT_RADIUS_M) {
                        db.navDao().insertEdge(NavEdge(node.id, candidate.id, dist3d))
                        sessionEdges++
                        connected++
                    }
                }
                if (connected > 0) {
                    statusMsg = "Stair Mid + linked to $connected nearby node(s)"
                }
            }

            if (type == NodeType.DOOR) {
                pendingDoorNode  = updated
                showDoorQrPrompt = true
                statusMsg        = "Door marked — link a QR code or skip"
            }
        }
    }

    /**
     * Recomputes the weight of every edge that touches [node], using the node's
     * current (possibly just-snapped) coordinates and the neighbour's stored coords.
     * Must be called after any operation that moves a node's lat/lon/alt in the DB.
     *
     * Uses haversine (horizontal only) for same-floor edges and distance3d only
     * for cross-floor stair edges (alt diff > 2 m).  GPS altitude indoors is
     * typically ±5–10 m noisy; including it in same-floor weights inflates them
     * by up to 10× and causes A* to route around valid corridors.
     */
    suspend fun reweightEdgesForNode(node: NavNode) {
        val edges = db.navDao().getEdgesForNode(node.id)
        for (edge in edges) {
            val neighbourId = if (edge.fromId == node.id) edge.toId else edge.fromId
            val neighbour   = db.navDao().getNodeById(neighbourId) ?: continue
            val altDiff     = kotlin.math.abs(node.alt - neighbour.alt)
            val newWeight   = if (altDiff > 2.0)
                NavGraphPathfinder.distance3d(node.lat, node.lon, node.alt, neighbour.lat, neighbour.lon, neighbour.alt)
            else
                NavGraphPathfinder.haversine(node.lat, node.lon, neighbour.lat, neighbour.lon)
            db.navDao().insertEdge(edge.copy(weight = newWeight))
        }
    }


    /**
     * Creates a local ARCore anchor at the current camera position and hosts it
     * as a Cloud Anchor with a 365-day TTL.  On success the ID is stored in the
     * last recorded node so it syncs to the Gist and is available for user-side
     * resolution.  Requires [CloudAnchorAuth.isConfigured] and an active session.
     */
    fun hostCloudAnchor() {
        val session = arSession ?: run {
            statusMsg = "AR session not ready — wait for tracking"
            return
        }
        val pose = lastCameraPose ?: run {
            statusMsg = "No camera pose yet — wait for VPS lock"
            return
        }
        if (cloudHostState == HostState.Hosting) return

        cloudHostState = HostState.Hosting
        statusMsg      = "Hosting cloud anchor…"

        // Snapshot heading now (before the async callback fires) so we capture
        // the direction the device was actually pointing when hosting.
        val headingAtHost = geospatialPose?.heading

        val anchor = session.createAnchor(pose)
        session.hostCloudAnchorAsync(anchor, 1) { cloudId, state ->
            when (state) {
                CloudAnchorState.SUCCESS -> {
                    cloudHostState     = HostState.Hosted(cloudId)
                    statusMsg          = "Hosted ✓  …${cloudId.takeLast(8)}"
                    // Keep anchor alive so it renders as a visual marker in the scene
                    cloudAnchorVisuals = cloudAnchorVisuals + anchor
                    lastRecordedNode?.let { node ->
                        scope.launch {
                            val updated = node.copy(
                                cloudAnchorId      = cloudId,
                                cloudAnchorHeading = headingAtHost
                            )
                            db.navDao().updateNode(updated)
                            lastRecordedNode = updated
                        }
                    }
                }
                else -> {
                    anchor.detach()
                    cloudHostState = HostState.Failed(state.name)
                    statusMsg      = "Hosting failed: ${state.name}"
                }
            }
        }
    }

    // ── UI ─────────────────────────────────────────────────────────────────────

    Box(modifier = Modifier.fillMaxSize()) {

        // AR background or loading spinner
        if (canRenderAR && currentLifecycleState == Lifecycle.State.RESUMED) {
            ARSceneView(
                modifier       = Modifier.fillMaxSize(),
                engine         = engine,
                modelLoader    = modelLoader,
                materialLoader = materialLoader,
                sessionConfiguration = { _, config ->
                    config.geospatialMode  = Config.GeospatialMode.ENABLED
                    config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                    config.focusMode       = Config.FocusMode.AUTO
                },
                onSessionUpdated = { session, frame ->
                    // Capture session + camera pose for cloud anchor hosting
                    arSession      = session
                    lastCameraPose = frame.camera.pose

                    val earth = session.earth

                    if (earth != null) {
                        earthTrackingState = earth.trackingState
                        if (earthTrackingState == TrackingState.TRACKING) {
                            val pose = earth.cameraGeospatialPose
                            geospatialPose = pose
                            val now = System.currentTimeMillis()

                    // ── Auto-node capture ─────────────────────────────────────
                    if (isRecording && now - lastCaptureTime > CAPTURE_INTERVAL_MS) {
                        val corrLat = pose.latitude
                        val corrLon = pose.longitude
                        val corrAlt = pose.altitude

                        // Distance from the last node placed in this session.
                        val distFromLast = lastRecordedNode?.let {
                            NavGraphPathfinder.distance3d(corrLat, corrLon, corrAlt, it.lat, it.lon, it.alt)
                        } ?: Double.MAX_VALUE

                        // Nearest pre-existing node (from a previous session) within
                        // the no-duplicate radius.  Altitude guard prevents matching
                        // vertically stacked corridors on different floors.
                        val nearExisting = preloadedNodes.firstOrNull { existing ->
                            existing.id != lastRecordedNode?.id &&
                            kotlin.math.abs(existing.alt - corrAlt) <= 1.5 &&
                            NavGraphPathfinder.haversine(
                                corrLat, corrLon, existing.lat, existing.lon
                            ) < MIN_NODE_DISTANCE_M
                        }

                        if (distFromLast >= MIN_NODE_DISTANCE_M) {
                            if (nearExisting != null) {
                                // ── Merge: already covered by a previous session ─────
                                // Don't create a duplicate node.  Adopt the existing
                                // node as the current chain anchor so that when the
                                // path later enters new territory the new node will
                                // bridge back to the existing graph correctly.
                                val prevNode = lastRecordedNode
                                lastRecordedNode = nearExisting
                                lastCaptureTime  = now
                                if (prevNode != null) {
                                    scope.launch {
                                        val d = NavGraphPathfinder.haversine(
                                            prevNode.lat, prevNode.lon,
                                            nearExisting.lat, nearExisting.lon
                                        )
                                        db.navDao().insertEdge(NavEdge(prevNode.id, nearExisting.id, d))
                                        db.navDao().insertEdge(NavEdge(nearExisting.id, prevNode.id, d))
                                        sessionEdges += 2
                                        statusMsg = "Following existing path…"
                                    }
                                }
                            } else {
                                // ── New node: this position has no existing coverage ──
                                val newNode  = NavNode(
                                    id    = UUID.randomUUID().toString(),
                                    lat   = corrLat,
                                    lon   = corrLon,
                                    alt   = corrAlt,
                                    floor = currentFloor
                                )
                                val prevNode     = lastRecordedNode
                                lastRecordedNode = newNode
                                lastCaptureTime  = now

                                scope.launch {
                                    db.navDao().insertNode(newNode)
                                    sessionNodes++
                                    if (prevNode != null) {
                                        db.navDao().insertEdge(
                                            NavEdge(
                                                fromId = prevNode.id,
                                                toId   = newNode.id,
                                                weight = NavGraphPathfinder.haversine(
                                                    prevNode.lat, prevNode.lon,
                                                    newNode.lat,  newNode.lon
                                                )
                                            )
                                        )
                                        sessionEdges++
                                    }
                                    // Auto-bridge to nearby pre-recorded segments within
                                    // SEGMENT_SNAP_RADIUS_M (wider than MIN_NODE_DISTANCE_M
                                    // so junctions get connected even if not perfectly
                                    // aligned).  Altitude guard prevents cross-floor bridges.
                                    val bridges = preloadedNodes.filter { existing ->
                                        existing.id != newNode.id &&
                                        existing.id != prevNode?.id &&
                                        kotlin.math.abs(existing.alt - newNode.alt) <= 1.5 &&
                                        NavGraphPathfinder.haversine(
                                            newNode.lat, newNode.lon, existing.lat, existing.lon
                                        ) <= SEGMENT_SNAP_RADIUS_M
                                    }
                                    for (nearby in bridges) {
                                        val d = NavGraphPathfinder.haversine(
                                            newNode.lat, newNode.lon,
                                            nearby.lat,  nearby.lon
                                        )
                                        db.navDao().insertEdge(NavEdge(newNode.id, nearby.id, d))
                                        db.navDao().insertEdge(NavEdge(nearby.id, newNode.id, d))
                                        sessionEdges += 2
                                    }
                                    if (bridges.isNotEmpty()) {
                                        statusMsg = "Auto-bridged to ${bridges.size} nearby segment(s)"
                                    }
                                }
                            }
                        }
                    }

                    // ── Door QR scan (active only after admin taps "Scan QR") ────
                    if (isDoorScanActive && now - lastScanTime > 1_000L) {
                        try {
                            if (currentLifecycleState == Lifecycle.State.RESUMED) {
                                val image    = frame.acquireCameraImage()
                                lastScanTime = now

                                val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    context.display.rotation ?: Surface.ROTATION_0
                                } else {
                                    @Suppress("DEPRECATION")
                                    (context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager)
                                        .defaultDisplay.rotation
                                }
                                val inputImage = InputImage.fromMediaImage(
                                    image,
                                    when (rotation) {
                                        Surface.ROTATION_90  -> 0
                                        Surface.ROTATION_180 -> 270
                                        Surface.ROTATION_270 -> 180
                                        else                 -> 90
                                    }
                                )
                                scanner.process(inputImage)
                                    .addOnSuccessListener { barcodes ->
                                        if (barcodes.isNotEmpty()) {
                                            val qrId = barcodes[0].rawValue ?: return@addOnSuccessListener
                                            val node = pendingDoorNode ?: return@addOnSuccessListener
                                            val capturedHeading = sensorHeading
                                            scope.launch {
                                                val loc = db.qrDao().getById(qrId)
                                                val linked = if (loc != null) {
                                                    // Snap to QR's surveyed position so cross-session
                                                    // door nodes all converge on the same coordinates.
                                                    node.copy(
                                                        anchorQrId = qrId,
                                                        label      = loc.name,
                                                        lat        = loc.lat,
                                                        lon        = loc.lon,
                                                        alt        = if (loc.alt != 0.0) loc.alt else node.alt
                                                    )
                                                } else {
                                                    // QR not in room database — store ID only
                                                    node.copy(anchorQrId = qrId)
                                                }
                                                db.navDao().updateNode(linked)
                                                reweightEdgesForNode(linked)
                                                lastRecordedNode = linked
                                                if (capturedHeading != null && loc != null) {
                                                    db.qrDao().updateFacingDeg(qrId, capturedHeading)
                                                }
                                                statusMsg = if (loc != null)
                                                    "Door linked to '${loc.name}'"
                                                else
                                                    "QR stored — room not yet in database"
                                                isDoorScanActive = false
                                                pendingDoorNode  = null
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { image.close() }
                            }
                        } catch (_: Exception) { /* Native buffer safety */ }
                    }
                        } // end if TRACKING
                    } // end if earth != null
                }
            ) {
                // ── Cloud anchor visual markers ────────────────────────────────
                val anchorMarkerMaterial = remember(materialLoader) {
                    materialLoader.createColorInstance(
                        color = SceneViewColor(0.0f, 0.9f, 0.85f, 0.95f), // teal
                        metallic = 0.0f, roughness = 0.4f, reflectance = 0.6f
                    )
                }
                val anchorArrowMaterial = remember(materialLoader) {
                    materialLoader.createColorInstance(
                        color = SceneViewColor(1.0f, 0.6f, 0.0f, 1.0f), // amber arrow
                        metallic = 0.0f, roughness = 0.5f, reflectance = 0.5f
                    )
                }
                cloudAnchorVisuals.forEach { anchor ->
                    AnchorNode(anchor = anchor) {
                        // Teal diamond-shaped marker at the anchor position
                        CubeNode(
                            size             = Float3(0.15f, 0.15f, 0.15f),
                            center           = Position(0f, 0f, 0f),
                            materialInstance = anchorMarkerMaterial
                        )
                        // Amber arrow pointing in the camera's forward direction (-Z)
                        // so you can see which way you were facing when the anchor was hosted
                        CubeNode(
                            size             = Float3(0.05f, 0.05f, 0.4f),
                            center           = Position(0f, 0f, -0.3f),
                            materialInstance = anchorArrowMaterial
                        )
                        // Arrow tip
                        CubeNode(
                            size             = Float3(0.12f, 0.12f, 0.08f),
                            center           = Position(0f, 0f, -0.54f),
                            materialInstance = anchorArrowMaterial
                        )
                    }
                }
            }
        } else {
            Box(
                modifier         = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Color(0xFF64FFDA)) }
        }

        // ── BOTTOM-LEFT: tiled minimap ────────────────────────────────────────
        geospatialPose?.let { pose ->
            val heading = (pose.heading + 360.0) % 360.0
            TiledMiniMap(
                lat      = pose.latitude,
                lon      = pose.longitude,
                heading  = heading,
                nodes    = preloadedNodes,
                edges    = preloadedEdges,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 230.dp)
            )
        }

        // HUD overlay
        Column(
            modifier            = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            RecordingTopHud(
                isRecording        = isRecording,
                sessionNodes       = sessionNodes,
                sessionEdges       = sessionEdges,
                statusMsg          = statusMsg,
                geospatialPose     = geospatialPose,
                earthTrackingState = earthTrackingState,
            )
            RecordingControlsPanel(
                currentFloor       = currentFloor,
                onFloorChange      = onFloorChange,
                lastRecordedNode   = lastRecordedNode,
                lastNodeType       = lastNodeType,
                onMarkAs           = ::markLastNodeAs,
                isDoorScanActive   = isDoorScanActive,
                onCancelDoorScan   = {
                    isDoorScanActive = false
                    pendingDoorNode  = null
                    statusMsg        = "Door QR scan cancelled"
                },
                isRecording        = isRecording,
                onRecordToggle     = {
                    isRecording = !isRecording
                    if (isRecording) {
                        lastRecordedNode = null
                        lastNodeType     = NodeType.WAYPOINT
                        scope.launch {
                            preloadedNodes = withContext(Dispatchers.IO) { db.navDao().getAllNodes() }
                            preloadedEdges = withContext(Dispatchers.IO) { db.navDao().getAllEdges() }
                            statusMsg = "Recording — walk the corridor"
                        }
                    } else {
                        statusMsg = "Paused — mark type or start new segment"
                    }
                },
                onFinished         = {
                    isRecording = false
                    onFinished()
                },
                cloudHostState     = cloudHostState,
                onHostCloudAnchor  = ::hostCloudAnchor,
                canHostAnchor      = lastRecordedNode != null &&
                                     earthTrackingState == TrackingState.TRACKING,
            )
        }
    }

    // Scan-QR-or-skip prompt shown after marking a node as DOOR
    if (showDoorQrPrompt) {
        DoorQrPromptDialog(
            onScanQr = {
                showDoorQrPrompt = false
                isDoorScanActive = true
                statusMsg        = "Point camera at the door's QR code…"
            },
            onSkip = {
                showDoorQrPrompt = false
                pendingDoorNode  = null
                statusMsg        = "Door marked — GPS only"
            }
        )
    }
}
