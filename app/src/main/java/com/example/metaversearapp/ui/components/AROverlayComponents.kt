package com.example.metaversearapp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.metaversearapp.data.QrLocation
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.TrackingState
import java.util.Locale
import kotlin.math.*

@Composable
fun StatusOverlay(status: String) {
    Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
        Text(status, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun NavigationArrow(currentPose: GeospatialPose, destination: QrLocation, latOffset: Double, lonOffset: Double) {
    val targetLat = destination.lat + latOffset
    val targetLon = destination.lon + lonOffset
    val lat1 = Math.toRadians(currentPose.latitude)
    val lon1 = Math.toRadians(currentPose.longitude)
    val lat2 = Math.toRadians(targetLat)
    val lon2 = Math.toRadians(targetLon)
    val dLon = lon2 - lon1
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val bearing = Math.toDegrees(atan2(y, x))
    val relativeAngle = (bearing - currentPose.heading).toFloat()

    Card(
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)),
        modifier = Modifier.padding(bottom = 8.dp).size(64.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = null,
                modifier = Modifier.size(40.dp).rotate(relativeAngle),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun GeospatialBottomOverlay(
    pose: GeospatialPose?,
    trackingState: TrackingState,
    earthState: Earth.EarthState,
    isEarthNull: Boolean,
    isCalibrated: Boolean
) {
    Card(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("VPS Status:", style = MaterialTheme.typography.labelLarge)
                val statusColor = if (isCalibrated) Color(0xFF4CAF50) else Color.Gray
                Text(if (isCalibrated) "CALIBRATED" else "UNCALIBRATED", color = statusColor)
            }
            Text(
                "Tracking: ${trackingState.name}",
                color = if (trackingState == TrackingState.TRACKING) Color(0xFF4CAF50) else Color.Red
            )
            if (pose != null && trackingState == TrackingState.TRACKING) {
                Text(
                    String.format(Locale.US, "Lat: %.6f, Lon: %.6f", pose.latitude, pose.longitude),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
