package com.example.metaversearapp.ui.admin

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
import com.google.ar.core.Config
import com.google.ar.core.GeospatialPose
import com.google.ar.core.TrackingState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
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
    var statusMsg        by remember { mutableStateOf("Ready — tap Start Recording, or scan a QR to improve accuracy") }
    var lastNodeType     by remember { mutableStateOf(NodeType.WAYPOINT) }

    // Auto-bridging: snapshot of existing nodes taken at recording-segment start.
    // Duplicate edges are handled by Room's composite primary key (ON CONFLICT REPLACE)
    // so we don't need a separate bridgedNodeIds set — every nearby node gets a bridge
    // attempt on every new node, and the DB deduplicates silently.
    var preloadedNodes by remember { mutableStateOf<List<NavNode>>(emptyList()) }

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
            statusMsg = "No waypoint recorded yet — start recording first"
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
                    statusMsg = "Marked + auto-connected to $connected stair node(s) on other floor(s)"
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
                    statusMsg = "Stair Mid marked + linked to $connected nearby stair node(s)"
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

    /** Snaps [pendingDoorNode] to [qr]'s ground-truth coords and persists the link. */
    fun linkDoorToQr(qr: QrLocation) {
        val node = pendingDoorNode ?: return
        scope.launch {
            val linked = node.copy(
                anchorQrId = qr.qrID,
                label      = qr.name,
                lat        = qr.lat,
                lon        = qr.lon,
                alt        = if (qr.alt != 0.0) qr.alt else node.alt
            )
            db.navDao().updateNode(linked)
            lastRecordedNode = linked
            statusMsg = "Door linked to '${qr.name}'"
        }
        showDoorLinkDialog = false
        pendingDoorNode    = null
        doorLinkCandidates = emptyList()
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
                    config.geospatialMode = Config.GeospatialMode.ENABLED
                    config.focusMode      = Config.FocusMode.AUTO
                },
                onSessionUpdated = { session, frame ->
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
                        // Use 3-D distance so nodes are placed evenly on staircases:
                        // haversine-only would under-measure steep stairs (large vertical,
                        // small horizontal) and cause nodes to bunch up at the top/bottom.
                        val dist    = lastRecordedNode?.let {
                            NavGraphPathfinder.distance3d(corrLat, corrLon, corrAlt, it.lat, it.lon, it.alt)
                        } ?: Double.MAX_VALUE

                        if (dist >= MIN_NODE_DISTANCE_M) {
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
                                // Auto-bridge to nearby pre-recorded segments.
                                // No bridgedNodeIds guard — Room's composite PK
                                // deduplicates edges silently on REPLACE, so every
                                // nearby node gets a connection attempt each capture.
                                // Altitude guard prevents spurious cross-floor bridges
                                // between vertically stacked corridors.
                                val bridges = preloadedNodes.filter { existing ->
                                    existing.id != newNode.id &&
                                    existing.id != prevNode?.id &&
                                    kotlin.math.abs(existing.alt - newNode.alt) <= 3.0 &&
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
            ) { /* No AR nodes rendered in admin mode */ }
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
                currentFloor     = currentFloor,
                onFloorChange    = onFloorChange,
                lastRecordedNode = lastRecordedNode,
                lastNodeType     = lastNodeType,
                onMarkAs         = ::markLastNodeAs,
                isScanning       = isScanning,
                onScanToggle     = {
                    isScanning = !isScanning
                    if (isScanning) statusMsg = "Point camera at a QR code..."
                },
                isRecording      = isRecording,
                onRecordToggle   = {
                    isRecording = !isRecording
                    if (isRecording) {
                        lastRecordedNode = null
                        lastNodeType     = NodeType.WAYPOINT
                        scope.launch {
                            preloadedNodes = withContext(Dispatchers.IO) { db.navDao().getAllNodes() }
                            statusMsg = "Recording — walk the corridor"
                        }
                    } else {
                        statusMsg = "Paused — mark waypoint type or start new segment"
                    }
                },
                onFinished = {
                    isRecording = false
                    onFinished()
                },
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
