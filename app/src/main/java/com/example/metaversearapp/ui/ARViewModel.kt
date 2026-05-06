package com.example.metaversearapp.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.metaversearapp.data.AppDatabase
import com.example.metaversearapp.data.NavGistSync
import com.example.metaversearapp.data.NavGraphPathfinder
import com.example.metaversearapp.data.NavNode
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
     * Runs A* from the user's current corrected position to the nearest nav
     * node to [selectedDestination].  Safe to call any time — silently does
     * nothing if VPS isn't tracking yet or there is no destination selected.
     * Called automatically on destination selection and whenever VPS first locks.
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
            val endNode   = NavGraphPathfinder.nearestNode(nodes, dest.lat, dest.lon)

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
                latOffset = scanPose.latitude - loc.lat
                lonOffset = scanPose.longitude - loc.lon
                altOffset = scanPose.altitude - loc.alt

                isCalibrated = true
                isScanning = false

                statusText = if (scanPose.horizontalAccuracy > 1.5) {
                    "Calibrated at ${loc.name} (Low VPS confidence)"
                } else {
                    "Calibrated at ${loc.name}"
                }
            } else {
                statusText = "Unknown QR Code: $qrId"
            }
        }
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
                waypointMode = WaypointMode.PATH_READY

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
