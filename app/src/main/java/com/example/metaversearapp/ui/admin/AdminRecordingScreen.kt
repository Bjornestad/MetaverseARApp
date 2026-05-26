package com.example.metaversearapp.ui.admin

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import com.example.metaversearapp.data.FloorAltitude
import com.example.metaversearapp.data.NavEdge
import com.example.metaversearapp.data.NavGraphPathfinder
import com.example.metaversearapp.ui.components.TiledMiniMap
import com.example.metaversearapp.data.NavNode
import com.example.metaversearapp.data.NodeType
import com.example.metaversearapp.data.Room
import com.example.metaversearapp.data.RoomGistSync
import com.example.metaversearapp.data.toRoomId
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.Config
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
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
    currentBuilding: String,
    onBuildingChange: (String) -> Unit,
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
    var isRecording           by remember { mutableStateOf(false) }
    var sessionNodes          by remember { mutableIntStateOf(0) }
    var sessionEdges          by remember { mutableIntStateOf(0) }
    var lastRecordedNode      by remember { mutableStateOf<NavNode?>(null) }
    var lastCaptureTime       by remember { mutableLongStateOf(0L) }
    var statusMsg             by remember { mutableStateOf("Tap ▶ to record") }
    var lastNodeType          by remember { mutableStateOf(NodeType.WAYPOINT) }
    // Admin-configurable VPS precision gate — only nodes recorded below this
    // horizontal accuracy (metres) are accepted.  Persisted across screen
    // recompositions but not across app restarts (reset to default on fresh launch).
    var capturePrecisionM     by remember { mutableStateOf(MAX_CAPTURE_PRECISION_M) }

    // Canonical altitude for the current floor — the median of every existing
    // node on this floor.  All new nodes use this value instead of raw GPS
    // altitude, which can drift ±5–10 m indoors and put arrows at the wrong height.
    // Null until the first GPS reading if no prior nodes exist on this floor.
    var currentFloorAlt  by remember { mutableStateOf<Double?>(null) }

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

    // ── Door naming state ──────────────────────────────────────────────────────
    // showDoorNameDialog: name-entry dialog shown after marking a node as DOOR.
    var showDoorNameDialog by remember { mutableStateOf(false) }
    var pendingDoorNode    by remember { mutableStateOf<NavNode?>(null) }

    // GPS accumulator for floors that have no established canonical altitude yet.
    // Avoids anchoring the whole floor to a single potentially-noisy GPS reading.
    // Stored as a plain remember reference (not state) — only read inside the AR callback.
    val floorGpsAccumulator = remember { mutableMapOf<String, MutableList<Double>>() }

    // When the floor changes during recording: load the table value first (preferred),
    // fall back to node median, reset the GPS accumulator for this floor so samples
    // don't carry over from a previous visit this session.
    LaunchedEffect(currentBuilding, currentFloor) {
        if (!isRecording) return@LaunchedEffect
        floorGpsAccumulator.remove(currentFloor)
        val tableAlt = withContext(Dispatchers.IO) { db.floorAltDao().getAlt(currentBuilding, currentFloor) }
        currentFloorAlt = tableAlt ?: run {
            val nodeMedian = withContext(Dispatchers.IO) { db.navDao().getAllNodes() }
                .filter { it.building == currentBuilding && it.floor == currentFloor }
                .map { it.alt }
                .takeIf { it.isNotEmpty() }
                ?.sorted()?.let { it[it.size / 2] }
            if (nodeMedian != null) {
                withContext(Dispatchers.IO) {
                    db.floorAltDao().upsert(FloorAltitude(currentBuilding, currentFloor, nodeMedian))
                }
            }
            nodeMedian
        }
    }

    // ── DB actions ─────────────────────────────────────────────────────────────

    /**
     * Updates the last recorded node's type in the DB.
     * For STAIR_TOP / STAIR_BOTTOM: also scans for the complementary stair
     * node on a different floor within [STAIR_CONNECT_RADIUS_M] and creates
     * a bridging edge so A* can route between floors across separate walks.
     * For DOOR: opens [DoorNameDialog] so the admin can name the room.
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
                pendingDoorNode    = updated
                showDoorNameDialog = true
                statusMsg          = "Enter a name for this door/room"
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
     * Creates a [Room] from [pendingDoorNode] using [name] as the display name,
     * updates the nav node with the generated room ID and label, and pushes the
     * room to the rooms Gist so all users see the new destination on next launch.
     */
    fun createDoorRoom(name: String) {
        val node = pendingDoorNode ?: return
        val roomId = name.toRoomId()
        scope.launch {
            val linked = node.copy(
                anchorQrId = roomId,
                label      = name
            )
            db.navDao().updateNode(linked)
            reweightEdgesForNode(linked)
            lastRecordedNode = linked

            val room = Room(
                id    = roomId,
                name  = name,
                floor = node.floor,
                lat   = node.lat,
                lon   = node.lon,
                alt   = node.alt
            )
            val result = withContext(Dispatchers.IO) { RoomGistSync.addRoom(room) }
            statusMsg = if (result.isSuccess) "Room '${name}' saved"
                        else "Room saved locally — Gist sync failed"
        }
        showDoorNameDialog = false
        pendingDoorNode    = null
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

                        // Canonical floor altitude. If the table has a value, use it.
                        // If not, accumulate GPS readings until we have 5 then lock in
                        // their median — avoids anchoring the floor to a single noisy reading.
                        val corrAlt: Double? = if (currentFloorAlt != null) {
                            currentFloorAlt
                        } else {
                            val acc = floorGpsAccumulator.getOrPut(currentFloor) { mutableListOf() }
                            acc.add(pose.altitude)
                            lastCaptureTime = now
                            if (acc.size >= 5) {
                                val median = acc.sorted().let { it[it.size / 2] }
                                currentFloorAlt = median
                                scope.launch {
                                    db.floorAltDao().upsert(FloorAltitude(currentBuilding, currentFloor, median))
                                }
                                statusMsg = "Floor altitude locked — recording"
                                median
                            } else {
                                statusMsg = "Settling GPS ${acc.size}/5…"
                                null
                            }
                        }

                        if (corrAlt != null) {
                            // ── Precision gate ────────────────────────────────
                            // Skip this sample if VPS accuracy is too poor to trust.
                            // A node placed with hAcc > MAX_CAPTURE_PRECISION_M can end up
                            // several metres into the wrong corridor, poisoning the graph.
                            val hAcc = pose.horizontalAccuracy
                            if (hAcc > capturePrecisionM) {
                                lastCaptureTime = now   // prevent rapid-fire status updates
                                statusMsg = "Waiting for VPS (±${String.format("%.1f", hAcc)}m > ${capturePrecisionM.toInt()}m)…"
                                return@onSessionUpdated
                            }

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
                                        id       = UUID.randomUUID().toString(),
                                        lat      = corrLat,
                                        lon      = corrLon,
                                        alt      = corrAlt,
                                        floor    = currentFloor,
                                        building = currentBuilding
                                    )
                                    val prevNode     = lastRecordedNode
                                    lastRecordedNode = newNode
                                    lastCaptureTime  = now

                                    scope.launch {
                                        db.navDao().insertNode(newNode)
                                        preloadedNodes = preloadedNodes + newNode   // live minimap update
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
                currentBuilding    = currentBuilding,
                onBuildingChange   = onBuildingChange,
                currentFloor       = currentFloor,
                onFloorChange      = onFloorChange,
                capturePrecisionM  = capturePrecisionM,
                onPrecisionChange  = { capturePrecisionM = it },
                lastRecordedNode   = lastRecordedNode,
                lastNodeType       = lastNodeType,
                onMarkAs           = ::markLastNodeAs,
                isRecording        = isRecording,
                onRecordToggle     = {
                    isRecording = !isRecording
                    if (isRecording) {
                        lastRecordedNode = null
                        lastNodeType     = NodeType.WAYPOINT
                        scope.launch {
                            preloadedNodes = withContext(Dispatchers.IO) { db.navDao().getAllNodes() }
                            preloadedEdges = withContext(Dispatchers.IO) { db.navDao().getAllEdges() }
                            floorGpsAccumulator.clear()
                            // Prefer the table's canonical altitude — it is the stable value
                            // established at first recording. Fall back to node median, then
                            // null (GPS accumulator in capture loop handles a brand-new floor).
                            val tableAlt = withContext(Dispatchers.IO) { db.floorAltDao().getAlt(currentBuilding, currentFloor) }
                            currentFloorAlt = tableAlt ?: run {
                                val nodeMedian = preloadedNodes
                                    .filter { it.building == currentBuilding && it.floor == currentFloor }
                                    .map { it.alt }
                                    .takeIf { it.isNotEmpty() }
                                    ?.sorted()?.let { it[it.size / 2] }
                                // Persist the node median so it's ready for the next session.
                                if (nodeMedian != null) {
                                    withContext(Dispatchers.IO) {
                                        db.floorAltDao().upsert(FloorAltitude(currentBuilding, currentFloor, nodeMedian))
                                    }
                                }
                                nodeMedian
                            }
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

    // Name dialog shown after marking a node as DOOR.
    if (showDoorNameDialog) {
        DoorNameDialog(
            onConfirm = ::createDoorRoom,
            onDismiss = {
                showDoorNameDialog = false
                pendingDoorNode    = null
                statusMsg          = "Door marked — name skipped"
            }
        )
    }
}
