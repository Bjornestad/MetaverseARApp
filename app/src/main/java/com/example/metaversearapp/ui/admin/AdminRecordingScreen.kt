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

    // ── Calibration offsets (set by QR scan) ───────────────────────────────────
    var latOffset    by remember { mutableStateOf(0.0) }
    var lonOffset    by remember { mutableStateOf(0.0) }
    var altOffset    by remember { mutableStateOf(0.0) }
    var isCalibrated by remember { mutableStateOf(true) }

    // ── Recording state ────────────────────────────────────────────────────────
    var isRecording      by remember { mutableStateOf(false) }
    var sessionNodes     by remember { mutableIntStateOf(0) }
    var sessionEdges     by remember { mutableIntStateOf(0) }
    var lastRecordedNode by remember { mutableStateOf<NavNode?>(null) }
    var lastCaptureTime  by remember { mutableLongStateOf(0L) }
    var statusMsg        by remember { mutableStateOf("Tap ▶ to record  ·  scan QR to calibrate") }
    var lastNodeType     by remember { mutableStateOf(NodeType.WAYPOINT) }

    // Auto-bridging: snapshot of existing nodes taken at recording-segment start.
    // Duplicate edges are handled by Room's composite primary key (ON CONFLICT REPLACE)
    // so we don't need a separate bridgedNodeIds set — every nearby node gets a bridge
    // attempt on every new node, and the DB deduplicates silently.
    var preloadedNodes by remember { mutableStateOf<List<NavNode>>(emptyList()) }

    // ── Cloud Anchor state ─────────────────────────────────────────────────────
    var arSession        by remember { mutableStateOf<Session?>(null) }
    var lastCameraPose   by remember { mutableStateOf<Pose?>(null) }
    var cloudHostState   by remember { mutableStateOf<HostState>(HostState.Idle) }
    // Hosted cloud anchor visuals — kept alive (not detached) so they render in the scene
    var cloudAnchorVisuals by remember { mutableStateOf<List<Anchor>>(emptyList()) }

    // ── Hardware compass heading (rotation-vector sensor, GPS-independent) ────────
    // Continuously updated so linkDoorToQr() can snapshot the current facing
    // direction without any GPS involvement.  This avoids the 180° flip that
    // occurs when GPS positions are used to derive a bearing at short distances.
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

    // ── Door → QR link picker state ────────────────────────────────────────────
    var showDoorLinkDialog by remember { mutableStateOf(false) }
    var doorLinkCandidates by remember { mutableStateOf<List<Pair<QrLocation, Double>>>(emptyList()) }
    var pendingDoorNode    by remember { mutableStateOf<NavNode?>(null) }

    // ── QR scanning ────────────────────────────────────────────────────────────
    var isScanning   by remember { mutableStateOf(false) }
    var lastScanTime by remember { mutableLongStateOf(0L) }

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
                val candidates = db.qrDao().getAll()
                    .map { qr -> qr to NavGraphPathfinder.haversine(node.lat, node.lon, qr.lat, qr.lon) }
                    .filter { (_, dist) -> dist <= QR_LINK_RADIUS_M }
                    .sortedBy { (_, dist) -> dist }
                if (candidates.isNotEmpty()) {
                    pendingDoorNode    = updated
                    doorLinkCandidates = candidates
                    showDoorLinkDialog = true
                    statusMsg = "Choose which room this door belongs to…"
                }
            }
        }
    }

    /**
     * Recomputes the weight of every edge that touches [node], using the node's
     * current (possibly just-snapped) coordinates and the neighbour's stored coords.
     * Must be called after any operation that moves a node's lat/lon/alt in the DB.
     */
    suspend fun reweightEdgesForNode(node: NavNode) {
        val edges = db.navDao().getEdgesForNode(node.id)
        for (edge in edges) {
            val neighbourId = if (edge.fromId == node.id) edge.toId else edge.fromId
            val neighbour   = db.navDao().getNodeById(neighbourId) ?: continue
            val newWeight   = NavGraphPathfinder.distance3d(
                node.lat, node.lon, node.alt,
                neighbour.lat, neighbour.lon, neighbour.alt
            )
            db.navDao().insertEdge(edge.copy(weight = newWeight))
        }
    }

    /** Snaps [pendingDoorNode] to [qr]'s ground-truth coords and persists the link. */
    fun linkDoorToQr(qr: QrLocation) {
        val node = pendingDoorNode ?: return
        // Snapshot the hardware compass heading now — admin is standing in front
        // of the QR code so this IS the direction users will face when scanning.
        // Stored so onQrScanned() can use it as ground truth instead of GPS bearing.
        val capturedFacingDeg = sensorHeading
        scope.launch {
            val linked = node.copy(
                anchorQrId = qr.qrID,
                label      = qr.name,
                lat        = qr.lat,
                lon        = qr.lon,
                alt        = if (qr.alt != 0.0) qr.alt else node.alt
            )
            db.navDao().updateNode(linked)
            reweightEdgesForNode(linked)
            lastRecordedNode = linked
            if (capturedFacingDeg != null) {
                db.qrDao().updateFacingDeg(qr.qrID, capturedFacingDeg)
                statusMsg = "Door linked to '${qr.name}'  ·  facing %.1f°".format(capturedFacingDeg)
            } else {
                statusMsg = "Door linked to '${qr.name}'"
            }
        }
        showDoorLinkDialog = false
        pendingDoorNode    = null
        doorLinkCandidates = emptyList()
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
                    if (isRecording && isCalibrated && now - lastCaptureTime > CAPTURE_INTERVAL_MS) {
                        val corrLat = pose.latitude  - latOffset
                        val corrLon = pose.longitude - lonOffset
                        val corrAlt = pose.altitude  - altOffset

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
                                        val d = NavGraphPathfinder.distance3d(
                                            prevNode.lat, prevNode.lon, prevNode.alt,
                                            nearExisting.lat, nearExisting.lon, nearExisting.alt
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
                                                weight = NavGraphPathfinder.distance3d(
                                                    prevNode.lat, prevNode.lon, prevNode.alt,
                                                    newNode.lat,  newNode.lon,  newNode.alt
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
                                        val d = NavGraphPathfinder.distance3d(
                                            newNode.lat, newNode.lon, newNode.alt,
                                            nearby.lat,  nearby.lon,  nearby.alt
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

                    // ── QR calibration scan ───────────────────────────────────
                    if (isScanning && now - lastScanTime > 1_000L) {
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
                                            scope.launch {
                                                val loc = db.qrDao().getById(qrId)
                                                if (loc != null) {
                                                    latOffset    = pose.latitude  - loc.lat
                                                    lonOffset    = pose.longitude - loc.lon
                                                    if (loc.alt != 0.0) altOffset = pose.altitude - loc.alt
                                                    isCalibrated = true
                                                    isScanning   = false
                                                    lastRecordedNode?.let { node ->
                                                        val anchored = node.copy(
                                                            anchorQrId = qrId,
                                                            label      = loc.name,
                                                            lat        = loc.lat,
                                                            lon        = loc.lon,
                                                            alt        = if (loc.alt != 0.0) loc.alt else node.alt
                                                        )
                                                        db.navDao().updateNode(anchored)
                                                        reweightEdgesForNode(anchored)
                                                        lastRecordedNode = anchored
                                                    }
                                                    statusMsg = if (isRecording)
                                                        "Recalibrated at ${loc.name} — keep walking"
                                                    else
                                                        "Calibrated at ${loc.name} — ready to record"
                                                } else {
                                                    statusMsg = "QR not in database: $qrId"
                                                }
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
                isCalibrated       = isCalibrated,
                latOffset          = latOffset,
                lonOffset          = lonOffset,
                altOffset          = altOffset,
            )
            RecordingControlsPanel(
                currentFloor       = currentFloor,
                onFloorChange      = onFloorChange,
                lastRecordedNode   = lastRecordedNode,
                lastNodeType       = lastNodeType,
                onMarkAs           = ::markLastNodeAs,
                isScanning         = isScanning,
                onScanToggle       = {
                    isScanning = !isScanning
                    if (isScanning) statusMsg = "Point camera at a QR code..."
                },
                isRecording        = isRecording,
                onRecordToggle     = {
                    isRecording = !isRecording
                    if (isRecording) {
                        lastRecordedNode = null
                        lastNodeType     = NodeType.WAYPOINT
                        scope.launch {
                            preloadedNodes = withContext(Dispatchers.IO) { db.navDao().getAllNodes() }
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

    // Door → QR link picker dialog
    if (showDoorLinkDialog && doorLinkCandidates.isNotEmpty()) {
        DoorLinkPickerDialog(
            candidates = doorLinkCandidates,
            onLink     = ::linkDoorToQr,
            onDismiss  = {
                showDoorLinkDialog = false
                pendingDoorNode    = null
                doorLinkCandidates = emptyList()
                statusMsg = "Marked as Door (no room linked)"
            }
        )
    }
}
