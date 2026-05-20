package com.example.metaversearapp.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metaversearapp.data.NavNode
import com.example.metaversearapp.data.NodeType
import com.example.metaversearapp.data.QrLocation
import com.google.ar.core.GeospatialPose
import com.google.ar.core.TrackingState
import java.util.Locale

// ── Top HUD ────────────────────────────────────────────────────────────────────

/**
 * Compact single-card HUD: REC dot, status text, node counter, VPS dot,
 * and (when tracking + calibrated) lat / lon / alt on separate lines.
 */
@Composable
internal fun RecordingTopHud(
    isRecording:        Boolean,
    sessionNodes:       Int,
    sessionEdges:       Int,
    statusMsg:          String,
    geospatialPose:     GeospatialPose?,
    earthTrackingState: TrackingState,
    isCalibrated:       Boolean,
    latOffset:          Double,
    lonOffset:          Double,
    altOffset:          Double,
) {
    val vpsColor = when {
        earthTrackingState == TrackingState.TRACKING && isCalibrated -> Color(0xFF4CAF50)
        earthTrackingState == TrackingState.TRACKING                  -> Color(0xFFFFCC80)
        else                                                          -> Color(0xFFFF5252)
    }

    Card(
        colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier            = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ── Main status row ──────────────────────────────────────────────
            Row(
                modifier             = Modifier.fillMaxWidth(),
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // REC / STANDBY dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (isRecording) Color.Red else Color.Gray,
                            shape = CircleShape
                        )
                )
                // Status message (expands to fill)
                Text(
                    statusMsg,
                    modifier  = Modifier.weight(1f),
                    color     = Color.White,
                    fontSize  = 12.sp,
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis
                )
                // Node / edge counter
                Text(
                    "N:$sessionNodes E:$sessionEdges",
                    color      = Color(0xFF64FFDA),
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                // VPS status dot
                Surface(shape = CircleShape, color = vpsColor, modifier = Modifier.size(8.dp)) {}
            }

            // ── GPS readout (only when tracking and calibrated) ──────────────
            if (geospatialPose != null &&
                earthTrackingState == TrackingState.TRACKING &&
                isCalibrated
            ) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color     = Color.White.copy(alpha = 0.08f)
                )
                val gpsColor = Color(0xFF64FFDA).copy(alpha = 0.8f)
                // Row 1: Lat | Lon
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        String.format(Locale.US, "Lat %.6f", geospatialPose.latitude  - latOffset),
                        color = gpsColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    Text(
                        String.format(Locale.US, "Lon %.6f", geospatialPose.longitude - lonOffset),
                        color = gpsColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
                // Row 2: Alt | Heading
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        String.format(Locale.US, "Alt %.1fm", geospatialPose.altitude - altOffset),
                        color = gpsColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    Text(
                        String.format(Locale.US, "Hdg %.1f°", geospatialPose.heading),
                        color = gpsColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// ── Bottom controls panel ──────────────────────────────────────────────────────

/**
 * Compact bottom panel: floor selector, node-type markers, and a single row
 * of icon buttons for QR scan / record / cloud anchor / finish.
 */
@Composable
internal fun RecordingControlsPanel(
    currentFloor:      String,
    onFloorChange:     (String) -> Unit,
    lastRecordedNode:  NavNode?,
    lastNodeType:      NodeType,
    onMarkAs:          (NodeType) -> Unit,
    isScanning:        Boolean,
    onScanToggle:      () -> Unit,
    isRecording:       Boolean,
    onRecordToggle:    () -> Unit,
    onFinished:        () -> Unit,
    cloudHostState:    HostState  = HostState.Idle,
    onHostCloudAnchor: () -> Unit = {},
    canHostAnchor:     Boolean    = false,
) {
    Column(
        modifier            = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FloorSelectorCard(currentFloor, onFloorChange)
        MarkWaypointCard(lastRecordedNode, lastNodeType, onMarkAs)
        RecordingActionBar(
            isScanning        = isScanning,
            onScanToggle      = onScanToggle,
            isRecording       = isRecording,
            onRecordToggle    = onRecordToggle,
            cloudHostState    = cloudHostState,
            canHostAnchor     = canHostAnchor,
            onHostCloudAnchor = onHostCloudAnchor,
            onFinished        = onFinished
        )
    }
}

/** Compact single-row floor selector. */
@Composable
private fun FloorSelectorCard(currentFloor: String, onFloorChange: (String) -> Unit) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Floor", color = Color.Gray, fontSize = 11.sp)
            Spacer(Modifier.width(2.dp))
            FLOOR_OPTIONS.forEach { fl ->
                val selected = currentFloor == fl
                Surface(
                    onClick = { onFloorChange(fl) },
                    shape   = RoundedCornerShape(6.dp),
                    color   = if (selected) Color(0xFF64FFDA) else Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.size(width = 30.dp, height = 26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            fl,
                            fontSize   = 10.sp,
                            color      = if (selected) Color.Black else Color.White.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/** Compact single-row waypoint marker — label, active-type badge, 4 icon buttons. */
@Composable
private fun MarkWaypointCard(
    lastRecordedNode: NavNode?,
    lastNodeType:     NodeType,
    onMarkAs:         (NodeType) -> Unit,
) {
    val (typeLabel, typeColor) = when (lastNodeType) {
        NodeType.DOOR         -> "DOOR" to Color(0xFFFFA726)
        NodeType.STAIR_TOP    -> "↑"    to Color(0xFF66BB6A)
        NodeType.STAIR_MIDDLE -> "↕"    to Color(0xFF42A5F5)
        NodeType.STAIR_BOTTOM -> "↓"    to Color(0xFFEF5350)
        NodeType.WAYPOINT     -> "WPT"  to Color.Gray
    }

    Card(
        colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Mark", color = Color.Gray, fontSize = 11.sp)
            if (lastRecordedNode != null) {
                Text(
                    typeLabel,
                    color      = typeColor,
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.weight(1f))
            NodeIconButton(Icons.Default.DoorFront,    lastRecordedNode != null, lastNodeType == NodeType.DOOR,         Color(0xFFFFA726)) { onMarkAs(NodeType.DOOR) }
            NodeIconButton(Icons.Default.ArrowUpward,  lastRecordedNode != null, lastNodeType == NodeType.STAIR_TOP,    Color(0xFF66BB6A)) { onMarkAs(NodeType.STAIR_TOP) }
            NodeIconButton(Icons.Default.SwapVert,     lastRecordedNode != null, lastNodeType == NodeType.STAIR_MIDDLE, Color(0xFF42A5F5)) { onMarkAs(NodeType.STAIR_MIDDLE) }
            NodeIconButton(Icons.Default.ArrowDownward,lastRecordedNode != null, lastNodeType == NodeType.STAIR_BOTTOM, Color(0xFFEF5350)) { onMarkAs(NodeType.STAIR_BOTTOM) }
        }
    }
}

@Composable
private fun NodeIconButton(
    icon:     ImageVector,
    enabled:  Boolean,
    isActive: Boolean,
    color:    Color,
    onClick:  () -> Unit,
) {
    OutlinedIconButton(
        onClick  = onClick,
        enabled  = enabled,
        modifier = Modifier.size(36.dp),
        colors   = IconButtonDefaults.outlinedIconButtonColors(
            contentColor         = color,
            disabledContentColor = color.copy(alpha = 0.3f)
        ),
        border = BorderStroke(1.dp, if (isActive) color else color.copy(alpha = 0.35f))
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

/**
 * Single-row icon button bar: QR scan | record toggle | host anchor | finish.
 * Replaces four separate full-width buttons to reclaim vertical screen space.
 */
@Composable
private fun RecordingActionBar(
    isScanning:        Boolean,
    onScanToggle:      () -> Unit,
    isRecording:       Boolean,
    onRecordToggle:    () -> Unit,
    cloudHostState:    HostState,
    canHostAnchor:     Boolean,
    onHostCloudAnchor: () -> Unit,
    onFinished:        () -> Unit,
) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // ── QR scan ──────────────────────────────────────────────────────
            val scanColor = if (isScanning) Color(0xFFFF6B35) else Color(0xFF1E88E5)
            OutlinedIconButton(
                onClick = onScanToggle,
                colors  = IconButtonDefaults.outlinedIconButtonColors(contentColor = scanColor),
                border  = BorderStroke(1.dp, scanColor.copy(alpha = 0.7f))
            ) {
                Icon(
                    if (isScanning) Icons.Default.Close else Icons.Default.QrCodeScanner,
                    contentDescription = if (isScanning) "Cancel scan" else "Scan QR"
                )
            }

            // ── Record toggle ────────────────────────────────────────────────
            val recColor = if (isRecording) Color.Red else Color(0xFF64FFDA)
            OutlinedIconButton(
                onClick = onRecordToggle,
                colors  = IconButtonDefaults.outlinedIconButtonColors(contentColor = recColor),
                border  = BorderStroke(1.dp, recColor.copy(alpha = 0.7f))
            ) {
                Icon(
                    if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isRecording) "Stop" else "Record"
                )
            }

            // ── Host cloud anchor ────────────────────────────────────────────
            val anchorColor = when (cloudHostState) {
                is HostState.Idle    -> Color(0xFF80CBC4)
                is HostState.Hosting -> Color(0xFFFFCC80)
                is HostState.Hosted  -> Color(0xFF69F0AE)
                is HostState.Failed  -> Color(0xFFFF8A65)
            }
            OutlinedIconButton(
                onClick  = onHostCloudAnchor,
                enabled  = canHostAnchor ||
                           cloudHostState is HostState.Hosted ||
                           cloudHostState is HostState.Failed,
                colors   = IconButtonDefaults.outlinedIconButtonColors(
                    contentColor         = anchorColor,
                    disabledContentColor = anchorColor.copy(alpha = 0.35f)
                ),
                border   = BorderStroke(
                    1.dp,
                    if (canHostAnchor || cloudHostState !is HostState.Idle)
                        anchorColor.copy(alpha = 0.7f)
                    else
                        anchorColor.copy(alpha = 0.25f)
                )
            ) {
                if (cloudHostState is HostState.Hosting) {
                    CircularProgressIndicator(
                        color       = anchorColor,
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = "Host cloud anchor",
                        modifier           = Modifier.size(20.dp)
                    )
                }
            }

            // ── Finish session ───────────────────────────────────────────────
            OutlinedIconButton(
                onClick = onFinished,
                colors  = IconButtonDefaults.outlinedIconButtonColors(contentColor = Color.Gray),
                border  = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Check, contentDescription = "Finish session")
            }
        }
    }
}

// ── Door → QR link picker dialog ───────────────────────────────────────────────

/**
 * Dialog shown after the admin marks a node as DOOR.
 * All known rooms are shown in a searchable, scrollable list so the admin can
 * find and link any room regardless of its distance from the door node.
 */
@Composable
internal fun DoorLinkPickerDialog(
    candidates: List<Pair<QrLocation, Double>>,
    onLink:     (QrLocation) -> Unit,
    onDismiss:  () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, candidates) {
        if (query.isBlank()) candidates
        else candidates.filter { (qr, _) ->
            qr.name.contains(query, ignoreCase = true) ||
            qr.building.contains(query, ignoreCase = true) ||
            qr.floor.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1E1E1E),
        title = {
            Text(
                "Link door to room",
                color      = Color(0xFF64FFDA),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                // ── Search field ─────────────────────────────────────────────
                OutlinedTextField(
                    value            = query,
                    onValueChange    = { query = it },
                    placeholder      = { Text("Search rooms…", color = Color.Gray) },
                    singleLine       = true,
                    leadingIcon      = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = Color.Gray
                        )
                    },
                    trailingIcon     = if (query.isNotEmpty()) {{
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray)
                        }
                    }} else null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Color(0xFF64FFDA),
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White,
                        cursorColor          = Color(0xFF64FFDA)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // ── Results list ─────────────────────────────────────────────
                if (filtered.isEmpty()) {
                    Text(
                        "No rooms match "$query"",
                        color    = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    Column(
                        modifier            = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        filtered.forEach { (qr, dist) ->
                            OutlinedButton(
                                onClick  = { onLink(qr) },
                                modifier = Modifier.fillMaxWidth(),
                                shape    = RoundedCornerShape(8.dp),
                                colors   = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White
                                ),
                                border   = BorderStroke(1.dp, Color(0xFF64FFDA).copy(alpha = 0.6f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                ) {
                                    Text(
                                        qr.name,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize   = 14.sp
                                    )
                                    Text(
                                        buildString {
                                            append("${dist.toInt()} m away")
                                            if (qr.building.isNotBlank()) append("  ·  ${qr.building}")
                                            if (qr.floor.isNotBlank())    append("  ·  Floor ${qr.floor}")
                                        },
                                        color    = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Skip — leave unlinked", color = Color.Gray)
            }
        }
    )
}
