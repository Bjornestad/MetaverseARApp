package com.example.metaversearapp.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * The full top HUD stack: recording indicator bar, status message, and VPS readout.
 */
@Composable
internal fun RecordingTopHud(
    isRecording:       Boolean,
    sessionNodes:      Int,
    sessionEdges:      Int,
    statusMsg:         String,
    geospatialPose:    GeospatialPose?,
    earthTrackingState: TrackingState,
    isCalibrated:      Boolean,
    latOffset:         Double,
    lonOffset:         Double,
    altOffset:         Double,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RecordingIndicatorCard(isRecording, sessionNodes, sessionEdges)
        StatusMessageCard(statusMsg)
        if (geospatialPose != null) {
            VpsReadoutCard(geospatialPose, earthTrackingState, isCalibrated, latOffset, lonOffset, altOffset)
        }
    }
}

/** Red dot + REC/STANDBY label, plus node/edge counters. */
@Composable
private fun RecordingIndicatorCard(isRecording: Boolean, sessionNodes: Int, sessionEdges: Int) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (isRecording) Color.Red else Color.Gray,
                            shape = CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRecording) "REC" else "STANDBY",
                    color      = if (isRecording) Color.Red else Color.Gray,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                "Nodes: $sessionNodes   Edges: $sessionEdges",
                color      = Color(0xFF64FFDA),
                fontFamily = FontFamily.Monospace,
                fontSize   = 12.sp
            )
        }
    }
}

/** One-line status text card. */
@Composable
private fun StatusMessageCard(statusMsg: String) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
        shape    = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            statusMsg,
            modifier = Modifier.padding(10.dp),
            color    = Color.White,
            fontSize = 13.sp
        )
    }
}

/** VPS tracking state + calibrated lat/lon/alt readout. */
@Composable
private fun VpsReadoutCard(
    pose:               GeospatialPose,
    earthTrackingState: TrackingState,
    isCalibrated:       Boolean,
    latOffset:          Double,
    lonOffset:          Double,
    altOffset:          Double,
) {
    val trackColor = if (earthTrackingState == TrackingState.TRACKING) Color(0xFF64FFDA) else Color.Red
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
        shape    = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                "VPS: ${earthTrackingState.name}",
                color      = trackColor,
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            if (isCalibrated) {
                Text(
                    String.format(
                        Locale.US,
                        "%.6f, %.6f  alt %.1fm",
                        pose.latitude  - latOffset,
                        pose.longitude - lonOffset,
                        pose.altitude  - altOffset
                    ),
                    color      = Color.White,
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ── Bottom controls panel ──────────────────────────────────────────────────────

/**
 * Scrollable bottom panel: floor selector, mark-waypoint card, scan QR,
 * start/stop recording, and finish session.
 */
@Composable
internal fun RecordingControlsPanel(
    currentFloor:     String,
    onFloorChange:    (String) -> Unit,
    lastRecordedNode: NavNode?,
    lastNodeType:     NodeType,
    onMarkAs:         (NodeType) -> Unit,
    isScanning:       Boolean,
    onScanToggle:     () -> Unit,
    isRecording:      Boolean,
    onRecordToggle:   () -> Unit,
    onFinished:       () -> Unit,
) {
    Column(
        modifier            = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FloorSelectorCard(currentFloor, onFloorChange)
        MarkWaypointCard(lastRecordedNode, lastNodeType, onMarkAs)
        ScanQrButton(isScanning, onScanToggle)
        RecordToggleButton(isRecording, onRecordToggle)
        FinishSessionButton(onFinished)
    }
}

/** Teal chip row for selecting the current floor. */
@Composable
private fun FloorSelectorCard(currentFloor: String, onFloorChange: (String) -> Unit) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("Floor", color = Color.Gray, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FLOOR_OPTIONS.forEach { fl ->
                    FilterChip(
                        selected = currentFloor == fl,
                        onClick  = { onFloorChange(fl) },
                        label    = { Text(fl, fontSize = 12.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF64FFDA),
                            selectedLabelColor     = Color.Black
                        )
                    )
                }
            }
        }
    }
}

