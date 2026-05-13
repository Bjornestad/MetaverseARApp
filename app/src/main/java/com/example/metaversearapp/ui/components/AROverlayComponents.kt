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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.metaversearapp.data.QrLocation
import com.example.metaversearapp.ui.ARViewModel
import com.google.ar.core.GeospatialPose
import com.google.ar.core.TrackingState
import java.util.Locale
import kotlin.math.*

/** Shared dark-translucent surface colour used across all AR overlay cards. */
val OverlayBackground = Color(0xFF0F1923).copy(alpha = 0.88f)

@Composable
fun StatusOverlay(status: String) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OverlayBackground)
    ) {
        Text(
            status,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f)
        )
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
    val compassRotation = (-currentPose.heading).toFloat()

    Card(
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = OverlayBackground),
        modifier = Modifier.padding(bottom = 8.dp).size(80.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            // Compass cardinal directions
            Box(modifier = Modifier.fillMaxSize().padding(8.dp).rotate(compassRotation)) {
                Text("N", modifier = Modifier.align(Alignment.TopCenter).rotate(-compassRotation),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFF5252))
                Text("S", modifier = Modifier.align(Alignment.BottomCenter).rotate(-compassRotation),
                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                Text("E", modifier = Modifier.align(Alignment.CenterEnd).rotate(-compassRotation),
                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                Text("W", modifier = Modifier.align(Alignment.CenterStart).rotate(-compassRotation),
                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
            }
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = null,
                modifier = Modifier.size(40.dp).rotate(relativeAngle),
                tint = Color(0xFF64FFDA)
            )
        }
    }
}

@Composable
fun GeospatialBottomOverlay(viewModel: ARViewModel) {
    val pose = viewModel.geospatialPose
    val trackingState = viewModel.earthTrackingState
    val isCalibrated = viewModel.isCalibrated

    Card(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OverlayBackground)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "VPS Status:",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )
                val statusColor = if (isCalibrated) Color(0xFF4CAF50) else Color(0xFFFFCC80)
                Text(
                    if (isCalibrated) "CALIBRATED" else "UNCALIBRATED",
                    color = statusColor,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Text(
                "Tracking: ${trackingState.name}",
                style = MaterialTheme.typography.labelSmall,
                color = if (trackingState == TrackingState.TRACKING) Color(0xFF4CAF50)
                        else Color(0xFFFF5252)
            )

            if (pose != null && trackingState == TrackingState.TRACKING) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = Color.White.copy(alpha = 0.1f)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "H.Acc:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        "%.2fm".format(viewModel.horizontalAccuracy),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (viewModel.horizontalAccuracy < 1.0) Color(0xFF4CAF50)
                                else Color(0xFFFFCC80)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "V.Acc:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        "%.2fm".format(viewModel.verticalAccuracy),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
                Text(
                    String.format(Locale.US, "Lat: %.6f  Lon: %.6f", pose.latitude, pose.longitude),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64FFDA).copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
