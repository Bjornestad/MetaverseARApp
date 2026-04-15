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
import kotlinx.coroutines.launch
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

    // Using Double for high-precision VPS offsets
    var latOffset by mutableDoubleStateOf(0.0)
        private set
    var lonOffset by mutableDoubleStateOf(0.0)
        private set
    var altOffset by mutableDoubleStateOf(0.0)
        private set
    var isCalibrated by mutableStateOf(false)
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
                val response: List<QrFeature> = client.get(gistUrl).body()
                db.qrDao().insertAll(response.map { it.toEntity() })
                allLocations = db.qrDao().getAll()
                statusText = "Ready: Select Destination"
                client.close()
            } catch (e: Exception) {
                statusText = "Sync Failed: ${e.localizedMessage}"
                allLocations = db.qrDao().getAll()
            }
        }
    }

    fun onDestinationSelected(location: QrLocation) {
        selectedDestination = location
        isDropdownExpanded = false
        statusText = "Targeting ${location.name}"
    }

    fun onQrScanned(qrId: String, scanPose: GeospatialPose) {
        viewModelScope.launch {
            val loc = db.qrDao().getById(qrId)
            if (loc != null) {
                // Calculate precision offsets: Current VPS - Recorded Coordinate
                latOffset = scanPose.latitude - loc.lat
                lonOffset = scanPose.longitude - loc.lon
                altOffset = scanPose.altitude - loc.alt
                isCalibrated = true
                isScanning = false
                statusText = "Calibrated at ${loc.name}"
            } else {
                statusText = "Unknown QR Code: $qrId"
            }
        }
    }

    fun toggleScanning() {
        isScanning = !isScanning
        statusText = if (isScanning) "2. Scan Room QR to Calibrate..." else "Ready"
    }

    fun updateGeospatialState(earth: Earth?) {
        if (earth != null) {
            isEarthObjectNull = false
            earthState = earth.earthState
            earthTrackingState = earth.trackingState
            if (earthTrackingState == TrackingState.TRACKING) {
                geospatialPose = earth.cameraGeospatialPose
            }
        } else {
            isEarthObjectNull = true
            earthTrackingState = TrackingState.STOPPED
        }
    }

    class Factory(private val db: AppDatabase) : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ARViewModel(db) as T
        }
    }
}