/** Door / Stair-top / Stair-middle / Stair-bottom marker buttons, with active-type badge. */
@Composable
private fun MarkWaypointCard(
    lastRecordedNode: NavNode?,
    lastNodeType:     NodeType,
    onMarkAs:         (NodeType) -> Unit,
) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Mark last waypoint", color = Color.Gray, fontSize = 11.sp)
                if (lastRecordedNode != null) {
                    val (typeLabel, typeColor) = when (lastNodeType) {
                        NodeType.DOOR         -> "DOOR"       to Color(0xFFFFA726)
                        NodeType.STAIR_TOP    -> "STAIR TOP"  to Color(0xFF66BB6A)
                        NodeType.STAIR_MIDDLE -> "STAIR MID"  to Color(0xFF42A5F5)
                        NodeType.STAIR_BOTTOM -> "STAIR BTM"  to Color(0xFFEF5350)
                        NodeType.WAYPOINT     -> "WAYPOINT"   to Color.Gray
                    }
                    Text(
                        typeLabel,
                        color      = typeColor,
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // Row 1: Door (full width)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                NodeTypeButton(
                    label       = "Door",
                    icon        = { Icon(Icons.Default.DoorFront, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    enabled     = lastRecordedNode != null,
                    isActive    = lastNodeType == NodeType.DOOR,
                    activeColor = Color(0xFFFFA726),
                    onClick     = { onMarkAs(NodeType.DOOR) }
                )
            }
            Spacer(Modifier.height(4.dp))
            // Row 2: Stair Top / Stair Mid / Stair Bottom
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                NodeTypeButton(
                    label       = "Stair ↑",
                    icon        = { Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    enabled     = lastRecordedNode != null,
                    isActive    = lastNodeType == NodeType.STAIR_TOP,
                    activeColor = Color(0xFF66BB6A),
                    onClick     = { onMarkAs(NodeType.STAIR_TOP) }
                )
                NodeTypeButton(
                    label       = "Stair ↕",
                    icon        = { Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    enabled     = lastRecordedNode != null,
                    isActive    = lastNodeType == NodeType.STAIR_MIDDLE,
                    activeColor = Color(0xFF42A5F5),
                    onClick     = { onMarkAs(NodeType.STAIR_MIDDLE) }
                )
                NodeTypeButton(
                    label       = "Stair ↓",
                    icon        = { Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    enabled     = lastRecordedNode != null,
                    isActive    = lastNodeType == NodeType.STAIR_BOTTOM,
                    activeColor = Color(0xFFEF5350),
                    onClick     = { onMarkAs(NodeType.STAIR_BOTTOM) }
                )
            }
        }
    }
}

/** A single outlined marker button (Door / Stair ↑ / Stair ↓). */
@Composable
private fun RowScope.NodeTypeButton(
    label:       String,
    icon:        @Composable () -> Unit,
    enabled:     Boolean,
    isActive:    Boolean,
    activeColor: Color,
    onClick:     () -> Unit,
) {
    OutlinedButton(
        onClick        = onClick,
        enabled        = enabled,
        modifier       = Modifier.weight(1f),
        shape          = RoundedCornerShape(8.dp),
        colors         = ButtonDefaults.outlinedButtonColors(contentColor = activeColor),
        border         = androidx.compose.foundation.BorderStroke(
            1.dp, if (isActive) activeColor else activeColor.copy(alpha = 0.4f)
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    ) {
        icon()
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp)
    }
}

/** Blue / orange button that toggles QR calibration scanning. */
@Composable
private fun ScanQrButton(isScanning: Boolean, onToggle: () -> Unit) {
    Button(
        onClick  = onToggle,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape    = RoundedCornerShape(12.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor = if (isScanning) Color(0xFFFF6B35) else Color(0xFF1E88E5)
        )
    ) {
        Icon(
            if (isScanning) Icons.Default.Close else Icons.Default.QrCodeScanner,
            contentDescription = null
        )
        Spacer(Modifier.width(8.dp))
        Text(if (isScanning) "Cancel Scan" else "Scan QR (calibrate / anchor)")
    }
}

/** Teal start / red stop button for the recording segment. */
@Composable
private fun RecordToggleButton(isRecording: Boolean, onToggle: () -> Unit) {
    Button(
        onClick  = onToggle,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape    = RoundedCornerShape(12.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor = if (isRecording) Color.Red else Color(0xFF64FFDA)
        )
    ) {
        Icon(
            if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = if (isRecording) Color.White else Color.Black
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (isRecording) "Stop Segment" else "Start / Resume Recording",
            color      = if (isRecording) Color.White else Color.Black,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Grey outlined button that ends the admin recording session. */
@Composable
private fun FinishSessionButton(onFinished: () -> Unit) {
    OutlinedButton(
        onClick  = onFinished,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape    = RoundedCornerShape(12.dp),
        border   = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray),
        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray)
    ) {
        Icon(Icons.Default.Check, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Finish Session")
    }
}

// ── Door → QR link picker dialog ───────────────────────────────────────────────

/**
 * Dialog shown after the admin marks a node as DOOR and nearby QR anchors
 * are found.  The admin picks which room the door belongs to, or skips.
 */
@Composable
internal fun DoorLinkPickerDialog(
    candidates: List<Pair<QrLocation, Double>>,
    onLink:     (QrLocation) -> Unit,
    onDismiss:  () -> Unit,
) {
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Nearby rooms — choose the one this door leads to:",
                    color    = Color.Gray,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(4.dp))
                candidates.forEach { (qr, dist) ->
                    OutlinedButton(
                        onClick  = { onLink(qr) },
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(8.dp),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64FFDA).copy(alpha = 0.6f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(qr.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                "${dist.toInt()} m away  ·  ${qr.building}  ·  Floor ${qr.floor}",
                                color    = Color.Gray,
                                fontSize = 11.sp
                            )
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
