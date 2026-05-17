package com.example.metaversearapp.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.metaversearapp.data.AppDatabase
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
     * True only after a QR code has been successfully scanned this session.
     * Starts false so the UI can warn the user before they rely on path accuracy.
     */
    var isCalibrated by mutableStateOf(false)
        private set

    // Accuracy Metrics
    var horizontalAccuracy by mutableDoubleStateOf(0.0)
        private set
    var verticalAccuracy by mutableDoubleStateOf(0.0)
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

    init {
        syncLocations()
        syncNavGraph()
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
                    }
                }
                // Failures are silently ignored (token not set, no network, empty gist, etc.)
            } catch (_: Exception) { }
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
    fun onQrScanned(qrId: String, scanPose: GeospatialPose) {
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

                isCalibrated = true
                isScanning = false

                val sourceName = if (doorNode != null) "${loc.name} (door node)" else loc.name
                statusText = if (scanPose.horizontalAccuracy > 1.5) {
                    "Calibrated at $sourceName (Low VPS confidence)"
                } else {
                    "Calibrated at $sourceName"
                }
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
