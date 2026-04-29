package com.example.metaversearapp.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.metaversearapp.data.NavNode
import com.example.metaversearapp.ui.ARViewModel
import kotlin.math.*

@Composable
fun ARUiOverlay(viewModel: ARViewModel) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusOverlay(viewModel.statusText)
            Spacer(modifier = Modifier.height(8.dp))

            DestinationSelector(viewModel)

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.toggleScanning() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.isScanning) Color.Red else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (viewModel.isScanning) "Cancel Scan" else "Scan QR to Improve Accuracy")
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Waypoint pin button — cycles: Set Start → Set End → Clear
            val (buttonLabel, buttonColor) = when (viewModel.waypointMode) {
                ARViewModel.WaypointMode.AWAIT_START -> "Set Start Point" to Color(0xFF1565C0)
                ARViewModel.WaypointMode.AWAIT_END   -> "Set End Point"   to Color(0xFF2E7D32)
                ARViewModel.WaypointMode.PATH_READY  -> "Clear Waypoints" to Color(0xFFB71C1C)
            }
            Button(
                onClick = { viewModel.placeWaypoint() },
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(buttonLabel)
            }

            // Path arrows — visible once A* has produced a result
            if (viewModel.testPathNodes.size >= 2) {
                Spacer(modifier = Modifier.height(4.dp))
                PathArrowsOverlay(pathNodes = viewModel.testPathNodes)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (viewModel.selectedDestination != null && viewModel.geospatialPose != null) {
                NavigationArrow(
                    currentPose = viewModel.geospatialPose!!,
                    destination = viewModel.selectedDestination!!,
                    latOffset = viewModel.latOffset,
                    lonOffset = viewModel.lonOffset
                )
            }

            GeospatialBottomOverlay(viewModel = viewModel)
        }
    }
}

/**
 * Horizontal scrollable bar that visualises the A* path as a sequence of
 * directional arrows — one per segment — bookended by a green start pin
 * and a red end pin.
 *
 * Each arrow is rotated to the compass bearing of its segment (0° = north,
 * clockwise), giving an at-a-glance overview of the full route shape.
 */
@Composable
fun PathArrowsOverlay(pathNodes: List<NavNode>) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0D1F0D).copy(alpha = 0.92f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "A★ Path  ·  ${pathNodes.size} waypoints  ·  ${pathNodes.size - 1} segments",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64FFDA)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Green start pin
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Start",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(22.dp)
                )

                // One arrow per segment, rotated to the segment's compass bearing
                pathNodes.zipWithNext().forEach { (from, to) ->
                    val bearing = segmentBearing(from.lat, from.lon, to.lat, to.lon).toFloat()
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(bearing),
                        tint = Color(0xFF64FFDA)
                    )
                }

                // Red end pin
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "End",
                    tint = Color(0xFFF44336),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Returns the compass bearing in degrees (0 = north, clockwise) from
 * (lat1, lon1) to (lat2, lon2).
 */
private fun segmentBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon  = Math.toRadians(lon2 - lon1)
    val lat1R = Math.toRadians(lat1)
    val lat2R = Math.toRadians(lat2)
    val y = sin(dLon) * cos(lat2R)
    val x = cos(lat1R) * sin(lat2R) - sin(lat1R) * cos(lat2R) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

@Composable
fun DestinationSelector(viewModel: ARViewModel) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Target Destination:", style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(
                    onClick = { viewModel.isDropdownExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(viewModel.selectedDestination?.name ?: "Select Room")
                }
                DropdownMenu(
                    expanded = viewModel.isDropdownExpanded,
                    onDismissRequest = { viewModel.isDropdownExpanded = false }
                ) {
                    viewModel.allLocations.forEach { loc ->
                        DropdownMenuItem(
                            text = { Text(loc.name) },
                            onClick = { viewModel.onDestinationSelected(loc) }
                        )
                    }
                }
            }
        }
    }
}
