package com.example.metaversearapp.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.metaversearapp.data.AppDatabase
import com.example.metaversearapp.data.NavEdge
import com.example.metaversearapp.data.NavGistSync
import com.example.metaversearapp.data.NavGraphPathfinder
import com.example.metaversearapp.data.NavNode
import com.example.metaversearapp.data.NodeType
import com.example.metaversearapp.data.QrLocation
import com.example.metaversearapp.data.toEntity
import com.example.metaversearapp.data.QrFeature
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.TrackingState
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ARViewModel(private val db: AppDatabase) : ViewModel() {

    // --- UI State ---
    var statusText by mutableStateOf("Initializing...")
        private set

    var allLocations by mutableStateOf<List<QrLocation>>(emptyList())
        private set

    var selectedDestination by mutableStateOf<QrLocation?>(null)
    var isDropdownExpanded by mutableStateOf(false)
    var isScanning by mutableStateOf(false)

    // Corridor-calibration feedback
    /** Briefly true while the centroid calibration coroutine is running. */
    var isCorridorCalibrating by mutableStateOf(false)
        private set

    // Arrival notification
    var showArrivalBanner by mutableStateOf(false)
        private set
    var arrivedAtName by mutableStateOf("")
        private set

    fun onArrived(roomName: String) {
        arrivedAtName    = roomName
        showArrivalBanner = true
    }

    fun dismissArrivalBanner() {
        showArrivalBanner    = false
        selectedDestination  = null
        destinationPathNodes = emptyList()
        statusText           = "Ready: Select Destination"
    }

    // --- AR / Geospatial State ---
    var geospatialPose by mutableStateOf<GeospatialPose?>(null)
    var earthTrackingState by mutableStateOf(TrackingState.STOPPED)
    var earthState by mutableStateOf(Earth.EarthState.ENABLED)
    var isEarthObjectNull by mutableStateOf(true)

    // VPS Calibration Offsets
    var latOffset by mutableDoubleStateOf(0.0)
        private set
    var lonOffset by mutableDoubleStateOf(0.0)
        private set
    var altOffset by mutableDoubleStateOf(0.0)
        private set
    /**
     * Compass heading correction (degrees) derived from a QR door scan.
     * Added to [GeospatialPose.heading] to get the calibrated heading.
     * Computed as: bearing(scanPos → doorNode) − vps.heading, normalised to (−180, 180].
     */
    var headingOffset by mutableDoubleStateOf(0.0)
        private set
    /**
     * True only after a QR code has been successfully scanned this session.
     * Starts false so the UI can warn the user before they rely on path accuracy.
     */
    var isCalibrated by mutableStateOf(false)
        private set

    /** True while a Cloud Anchor resolve operation is in flight. */
    var isResolvingCloudAnchor by mutableStateOf(false)

    /**
     * Human-readable summary of the last cloud anchor resolution, shown in the
     * debug overlay.  Null until the first anchor is resolved this session.
     * Format: "⚓ …<shortId>  pos ±Xm  hdg Δ±Y°"
     */
    var lastCloudAnchorInfo by mutableStateOf<String?>(null)
        private set

    // Accuracy Metrics
    var horizontalAccuracy by mutableDoubleStateOf(0.0)
        private set
    var verticalAccuracy by mutableDoubleStateOf(0.0)
        private set
    var headingAccuracy by mutableDoubleStateOf(0.0)
        private set

    // Local AR reference — established at each QR scan.
    // Enables anchor placement using ARCore's local tracking (cm-accurate)
    // instead of VPS, which is unreliable indoors.
    data class LocalArReference(
        val tx: Float, val ty: Float, val tz: Float,
        val northX: Float, val northZ: Float,
        val refLat: Double, val refLon: Double, val refAlt: Double
    )

    var localArRef by mutableStateOf<LocalArReference?>(null)
        private set

    // --- Waypoint Pin State ---

    /**
     * AWAIT_START  → user taps to place the start pin
     * AWAIT_END    → start placed, user taps to place the end pin + trigger A*
     * PATH_READY   → path computed; next tap clears everything
     */
    enum class WaypointMode { AWAIT_START, AWAIT_END, PATH_READY }

    /** Raw VPS coordinates as returned by geospatialPose (no offset applied). */
    data class WaypointPin(val rawLat: Double, val rawLon: Double, val alt: Double)

    var waypointMode by mutableStateOf(WaypointMode.AWAIT_START)
        private set

    var startPin by mutableStateOf<WaypointPin?>(null)
        private set

    var endPin by mutableStateOf<WaypointPin?>(null)
        private set

    /** Ordered A* path between the two placed pins. Empty until the end pin is placed. */
    var testPathNodes by mutableStateOf<List<NavNode>>(emptyList())
        private set

    /**
     * Ordered A* path from the user's current position to [selectedDestination].
     * Recomputed each time a new destination is chosen, and again once VPS
     * tracking locks (in case the destination was selected before lock).
     */
    var destinationPathNodes by mutableStateOf<List<NavNode>>(emptyList())
        private set

    // ── Nav graph snapshot exposed for the minimap ───────────────────────────
    /** All nav nodes currently in the local Room database. Refreshed on sync. */
    var navNodes by mutableStateOf<List<NavNode>>(emptyList())
        private set
    /** All nav edges currently in the local Room database. Refreshed on sync. */
    var navEdges by mutableStateOf<List<NavEdge>>(emptyList())
        private set

    private fun loadNavData() {
        viewModelScope.launch {
            navNodes = withContext(Dispatchers.IO) { db.navDao().getAllNodes() }
            navEdges = withContext(Dispatchers.IO) { db.navDao().getAllEdges() }
        }
    }

    init {
        syncLocations()
        syncNavGraph()
        loadNavData()
    }

    private fun syncLocations() {
        viewModelScope.launch {
            try {
                statusText = "Syncing locations..."
                val client = HttpClient(Android) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true; coerceInputValues = true }, contentType = ContentType.Any)
                    }
                }

                val gistUrl = "https://gist.githubusercontent.com/Bjornestad/3b90e3bd67e9cd9a4bce90fb14f158e9/raw"

                val locations = withContext(Dispatchers.IO) {
                    val response: List<QrFeature> = client.get(gistUrl).body()
                    db.qrDao().insertAll(response.map { it.toEntity() })
                    db.qrDao().getAll()
                }

                allLocations = locations
                statusText = "Ready: Select Destination"
                client.close()
            } catch (e: Exception) {
                statusText = "Sync Failed: ${e.localizedMessage}"
                allLocations = withContext(Dispatchers.IO) { db.qrDao().getAll() }
            }
        }
    }

    /**
     * Downloads the latest nav graph from the GitHub Gist and MERGES it into the
     * local Room copy (upsert — no clear).  This means:
     *  • Nodes/edges already in the Gist overwrite their local counterparts (same id).
     *  • Locally recorded nodes that haven't been uploaded yet are preserved.
     *  • An empty or unavailable Gist never wipes the local graph.
     *
     * Runs silently — failures are swallowed so a missing/unconfigured Gist never
     * crashes the user-facing UI.
     */
    private fun syncNavGraph() {
        viewModelScope.launch {
            try {
                val result = NavGistSync.download()
                result.onSuccess { export ->
                    withContext(Dispatchers.IO) {
                        // Upsert only — never clear, so local-only nodes survive
                        export.nodes.forEach { db.navDao().insertNode(it) }
                        export.edges.forEach { db.navDao().insertEdge(it) }
                        // Remove any edge whose endpoint no longer exists —
                        // upsert never deletes rows, so edges referencing nodes
                        // that were removed from the Gist accumulate otherwise.
                        db.navDao().pruneOrphanEdges()
                    }
                }
                // Failures are silently ignored (token not set, no network, empty gist, etc.)
            } catch (_: Exception) { }
            loadNavData() // refresh minimap data after sync (success or failure)
        }
    }

    fun onDestinationSelected(location: QrLocation) {
        selectedDestination = location
        isDropdownExpanded = false
        destinationPathNodes = emptyList()   // clear stale path while new one computes
        statusText = "Targeting ${location.name}"
        computeDestinationPath()
    }

    /**
     * Runs A* from the user's current corrected position to [selectedDestination].
     *
     * End-node resolution order:
     *  1. A DOOR node whose [NavNode.anchorQrId] matches the destination's QR ID —
     *     this is the node recorded at the physical door, which is more accurate
     *     than the room-centre coordinates stored in [QrLocation].
     *  2. Fallback: the nav node nearest to [QrLocation.lat]/[QrLocation.lon].
     *
     * Safe to call any time — silently does nothing if VPS isn't tracking yet
     * or there is no destination selected.
     */
    fun computeDestinationPath() {
        val dest = selectedDestination ?: return
        val pose = geospatialPose ?: return          // bail if not tracking yet

        viewModelScope.launch {
            val nodes = withContext(Dispatchers.IO) { db.navDao().getAllNodes() }
            val edges = withContext(Dispatchers.IO) { db.navDao().getAllEdges() }
            if (nodes.isEmpty()) return@launch

            val corrLat   = pose.latitude  - latOffset
            val corrLon   = pose.longitude - lonOffset
            val startNode = NavGraphPathfinder.nearestNode(nodes, corrLat, corrLon)

            // Prefer a DOOR node linked to this QR over the room-centre coordinates,
            // since the door node was recorded at the actual physical door location.
            val endNode = nodes.firstOrNull { it.anchorQrId == dest.qrID }
                ?: NavGraphPathfinder.nearestNode(nodes, dest.lat, dest.lon)

            if (startNode != null && endNode != null) {
                val path = NavGraphPathfinder.aStar(nodes, edges, startNode.id, endNode.id)
                destinationPathNodes = path
                if (path.isNotEmpty()) {
                    statusText = "Route to ${dest.name}: ${path.size} waypoints"
                } else {
                    statusText = "No path to ${dest.name} — check nav graph coverage"
                }
            }
        }
    }

    /**
     * Snap the current VPS coordinate system to the QR code's ground truth.
     */
    fun onQrScanned(
        qrId: String,
        scanPose: GeospatialPose,
        camTx: Float, camTy: Float, camTz: Float,
        camQx: Float, camQy: Float, camQz: Float, camQw: Float
    ) {
        viewModelScope.launch {
            val loc = withContext(Dispatchers.IO) { db.qrDao().getById(qrId) }
            if (loc != null) {
                // Prefer the linked door node's coordinates as the calibration reference
                // if one exists — it was physically recorded on-site and is likely more
                // accurate than the map-derived coordinates stored in the QR JSON.
                // Falls back to the QrLocation coords if no door node is linked.
                val doorNode = withContext(Dispatchers.IO) {
                    db.navDao().getAllNodes().firstOrNull { it.anchorQrId == qrId }
                }
                val refLat = doorNode?.lat ?: loc.lat
                val refLon = doorNode?.lon ?: loc.lon
                val refAlt = loc.alt   // altitude stays from QrLocation (door nodes don't store alt)

                latOffset = scanPose.latitude - refLat
                lonOffset = scanPose.longitude - refLon
                altOffset = scanPose.altitude - refAlt

                // ── Heading calibration ──────────────────────────────────────
                // Use the sensor-captured facing direction stored at QR link time
                // (hardware magnetometer + accelerometer, no GPS, no VPS) as the
                // ground truth heading.  This avoids the 180° flip that occurs
                // when GPS-based bearing is used: GPS error is 5–15 m indoors but
                // the user-to-QR distance is only 1–3 m, making GPS bearing
                // unreliable and sometimes exactly backwards.
                //
                // If facingDeg was not captured yet (older QR links), skip heading
                // correction entirely — a missing correction is safer than a wrong
                // one.  The arrows will be slightly off from VPS compass error, but
                // will never flip 180°.
                val trueHeading = loc.facingDeg
                if (trueHeading != null) {
                    var rawOffset = trueHeading - scanPose.heading
                    rawOffset = ((rawOffset + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                    headingOffset = rawOffset
                }
                // else: leave headingOffset at its current value (0 on a fresh session,
                // or whatever a previous cloud-anchor calibration set it to)

                // Establish local AR reference for QR-relative anchor placement.
                // Camera forward in local AR = rotate [0,0,-1] by camera quaternion.
                val fwX = -2f * (camQx * camQz + camQy * camQw)
                val fwZ = -1f + 2f * (camQx * camQx + camQy * camQy)
                val horizLen = sqrt(fwX * fwX + fwZ * fwZ)
                val fwXn = if (horizLen > 0.01f) fwX / horizLen else 0f
                val fwZn = if (horizLen > 0.01f) fwZ / horizLen else -1f
                // Use facingDeg (sensor-captured true north) when available.
                // Fall back to VPS heading when it hasn't been captured yet —
                // the localArRef will be slightly wrong but at least never flipped.
                val refHeading = trueHeading ?: scanPose.heading
                val cosH = cos(Math.toRadians(refHeading)).toFloat()
                val sinH = sin(Math.toRadians(refHeading)).toFloat()
                localArRef = LocalArReference(
                    tx = camTx, ty = camTy, tz = camTz,
                    northX = cosH * fwXn + sinH * fwZn,
                    northZ = -sinH * fwXn + cosH * fwZn,
                    refLat = refLat, refLon = refLon, refAlt = refAlt
                )

                isCalibrated = true
                isScanning = false

                val sourceName = if (doorNode != null) "${loc.name} (door node)" else loc.name
                statusText = if (scanPose.horizontalAccuracy > 1.5) {
                    "Calibrated at $sourceName (Low VPS confidence)"
                } else {
                    "Calibrated at $sourceName"
                }

                // Recompute the path now that offsets are correct — the previous path
                // was found using raw (uncorrected) GPS, so the start node was likely
                // wrong and may have returned empty or a badly-routed result.
                computeDestinationPath()
            } else {
                statusText = "Unknown QR Code: $qrId"
            }
        }
    }

    /**
     * Corridor centroid calibration — the user has declared they are standing
     * in the **middle** of a group of nearby recorded nodes (e.g. | x x USER x x |).
     *
     * Algorithm:
     *  1. Collect all nodes within [CENTROID_RADIUS_M] metres of the raw VPS pose.
     *  2. If fewer than 2 nodes qualify, fall back to the 4 nearest regardless of
     *     distance (gives a sensible result even in sparse graphs).
     *  3. Compute the geographic centroid (average lat and average lon).
     *  4. Snap the VPS coordinate system so that raw-VPS centroid == stored centroid.
     *
     * This tends to be more accurate than snapping to a single node because random
     * VPS error is partially cancelled out across multiple reference points.
     */
    fun calibrateAtCorridorCentroid() {
        val pose = geospatialPose ?: run {
            statusText = "No VPS lock yet — wait for tracking then try again"
            return
        }
        isCorridorCalibrating = true
        viewModelScope.launch {
            val nodes = withContext(Dispatchers.IO) { db.navDao().getAllNodes() }
            if (nodes.isEmpty()) {
                statusText = "No nav graph recorded — cannot calibrate this way"
                isCorridorCalibrating = false
                return@launch
            }

            val withDist = nodes.map { node ->
                node to NavGraphPathfinder.haversine(pose.latitude, pose.longitude, node.lat, node.lon)
            }

            // Collect candidate nodes (within radius, or nearest 4 as a sparse-graph fallback)
            val nearby = withDist.filter { (_, d) -> d <= CENTROID_RADIUS_M }
                .ifEmpty { withDist.sortedBy { (_, d) -> d }.take(4) }

            // Inverse-distance weighting (power = 2): nodes that are closer pull harder.
            // This means a sparse or off-axis node has much less influence than a nearby one,
            // so the centroid stays anchored to where the user actually is rather than being
            // dragged toward a distant cluster.
            //
            // Edge case: if the user is standing almost exactly on a recorded node (< 0.1 m),
            // snap straight to that node rather than dividing by near-zero.
            val exactHit = nearby.firstOrNull { (_, d) -> d < 0.1 }
            val (centroidLat, centroidLon) = if (exactHit != null) {
                exactHit.first.lat to exactHit.first.lon
            } else {
                val totalWeight = nearby.sumOf { (_, d) -> 1.0 / (d * d) }
                val wLat = nearby.sumOf { (n, d) -> n.lat / (d * d) } / totalWeight
                val wLon = nearby.sumOf { (n, d) -> n.lon / (d * d) } / totalWeight
                wLat to wLon
            }

            latOffset    = pose.latitude  - centroidLat
            lonOffset    = pose.longitude - centroidLon
            isCalibrated = true
            isCorridorCalibrating = false

            statusText = "Calibrated — averaged ${nearby.size} surrounding nodes"
            computeDestinationPath()
        }
    }

    companion object {
        /** Nodes within this radius (metres) are included in the corridor centroid. */
        private const val CENTROID_RADIUS_M = 20.0
    }

    /**
     * Cycles through the three waypoint placement steps:
     *
     * 1st tap → places the **start pin** at the current geospatial position
     * 2nd tap → places the **end pin** and runs A* between the two nearest nav nodes
     * 3rd tap → clears both pins and the computed path
     */
    fun placeWaypoint() {
        val pose = geospatialPose ?: run {
            statusText = "No geospatial lock yet — try again in a moment"
            return
        }

        when (waypointMode) {
            WaypointMode.AWAIT_START -> {
                startPin = WaypointPin(pose.latitude, pose.longitude, pose.altitude - 1.7)
                waypointMode = WaypointMode.AWAIT_END
                statusText = if (!isCalibrated)
                    "Start pin placed — ⚠ scan a QR first for accurate arrows"
                else
                    "Start pin placed — walk to the end point and tap again"
            }

            WaypointMode.AWAIT_END -> {
                val newEnd = WaypointPin(pose.latitude, pose.longitude, pose.altitude - 1.7)
                endPin = newEnd
                waypointMode  = WaypointMode.PATH_READY
                testPathNodes = emptyList()   // clear previous arrows immediately while A* runs
                statusText    = "Computing path…"

                // Run A* on the background thread
                viewModelScope.launch {
                    val nodes = withContext(Dispatchers.IO) { db.navDao().getAllNodes() }
                    val edges = withContext(Dispatchers.IO) { db.navDao().getAllEdges() }

                    if (nodes.isEmpty()) {
                        statusText = "No nav graph recorded — use Admin to record a path first"
                        return@launch
                    }

                    // Convert raw VPS coords to corrected coords for nearest-node lookup.
                    // If the user hasn't scanned a QR code this session (latOffset == 0),
                    // their raw VPS position may differ from the stored corrected node
                    // coordinates by several metres — arrows will still be created but
                    // may appear offset from the real-world path until calibration is done.
                    val correctedStartLat = startPin!!.rawLat - latOffset
                    val correctedStartLon = startPin!!.rawLon - lonOffset
                    val correctedEndLat   = newEnd.rawLat - latOffset
                    val correctedEndLon   = newEnd.rawLon - lonOffset

                    val startNode = NavGraphPathfinder.nearestNode(nodes, correctedStartLat, correctedStartLon)
                    val endNode   = NavGraphPathfinder.nearestNode(nodes, correctedEndLat,   correctedEndLon)

                    if (startNode == null || endNode == null) {
                        statusText = "No nearby nav nodes — record a path here in Admin first"
                        return@launch
                    }

                    // Warn if the nearest node is suspiciously far — this usually means
                    // the session isn't calibrated and the coordinate spaces don't align.
                    val startDist = NavGraphPathfinder.haversine(
                        correctedStartLat, correctedStartLon, startNode.lat, startNode.lon)
                    val endDist   = NavGraphPathfinder.haversine(
                        correctedEndLat, correctedEndLon, endNode.lat, endNode.lon)
                    val maxDist   = maxOf(startDist, endDist)

                    if (maxDist > 30.0) {
                        statusText = "⚠ Nearest node is ${maxDist.toInt()} m away — " +
                            "scan a QR code to calibrate so arrows appear in the right place"
                        // Still run A* so the overlay arrows show (they just may be offset)
                    }

                    if (startNode.id == endNode.id) {
                        statusText = "Start and end map to the same nav node — " +
                            if (!isCalibrated) "try scanning a QR code to calibrate first"
                            else "place pins further apart or add more nodes in this area"
                        return@launch
                    }

                    val path = NavGraphPathfinder.aStar(nodes, edges, startNode.id, endNode.id)
                    testPathNodes = path
                    statusText = when {
                        path.isEmpty() -> "No path found — graph may be disconnected here"
                        !isCalibrated  -> "Path found (${path.size} waypoints) — scan QR for accurate arrow placement"
                        else           -> "Path found: ${path.size} waypoints"
                    }
                }
            }

            WaypointMode.PATH_READY -> {
                startPin      = null
                endPin        = null
                testPathNodes = emptyList()
                waypointMode  = WaypointMode.AWAIT_START
                statusText    = "Waypoints cleared"
            }
        }
    }

    /**
     * Called when the user has wandered off the current route.
     * Updates the status text immediately, then re-runs A* from the
     * user's current corrected position to the same destination.
     * Safe to call from any thread — delegates to [computeDestinationPath]
     * which dispatches its DB work onto [Dispatchers.IO] internally.
     */
    fun requestReroute() {
        if (selectedDestination == null) return
        statusText = "Off route — recalculating…"
        computeDestinationPath()
    }

    fun toggleScanning() {
        isScanning = !isScanning
        statusText = if (isScanning) "Scanning QR to improve accuracy..." else "Ready"
    }

    fun updateGeospatialState(earth: Earth?) {
        if (earth != null) {
            isEarthObjectNull = false
            earthState = earth.earthState
            earthTrackingState = earth.trackingState
            if (earthTrackingState == TrackingState.TRACKING) {
                val pose = earth.cameraGeospatialPose
                geospatialPose = pose
                horizontalAccuracy = pose.horizontalAccuracy
                verticalAccuracy = pose.verticalAccuracy
                headingAccuracy = pose.headingAccuracy.toDouble()
            } else {
                geospatialPose = null
            }
        } else {
            isEarthObjectNull = true
            earthTrackingState = TrackingState.STOPPED
            geospatialPose = null
        }
    }

    /**
     * Called when a Cloud Anchor has been successfully resolved.
     *
     * [geoPose] is the geospatial pose of the resolved anchor obtained via
     * `Earth.getGeospatialPose(anchor.pose)` — this gives the VPS coordinate
     * that corresponds to the anchor's known physical position.
     * [refLat] / [refLon] are the ground-truth coordinates stored in the
     * NavNode when the anchor was hosted.
     *
     * The offset is the difference between what VPS reports and reality,
     * exactly the same semantics as the QR-based calibration.
     */
    /**
     * Called when a Cloud Anchor has been successfully resolved.
     *
     * In addition to updating the GPS correction offsets (same as before), this now
     * builds a [LocalArReference] from the anchor's local AR-space pose — exactly the
     * same thing [onQrScanned] does from the camera pose at scan time.  Once localArRef
     * is set, all arrow placement switches from GPS earth anchors to the local-coordinate
     * path: centimetre-accurate relative positioning, correct floor height, and direction
     * derived from the magnetometer-captured [storedHeading] rather than VPS compass.
     *
     * [anchorTx/Ty/Tz] and [anchorQx/Qy/Qz/Qw] are the translation and rotation of
     * the resolved anchor's pose captured on the GL thread before anchor.detach().
     * [refLat] / [refLon] are the ground-truth GPS coordinates stored in the NavNode.
     * [storedHeading] is the true compass bearing recorded when the anchor was hosted
     * (device magnetometer + accelerometer, stored in [NavNode.cloudAnchorHeading]).
     */
    fun onCloudAnchorResolved(
        geoPose: GeospatialPose,
        anchorTx: Float, anchorTy: Float, anchorTz: Float,
        anchorQx: Float, anchorQy: Float, anchorQz: Float, anchorQw: Float,
        refLat: Double,
        refLon: Double,
        storedHeading: Double?,
        anchorId: String
    ) {
        latOffset = geoPose.latitude  - refLat
        lonOffset = geoPose.longitude - refLon
        altOffset = geoPose.altitude

        // Heading correction (kept for the earth-anchor fallback path)
        val hdgDelta: Double? = if (storedHeading != null) {
            var raw = storedHeading - geoPose.heading
            raw = ((raw + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            headingOffset = raw
            raw
        } else null

        // ── Build localArRef from the cloud anchor's AR-space pose ──────────────
        // This mirrors onQrScanned exactly: the anchor was created at frame.camera.pose
        // by the admin, so its orientation encodes the camera's facing direction at that
        // moment.  Camera forward in local AR = rotate [0,0,−1] by the pose quaternion.
        //
        //   fwX = -2(qx·qz + qy·qw)
        //   fwZ = -1 + 2(qx² + qy²)
        //
        // Once we have the forward direction we rotate it "backward" by storedHeading
        // (the true compass bearing the admin faced) to find where north lies in local
        // AR space.  From that point all arrows use createLocalArrowAnchor, which gives
        // correct floor height (ref.ty − 1.7 m) and compass-accurate pointing — no GPS.
        //
        // Nav nodes store corrAlt = pose.altitude − altOffset_at_recording ≈ 0 for a
        // flat corridor, so refAlt = 0.0 keeps (alt − refAlt) near zero and arrows land
        // at floor level.  Stair altitude differences are handled by the non-zero
        // corrAlt values recorded during the admin walk.
        val fwX = -2f * (anchorQx * anchorQz + anchorQy * anchorQw)
        val fwZ = -1f + 2f * (anchorQx * anchorQx + anchorQy * anchorQy)
        val horizLen = sqrt(fwX * fwX + fwZ * fwZ)
        val fwXn = if (horizLen > 0.01f) fwX / horizLen else 0f
        val fwZn = if (horizLen > 0.01f) fwZ / horizLen else -1f
        val refHeading = storedHeading ?: geoPose.heading
        val cosH = cos(Math.toRadians(refHeading)).toFloat()
        val sinH = sin(Math.toRadians(refHeading)).toFloat()
        localArRef = LocalArReference(
            tx = anchorTx, ty = anchorTy, tz = anchorTz,
            northX = cosH * fwXn + sinH * fwZn,
            northZ = -sinH * fwXn + cosH * fwZn,
            refLat = refLat, refLon = refLon, refAlt = 0.0
        )

        // Debug overlay
        val dLat = (latOffset * 111_320.0)
        val dLon = (lonOffset * 111_320.0 * Math.cos(Math.toRadians(refLat)))
        val posErrM = Math.sqrt(dLat * dLat + dLon * dLon)
        val hdgStr = if (hdgDelta != null) "  hdg Δ%+.1f°".format(hdgDelta) else "  hdg —"
        lastCloudAnchorInfo = "⚓ …${anchorId.takeLast(8)}  pos %+.2fm%s".format(posErrM, hdgStr)

        isCalibrated = true
        isResolvingCloudAnchor = false
        val accuracyM = geoPose.horizontalAccuracy.toInt()
        statusText = "Calibrated via cloud anchor (±${accuracyM}m VPS)"
        computeDestinationPath()
    }

    /**
     * Returns the "snapped" coordinates (current VPS minus calculated drift).
     */
    fun getCorrectedPose(): Pair<Double, Double>? {
        val pose = geospatialPose ?: return null
        return Pair(pose.latitude - latOffset, pose.longitude - lonOffset)
    }

    class Factory(private val db: AppDatabase) : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ARViewModel(db) as T
        }
    }
}
