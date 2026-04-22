package com.example.metaversearapp.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.metaversearapp.data.AppDatabase
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
    var isCalibrated by mutableStateOf(true)
        private set

    // Accuracy Metrics
    var horizontalAccuracy by mutableDoubleStateOf(0.0)
        private set
    var verticalAccuracy by mutableDoubleStateOf(0.0)
        private set

    // Test Anchors — each press appends; existing ones are never replaced.
    data class PlacedAnchor(val lat: Double, val lon: Double, val alt: Double)
    var placedAnchors by mutableStateOf<List<PlacedAnchor>>(emptyList())
        private set

    init {
        syncLocations()
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

    fun onDestinationSelected(location: QrLocation) {
        selectedDestination = location
        isDropdownExpanded = false
        statusText = "Targeting ${location.name}"
    }

    /**
     * Snap the current VPS coordinate system to the QR code's ground truth.
     * This "overwrites" the drift by calculating the delta between VPS and Reality.
     */
    fun onQrScanned(qrId: String, scanPose: GeospatialPose) {
        viewModelScope.launch {
            val loc = withContext(Dispatchers.IO) { db.qrDao().getById(qrId) }
            if (loc != null) {
                // Calculation: How much is the VPS wrong by?
                latOffset = scanPose.latitude - loc.lat
                lonOffset = scanPose.longitude - loc.lon
                altOffset = scanPose.altitude - loc.alt
                
                isCalibrated = true
                isScanning = false
                
                // Provide feedback based on quality, but ALWAYS accept the fix
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
     * Appends a new green test cube at the user's current geospatial position.
     * Each press adds a new permanent marker; existing ones are never touched.
     */
    fun placeTestAnchor() {
        val pose = geospatialPose ?: run {
            statusText = "No geospatial lock yet — try again in a moment"
            return
        }
        placedAnchors = placedAnchors + PlacedAnchor(
            lat = pose.latitude,
            lon = pose.longitude,
            alt = pose.altitude - 1.7  // Drop to floor level
        )
        statusText = "Test object placed (${placedAnchors.size} total)"
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
     * Returns the "Snapped" coordinates (Current VPS - Calculated Drift)
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
