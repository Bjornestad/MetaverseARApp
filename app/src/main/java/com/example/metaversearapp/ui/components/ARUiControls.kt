package com.example.metaversearapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.QrCodeScanner
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
fun ARUiOverlay(viewModel: ARViewModel, showDebug: Boolean = false) {
    // Dialog lives outside the positioned columns so it can cover the full screen
    DestinationSelector(viewModel)

    Box(modifier = Modifier.fillMaxSize()) {

        // ── TOP: status + route overview ─────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusOverlay(viewModel.statusText)

            // 2-D path overview for the room-destination route
            if (viewModel.destinationPathNodes.size >= 2) {
                Spacer(modifier = Modifier.height(4.dp))
                PathArrowsOverlay(
                    pathNodes   = viewModel.destinationPathNodes,
                    label       = "Route to ${viewModel.selectedDestination?.name ?: "destination"}",
                    accentColor = Color(0xFFFFCC02)
                )
            }

            // ── Debug-only controls ──────────────────────────────────────────
            if (showDebug) {
                Spacer(modifier = Modifier.height(4.dp))
                val (buttonLabel, buttonColor) = when (viewModel.waypointMode) {
                    ARViewModel.WaypointMode.AWAIT_START -> "Set Start Point" to Color(0xFF1565C0)
                    ARViewModel.WaypointMode.AWAIT_END   -> "Set End Point"   to Color(0xFF2E7D32)
                    ARViewModel.WaypointMode.PATH_READY  -> "Clear Waypoints" to Color(0xFFB71C1C)
                }
                Button(
                    onClick = { viewModel.placeWaypoint() },
                    colors  = ButtonDefaults.buttonColors(containerColor = buttonColor)
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(buttonLabel)
                }
                if (viewModel.testPathNodes.size >= 2) {
                    Spacer(modifier = Modifier.height(4.dp))
                    PathArrowsOverlay(pathNodes = viewModel.testPathNodes)
                }
            }
        }

        // ── BOTTOM: compass + control bar + debug overlay ────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // VPS debug card — only shown when debug mode is on
            if (showDebug) {
                GeospatialBottomOverlay(viewModel = viewModel)
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Navigation compass arrow
            if (viewModel.selectedDestination != null && viewModel.geospatialPose != null) {
                NavigationArrow(
                    currentPose = viewModel.geospatialPose!!,
                    destination = viewModel.selectedDestination!!,
                    latOffset   = viewModel.latOffset,
                    lonOffset   = viewModel.lonOffset
                )
            }

            // ── Control bar ──────────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = OverlayBackground)
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Destination picker
                    OutlinedIconButton(
                        onClick = { viewModel.isDropdownExpanded = true },
                        colors  = IconButtonDefaults.outlinedIconButtonColors(
                            contentColor = Color(0xFF64FFDA)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF64FFDA).copy(alpha = 0.7f))
                    ) {
                        Icon(Icons.Default.MeetingRoom, contentDescription = "Select destination")
                    }

                    // Room name (or "not calibrated" hint when nothing selected)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text     = viewModel.selectedDestination?.name ?: "Select room…",
                            color    = if (viewModel.selectedDestination != null) Color.White
                                       else Color.White.copy(alpha = 0.4f),
                            fontSize = 13.sp
                        )
                        if (!viewModel.isCalibrated) {
                            Text(
                                "⚠ Not calibrated",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFCC80)
                            )
                        }
                    }

                    // QR scan / cancel
                    val scanColor = if (viewModel.isScanning) Color(0xFFFF5252)
                                    else if (!viewModel.isCalibrated) Color(0xFFFFCC80)
                                    else Color(0xFF64FFDA)
                    OutlinedIconButton(
                        onClick = { viewModel.toggleScanning() },
                        colors  = IconButtonDefaults.outlinedIconButtonColors(contentColor = scanColor),
                        border  = BorderStroke(1.dp, scanColor.copy(alpha = 0.7f))
                    ) {
                        Icon(
                            imageVector = if (viewModel.isScanning) Icons.Default.Close
                                          else Icons.Default.QrCodeScanner,
                            contentDescription = if (viewModel.isScanning) "Cancel scan"
                                                 else "Scan QR to calibrate"
                        )
                    }

                    // Mid-corridor centroid calibration
                    val corridorEnabled = !viewModel.isScanning && !viewModel.isCorridorCalibrating
                    val corridorColor   = if (corridorEnabled) Color(0xFFFFCC80)
                                         else Color(0xFFFFCC80).copy(alpha = 0.3f)
                    OutlinedIconButton(
                        onClick  = { viewModel.calibrateAtCorridorCentroid() },
                        enabled  = corridorEnabled,
                        colors   = IconButtonDefaults.outlinedIconButtonColors(
                            contentColor         = corridorColor,
                            disabledContentColor = corridorColor
                        ),
                        border = BorderStroke(1.dp, corridorColor.copy(alpha = 0.7f))
                    ) {
                        Icon(Icons.Default.LinearScale, contentDescription = "Calibrate at corridor midpoint")
                    }
                }
            }
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
        colors = CardDefaults.cardColors(containerColor = OverlayBackground),
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

/**
 * Room picker dialog — the trigger button lives in the compact top bar in [ARUiOverlay];
 * this composable only renders the dialog itself.
 */
@Composable
fun DestinationSelector(viewModel: ARViewModel) {
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(searchQuery, viewModel.allLocations) {
        val q = searchQuery.trim()
        if (q.isBlank()) viewModel.allLocations
        else viewModel.allLocations.filter { it.name.contains(q, ignoreCase = true) }
    }

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
