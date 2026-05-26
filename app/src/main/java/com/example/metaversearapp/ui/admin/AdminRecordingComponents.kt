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
) {
    val vpsColor = when {
        earthTrackingState == TrackingState.TRACKING -> Color(0xFF4CAF50)
        else                                          -> Color(0xFFFF5252)
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

            // ── GPS readout (when tracking) ──────────────────────────────────
            if (geospatialPose != null && earthTrackingState == TrackingState.TRACKING) {
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
                        String.format(Locale.US, "Lat %.6f", geospatialPose.latitude),
                        color = gpsColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    Text(
                        String.format(Locale.US, "Lon %.6f", geospatialPose.longitude),
                        color = gpsColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
                // Row 2: Alt | Heading
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        String.format(Locale.US, "Alt %.1fm", geospatialPose.altitude),
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
    currentBuilding:   String,
    onBuildingChange:  (String) -> Unit,
    currentFloor:      String,
    onFloorChange:     (String) -> Unit,
    lastRecordedNode:  NavNode?,
    lastNodeType:      NodeType,
    onMarkAs:          (NodeType) -> Unit,
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
        BuildingFloorSelectorCard(currentBuilding, onBuildingChange, currentFloor, onFloorChange)
        MarkWaypointCard(lastRecordedNode, lastNodeType, onMarkAs)
        RecordingActionBar(
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
private fun BuildingFloorSelectorCard(
    currentBuilding:  String,
    onBuildingChange: (String) -> Unit,
    currentFloor:     String,
    onFloorChange:    (String) -> Unit,
) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {

            // ── Building text field ──────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                Text("Building", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(52.dp))
                OutlinedTextField(
                    value         = currentBuilding,
                    onValueChange = onBuildingChange,
                    placeholder   = { Text("e.g. Realfagbygget", color = Color.Gray, fontSize = 11.sp) },
                    singleLine    = true,
                    textStyle     = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        color    = Color.White
                    ),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Color(0xFF64FFDA),
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White,
                        cursorColor          = Color(0xFF64FFDA)
                    ),
                    modifier      = Modifier
                        .weight(1f)
                        .height(40.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            // ── Floor chips ──────────────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                Text("Floor", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(52.dp))
                FLOOR_OPTIONS.forEach { fl ->
                    val selected = currentFloor == fl
                    Surface(
                        onClick  = { onFloorChange(fl) },
                        shape    = RoundedCornerShape(6.dp),
                        color    = if (selected) Color(0xFF64FFDA) else Color.White.copy(alpha = 0.08f),
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

/** Single-row icon button bar: record toggle | host anchor | finish. */
@Composable
private fun RecordingActionBar(
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

// ── Door assignment management dialog ──────────────────────────────────────────

/**
 * Hub-screen dialog that lists every DOOR node and its current room name.
 * The admin can rename or unlink any door.
 */
@Composable
internal fun DoorManagementDialog(
    doorNodes: List<NavNode>,
    onRename:  (NavNode) -> Unit,
    onUnLink:  (NavNode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1E1E1E),
        title = {
            Text(
                "Door Assignments",
                color      = Color(0xFF64FFDA),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (doorNodes.isEmpty()) {
                Text(
                    "No door nodes recorded yet.",
                    color    = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(
                    modifier            = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    doorNodes.forEach { node ->
                        val isLinked = node.label.isNotBlank()

                        Card(
                            colors   = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                            shape    = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier              = Modifier
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .fillMaxWidth(),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // ── Info ────────────────────────────────────
                                Column(modifier = Modifier.weight(1f)) {
                                    if (isLinked) {
                                        Text(
                                            node.label,
                                            color      = Color.White,
                                            fontSize   = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "Floor ${node.floor}  ·  ID: ${node.anchorQrId ?: "—"}",
                                            color    = Color.Gray,
                                            fontSize = 11.sp
                                        )
                                    } else {
                                        Text(
                                            "Unnamed door",
                                            color      = Color(0xFFFFCC80),
                                            fontSize   = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text("Floor ${node.floor}", color = Color.Gray, fontSize = 11.sp)
                                    }
                                }

                                // ── Rename button ────────────────────────────
                                OutlinedIconButton(
                                    onClick  = { onRename(node) },
                                    modifier = Modifier.size(32.dp),
                                    colors   = IconButtonDefaults.outlinedIconButtonColors(
                                        contentColor = Color(0xFF64FFDA)
                                    ),
                                    border   = BorderStroke(1.dp, Color(0xFF64FFDA).copy(alpha = 0.6f))
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Rename",
                                        modifier           = Modifier.size(14.dp)
                                    )
                                }

                                // ── Unlink button (only when linked) ─────────
                                if (isLinked) {
                                    OutlinedIconButton(
                                        onClick  = { onUnLink(node) },
                                        modifier = Modifier.size(32.dp),
                                        colors   = IconButtonDefaults.outlinedIconButtonColors(
                                            contentColor = Color(0xFFFF6B6B)
                                        ),
                                        border   = BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.6f))
                                    ) {
                                        Icon(
                                            Icons.Default.LinkOff,
                                            contentDescription = "Remove name",
                                            modifier           = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton  = {},
        dismissButton  = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.Gray)
            }
        }
    )
}

// ── Door name dialog ───────────────────────────────────────────────────────────

/**
 * Shown after the admin marks a node as DOOR (or renames an existing door).
 * The admin types a free-form room name; a URL-safe ID is generated automatically.
 *
 * [initialName] pre-fills the field when renaming an existing door.
 */
@Composable
internal fun DoorNameDialog(
    initialName: String    = "",
    onConfirm:   (String) -> Unit,
    onDismiss:   () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1E1E1E),
        title = {
            Text(
                if (initialName.isBlank()) "Name this room" else "Rename room",
                color      = Color(0xFF64FFDA),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter a name — it will appear in the destination picker for all users.",
                    color    = Color.Gray,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    placeholder   = { Text("e.g. Room 101, Main Entrance…", color = Color.Gray) },
                    singleLine    = true,
                    leadingIcon   = {
                        Icon(Icons.Default.MeetingRoom, contentDescription = null, tint = Color.Gray)
                    },
                    trailingIcon  = if (name.isNotEmpty()) {{
                        IconButton(onClick = { name = "" }) {
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
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled  = name.isNotBlank()
            ) {
                Text("Save", color = if (name.isNotBlank()) Color(0xFF64FFDA) else Color.Gray)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Skip", color = Color.Gray)
            }
        }
    )
}

