package com.example.metaversearapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metaversearapp.data.NavNode
import com.example.metaversearapp.ui.ARViewModel
import com.google.ar.core.TrackingState
import kotlin.math.*

@Composable
fun ARUiOverlay(
    viewModel          : ARViewModel,
    showDebug          : Boolean       = false,
    remainingWaypoints : Int           = 0,
    remainingPath      : List<NavNode> = emptyList()
) {
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
            // Show a waiting message until GPS/VPS has actually locked on,
            // then fall back to the ViewModel status only while navigating.
            val displayStatus = when {
                viewModel.earthTrackingState != TrackingState.TRACKING ->
                    "Connecting to GPS…"
                viewModel.selectedDestination == null ->
                    "Ready: select destination"
                else ->
                    viewModel.statusText
            }
            StatusOverlay(
                status              = displayStatus,
                trackingState       = viewModel.earthTrackingState,
                isCloudAnchorActive = viewModel.isResolvingCloudAnchor ||
                                      viewModel.lastCloudAnchorInfo != null,
                cloudAnchorResolved = viewModel.lastCloudAnchorInfo != null
            )

            // HUD compass — shown whenever VPS is tracking
            viewModel.geospatialPose?.let { pose ->
                Spacer(modifier = Modifier.height(4.dp))
                HUDCompass(
                    heading             = (pose.heading + viewModel.headingOffset + 360.0) % 360.0,
                    destinationBearing  = viewModel.destinationBearing,
                    nextWaypointBearing = viewModel.nextWaypointBearing,
                    modifier            = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                )
            }

            // ── Floor change warning ─────────────────────────────────────────
            if (remainingPath.size >= 2) {
                Spacer(modifier = Modifier.height(4.dp))
                FloorChangeWarning(remainingPath = remainingPath)
            }

            // ── Debug-only controls ──────────────────────────────────────────
            if (showDebug) {
                // Combined route card: arrow overview + waypoints remaining
                if (viewModel.destinationPathNodes.size >= 2) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = OverlayBackground)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            // Header: destination name + remaining count
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Route to ${viewModel.selectedDestination?.name ?: "destination"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFFCC02)
                                )
                                if (remainingWaypoints > 0) {
                                    Text(
                                        "$remainingWaypoints remaining",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFFFCC02).copy(alpha = 0.75f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            // Arrow strip
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(Icons.Default.LocationOn, contentDescription = "Start",
                                    tint = Color(0xFF4CAF50), modifier = Modifier.size(22.dp))
                                viewModel.destinationPathNodes.zipWithNext().forEach { (from, to) ->
                                    val bearing = segmentBearing(from.lat, from.lon, to.lat, to.lon).toFloat()
                                    Icon(Icons.Default.ArrowUpward, contentDescription = null,
                                        modifier = Modifier.size(20.dp).rotate(bearing),
                                        tint = Color(0xFFFFCC02))
                                }
                                Icon(Icons.Default.LocationOn, contentDescription = "End",
                                    tint = Color(0xFFF44336), modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }

                // A* test-pin waypoint button + arrow preview
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

        // ── BOTTOM-LEFT: debug overlay stacked above minimap ────────────────
        // Same start/width as the minimap so they form a tidy column.
        // bottom = control-card clearance (82) + minimap height (130) + gap (4)
        if (showDebug) {
            GeospatialBottomOverlay(
                viewModel = viewModel,
                modifier  = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 216.dp)
                    .width(130.dp)
            )
        }

        // ── BOTTOM: control bar ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

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

                }
            }
        }

        // ── BOTTOM-LEFT: minimap ─────────────────────────────────────────────
        MiniMap(
            viewModel = viewModel,
            modifier  = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 82.dp)  // clears the control card
        )

        // ── Arrival notification ─────────────────────────────────────────────
        if (viewModel.showArrivalBanner) {
            // Auto-dismiss after 5 seconds
            LaunchedEffect(Unit) {
                delay(5_000)
                viewModel.dismissArrivalBanner()
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = OverlayBackground),
                    border = BorderStroke(1.dp, Color(0xFF64FFDA).copy(alpha = 0.8f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector        = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint               = Color(0xFF64FFDA),
                            modifier           = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "You've arrived",
                            color      = Color.White,
                            fontSize   = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            viewModel.arrivedAtName,
                            color    = Color(0xFF64FFDA),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { viewModel.dismissArrivalBanner() },
                            colors  = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White.copy(alpha = 0.6f)
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Amber warning card shown when a floor transition is detected within the
 * first [lookAhead] nodes of [remainingPath].  Shows the direction (↑/↓) and
 * target floor number.  Renders nothing if no transition is imminent.
 */
@Composable
fun FloorChangeWarning(
    remainingPath : List<NavNode>,
    lookAhead     : Int = 10
) {
    // Find the first floor transition in the look-ahead window
    val segment    = remainingPath.take(lookAhead + 1)
    val transition = segment.zipWithNext().firstOrNull { (a, b) -> a.floor != b.floor } ?: return

    val targetFloor     = transition.second.floor
    val currentFloorInt = transition.first.floor.toIntOrNull()
    val targetFloorInt  = targetFloor.toIntOrNull()
    val isGoingUp = when {
        currentFloorInt != null && targetFloorInt != null -> targetFloorInt > currentFloorInt
        else -> true
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1200).copy(alpha = 0.92f)
        ),
        border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.8f))
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector        = if (isGoingUp) Icons.Default.ArrowUpward
                                     else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint               = Color(0xFFFFB300),
                modifier           = Modifier.size(18.dp)
            )
            Text(
                text  = if (isGoingUp) "Head up  ·  floor $targetFloor"
                        else           "Head down  ·  floor $targetFloor",
                color = Color(0xFFFFCC80),
                style = MaterialTheme.typography.labelMedium
            )
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

