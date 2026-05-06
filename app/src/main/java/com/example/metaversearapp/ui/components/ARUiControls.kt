package com.example.metaversearapp.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

            // 2-D path overview for the room-destination route
            if (viewModel.destinationPathNodes.size >= 2) {
                Spacer(modifier = Modifier.height(4.dp))
                PathArrowsOverlay(
                    pathNodes   = viewModel.destinationPathNodes,
                    label       = "Route to ${viewModel.selectedDestination?.name ?: "destination"}",
                    accentColor = Color(0xFFFFCC02)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Calibration banner — shown until the user scans a QR code this session.
            // Without calibration the VPS coordinate space may be drifted from the
            // stored nav graph, causing path arrows to appear in the wrong location.
            if (!viewModel.isCalibrated) {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF7A3E00).copy(alpha = 0.92f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("⚠", fontSize = 16.sp)
                        Text(
                            "Not calibrated — scan a QR code for accurate path arrows",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFCC80)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Button(
                onClick = { viewModel.toggleScanning() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.isScanning) Color.Red
                                     else if (!viewModel.isCalibrated) Color(0xFF1565C0)
                                     else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (viewModel.isScanning) "Cancel Scan" else "Scan QR to Calibrate")
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

            // Path arrows — visible once A* has produced a result for the test pins
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
/**
 * Horizontal scrollable bar that visualises an A* path as a sequence of
 * directional arrows — one per segment — bookended by location pins.
 *
 * @param pathNodes   The ordered list of nav nodes forming the path.
 * @param label       Header text.  Defaults to "A★ Path · N waypoints · M segments".
 * @param accentColor Tint for the arrows and header.  Defaults to teal (test-pin path).
 */
@Composable
fun PathArrowsOverlay(
    pathNodes: List<NavNode>,
    label: String? = null,
    accentColor: Color = Color(0xFF64FFDA)
) {
    val headerText = label
        ?: "A★ Path  ·  ${pathNodes.size} waypoints  ·  ${pathNodes.size - 1} segments"

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
                text  = headerText,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Start pin
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Start",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(22.dp)
                )

                // One arrow per segment, rotated to its compass bearing
                pathNodes.zipWithNext().forEach { (from, to) ->
                    val bearing = segmentBearing(from.lat, from.lon, to.lat, to.lon).toFloat()
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(bearing),
                        tint = accentColor
                    )
                }

                // End pin
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
    // Local search query — reset whenever the dialog closes
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(searchQuery, viewModel.allLocations) {
        val q = searchQuery.trim()
        if (q.isBlank()) viewModel.allLocations
        else viewModel.allLocations.filter { it.name.contains(q, ignoreCase = true) }
    }

    // ── Trigger button ────────────────────────────────────────────────────────
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
            OutlinedButton(
                onClick   = { viewModel.isDropdownExpanded = true },
                modifier  = Modifier.fillMaxWidth()
            ) {
                Text(viewModel.selectedDestination?.name ?: "Select Room")
            }
        }
    }

    // ── Picker dialog ─────────────────────────────────────────────────────────
    if (viewModel.isDropdownExpanded) {
        AlertDialog(
            onDismissRequest = {
                viewModel.isDropdownExpanded = false
                searchQuery = ""
            },
            containerColor = Color(0xFF1A1A2E),
            title = {
                Text(
                    "Select destination",
                    color    = Color(0xFF64FFDA),
                    fontSize = 16.sp
                )
            },
            text = {
                Column {
                    // Search field
                    OutlinedTextField(
                        value         = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder   = { Text("Search rooms…", color = Color.Gray) },
                        leadingIcon   = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray)
                        },
                        singleLine    = true,
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Color(0xFF64FFDA),
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White,
                            cursorColor          = Color(0xFF64FFDA)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    when {
                        viewModel.allLocations.isEmpty() -> {
                            Text(
                                "No rooms loaded yet",
                                color    = Color.Gray,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        filtered.isEmpty() -> {
                            Text(
                                "No rooms match \"$searchQuery\"",
                                color    = Color.Gray,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                            ) {
                                items(filtered, key = { it.qrID }) { loc ->
                                    TextButton(
                                        onClick  = {
                                            viewModel.onDestinationSelected(loc)
                                            viewModel.isDropdownExpanded = false
                                            searchQuery = ""
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.Start
                                        ) {
                                            Text(
                                                loc.name,
                                                color    = Color.White,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                "${loc.building}  ·  Floor ${loc.floor}",
                                                color    = Color.Gray,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.isDropdownExpanded = false
                        searchQuery = ""
                    }
                ) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}