// ═══════════════════════════════════════════════════════════════════════════
// MINIMAP
// ═══════════════════════════════════════════════════════════════════════════

/**
 * A small top-down map that shows:
 *  • nearby nav-graph nodes (blue dots) and edges (faint blue lines)
 *  • the active destination path (amber line + destination dot)
 *  • the user's position (white dot) with a heading cone (teal triangle)
 *  • a north "N" label and a range legend
 *
 * The map is centred on the user and covers [mapRadiusM] metres in every
 * direction.  Coordinates are projected with a simple equirectangular
 * approximation, which is accurate to within a fraction of a percent at
 * indoor scales (≤ 100 m).
 *
 * Returns early (renders nothing) when VPS is not yet tracking.
 */
@Composable
fun MiniMap(
    viewModel  : ARViewModel,
    modifier   : Modifier = Modifier,
    sizeDp     : Dp       = 130.dp,
    mapRadiusM : Double   = 25.0
) {
    val pose = viewModel.geospatialPose ?: return   // hide until VPS locks

    val userLat = pose.latitude  - viewModel.latOffset
    val userLon = pose.longitude - viewModel.lonOffset
    val heading = (pose.heading  + viewModel.headingOffset + 360.0) % 360.0

    val nodes     = viewModel.navNodes
    val edges     = viewModel.navEdges
    val pathNodes = viewModel.destinationPathNodes

    // Build a node-id → node lookup once per node-list change.
    val nodeMap = remember(nodes) { nodes.associateBy { it.id } }

    Card(
        modifier = modifier.size(sizeDp),
        shape    = RoundedCornerShape(10.dp),
        colors   = CardDefaults.cardColors(
            containerColor = Color(0xFF0D1117).copy(alpha = 0.88f)
        ),
        border   = BorderStroke(1.dp, Color(0xFF2A2A3E))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            Canvas(modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
            ) {
                val w  = size.width
                val h  = size.height
                val cx = w / 2f
                val cy = h / 2f

                // Equirectangular projection helpers
                val mPerDegLat = 111_111.0
                val mPerDegLon = 111_111.0 * cos(Math.toRadians(userLat))

                fun toX(lon: Double) =
                    (cx + (lon - userLon) * mPerDegLon / mapRadiusM * cx).toFloat()
                fun toY(lat: Double) =
                    (cy - (lat - userLat) * mPerDegLat / mapRadiusM * cy).toFloat()
                fun inRange(lat: Double, lon: Double): Boolean {
                    val dx = (lon - userLon) * mPerDegLon
                    val dy = (lat - userLat) * mPerDegLat
                    return sqrt(dx * dx + dy * dy) <= mapRadiusM * 1.3
                }

                // ── Nav edges ───────────────────────────────────────────────
                edges.forEach { edge ->
                    val a = nodeMap[edge.fromId] ?: return@forEach
                    val b = nodeMap[edge.toId]   ?: return@forEach
                    if (!inRange(a.lat, a.lon) && !inRange(b.lat, b.lon)) return@forEach
                    drawLine(
                        color       = Color(0xFF38BDF8).copy(alpha = 0.18f),
                        start       = Offset(toX(a.lon), toY(a.lat)),
                        end         = Offset(toX(b.lon), toY(b.lat)),
                        strokeWidth = 1.2f
                    )
                }

                // ── Nav nodes ───────────────────────────────────────────────
                nodes.forEach { node ->
                    if (!inRange(node.lat, node.lon)) return@forEach
                    drawCircle(
                        color  = Color(0xFF38BDF8).copy(alpha = 0.45f),
                        radius = 2.5f,
                        center = Offset(toX(node.lon), toY(node.lat))
                    )
                }

                // ── Destination path ─────────────────────────────────────────
                if (pathNodes.size >= 2) {
                    for (i in 0 until pathNodes.size - 1) {
                        val a = pathNodes[i]
                        val b = pathNodes[i + 1]
                        drawLine(
                            color       = Color(0xFFFFB300).copy(alpha = 0.85f),
                            start       = Offset(toX(a.lon), toY(a.lat)),
                            end         = Offset(toX(b.lon), toY(b.lat)),
                            strokeWidth = 2.5f
                        )
                    }
                    // Destination marker dot
                    drawCircle(
                        color  = Color(0xFFFFB300),
                        radius = 4f,
                        center = Offset(toX(pathNodes.last().lon), toY(pathNodes.last().lat))
                    )
                }

                // ── Heading cone ─────────────────────────────────────────────
                // Triangle drawn pointing up (North), then rotated clockwise by
                // the corrected compass heading so it always faces the user's
                // actual direction.
                val coneH = 16f
                val coneW = 9f
                val conePath = Path().apply {
                    moveTo(cx, cy - coneH)          // tip
                    lineTo(cx - coneW / 2f, cy)     // base-left
                    lineTo(cx + coneW / 2f, cy)     // base-right
                    close()
                }
                rotate(degrees = heading.toFloat(), pivot = Offset(cx, cy)) {
                    drawPath(conePath, color = Color(0xFF64FFDA).copy(alpha = 0.9f))
                }

                // ── User dot (white ring) ─────────────────────────────────────
                drawCircle(color = Color.White,          radius = 5f,   center = Offset(cx, cy))
                drawCircle(color = Color(0xFF0D1117),    radius = 2.5f, center = Offset(cx, cy))
            }

            // North label
            Text(
                "N",
                modifier   = Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
                color      = Color.White.copy(alpha = 0.4f),
                fontSize   = 8.sp,
                fontWeight = FontWeight.Bold
            )

            // Range legend
            Text(
                "${mapRadiusM.toInt()}m",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 2.dp),
                color    = Color.White.copy(alpha = 0.3f),
                fontSize = 8.sp
            )
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
