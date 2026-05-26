package com.example.metaversearapp.ui.admin

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metaversearapp.data.AppDatabase
import com.example.metaversearapp.data.FloorAltitude
import com.example.metaversearapp.data.NavGistSync
import com.example.metaversearapp.data.NavGraphExport
import com.example.metaversearapp.data.NavNode
import com.example.metaversearapp.data.NavWall
import com.example.metaversearapp.data.NodeType
import com.example.metaversearapp.data.Room
import com.example.metaversearapp.data.RoomGistSync
import com.example.metaversearapp.data.toRoomId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
internal fun AdminHubScreen(
    db: AppDatabase,
    onStartRecord: () -> Unit,
    onExit: () -> Unit
) {
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    var nodeCount    by remember { mutableIntStateOf(0) }
    var edgeCount    by remember { mutableIntStateOf(0) }
    var showClearDlg by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }
    var uploadStatus by remember { mutableStateOf("") }
    var isUploading  by remember { mutableStateOf(false) }

    // Door / room management
    var doorNodes       by remember { mutableStateOf<List<NavNode>>(emptyList()) }
    var showDoorMgmtDlg by remember { mutableStateOf(false) }
    var renameTarget    by remember { mutableStateOf<NavNode?>(null) }
    var showRenameDlg   by remember { mutableStateOf(false) }

    // Gist sync state — only shows spinner on the very first entry this session
    var isSyncing   by remember { mutableStateOf(!adminGistSyncDone) }
    var syncMessage by remember { mutableStateOf("Syncing latest graph from Gist…") }

    // On first entry this session: pull the latest graph from the Gist and
    // MERGE it into the local DB (no clear) so any locally recorded nodes
    // that haven't been uploaded yet are never wiped.
    // On re-entry after recording: skip the sync and just refresh counts.
    LaunchedEffect(Unit) {
        if (!adminGistSyncDone) {
            val result = NavGistSync.download()
            result.onSuccess { export ->
                withContext(Dispatchers.IO) {
                    // Load local nodes once for O(1) lookup during merge.
                    val localNodes = db.navDao().getAllNodes().associateBy { it.id }

                    export.nodes.forEach { gistNode ->
                        val local = localNodes[gistNode.id]
                        // Door assignments are admin overrides. If the local DB already has an
                        // explicit anchorQrId that differs from what the Gist says, keep the
                        // local value — the admin intentionally set it and the Gist hasn't
                        // received the upload yet (or another device set it differently).
                        // For every other field, the Gist is authoritative.
                        val nodeToInsert = if (local?.anchorQrId != null &&
                                               local.anchorQrId != gistNode.anchorQrId) {
                            gistNode.copy(anchorQrId = local.anchorQrId, label = local.label)
                        } else {
                            gistNode
                        }
                        db.navDao().insertNode(nodeToInsert)
                    }

                    export.edges.forEach { db.navDao().insertEdge(it) }
                    // Floor altitudes from the Gist are the canonical values — upsert so all
                    // devices share the same reference altitude for each floor.
                    export.floorAltitudes.forEach { fa ->
                        db.floorAltDao().upsert(fa)
                    }
                    // Walls are Gist-only (no local-only walls) — replace fully.
                    if (export.walls.isNotEmpty()) {
                        db.navWallDao().clearAll()
                        db.navWallDao().upsertAll(export.walls)
                    }
                }
            }
            adminGistSyncDone = true
            syncMessage = result.fold(
                onSuccess = { "Graph up to date" },
                onFailure = { "Could not sync from Gist — using local data" }
            )
        }
        // One-time bootstrap: if the floor altitude table is still empty after the Gist sync
        // (old Gist without floor altitudes, or first launch after adding this feature),
        // populate it from the median of existing nodes per floor so the table is immediately
        // useful without requiring a fresh recording session on every floor.
        withContext(Dispatchers.IO) {
            if (db.floorAltDao().getAll().isEmpty()) {
                db.navDao().getAllNodes()
                    .groupBy { Pair(it.building, it.floor) }
                    .forEach { (key, nodes) ->
                        val sorted = nodes.map { it.alt }.sorted()
                        db.floorAltDao().upsert(
                            FloorAltitude(
                                building = key.first,
                                floor    = key.second,
                                alt      = sorted[sorted.size / 2]
                            )
                        )
                    }
            }
        }

        // Refresh counts from DB (covers both first entry and post-recording re-entry)
        nodeCount  = db.navDao().nodeCount()
        edgeCount  = db.navDao().edgeCount()
        doorNodes  = db.navDao().getNodesByType(NodeType.DOOR.name)
        isSyncing  = false
    }

    // Show a full-screen spinner until the sync resolves
    if (isSyncing) {
        Box(
            modifier         = Modifier.fillMaxSize().background(Color(0xFF121212)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF64FFDA))
                Text(syncMessage, color = Color.Gray, fontSize = 13.sp)
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    "Admin — Mapping Mode",
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color(0xFF64FFDA)
                )
                IconButton(onClick = onExit) {
                    Icon(Icons.Default.Close, contentDescription = "Exit Admin", tint = Color.Gray)
                }
            }

            // Stats card
            Card(
                colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Graph Status", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatChip(label = "Nodes", value = nodeCount.toString())
                        StatChip(label = "Edges", value = edgeCount.toString())
                    }
                }
            }

            // Start recording
            Button(
                onClick  = onStartRecord,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF64FFDA))
            ) {
                Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Color.Red)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start Recording Walk", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            // Upload to GitHub Gist
            Button(
                onClick = {
                    scope.launch {
                        isUploading  = true
                        uploadStatus = "Uploading…"
                        val nodes     = db.navDao().getAllNodes()
                        val edges     = db.navDao().getAllEdges()
                        val floorAlts = db.floorAltDao().getAll()
                        val walls     = db.navWallDao().getAll()
                        val result    = NavGistSync.upload(nodes, edges, floorAlts, walls)
                        isUploading  = false
                        uploadStatus = result.fold(
                            onSuccess = { "✓ Uploaded ${nodes.size} nodes, ${edges.size} edges, ${walls.size} wall(s) to Gist" },
                            onFailure = { "✗ Upload failed: ${it.message}" }
                        )
                    }
                },
                enabled  = !isUploading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        color       = Color.White,
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isUploading) "Uploading…" else "Upload Graph to Gist", fontWeight = FontWeight.Bold)
            }

            if (uploadStatus.isNotEmpty()) {
                Text(
                    uploadStatus,
                    color    = if (uploadStatus.startsWith("✓")) Color(0xFF64FFDA) else Color(0xFFFF6B6B),
                    fontSize = 12.sp
                )
            }

            // Share as text (fallback / manual backup)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val nodes  = db.navDao().getAllNodes()
                        val edges  = db.navDao().getAllEdges()
                        val export = NavGraphExport(nodes, edges)
                        val json   = Json { prettyPrint = true }.encodeToString(export)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "NavGraph_Export.json")
                            putExtra(Intent.EXTRA_TEXT, json)
                        }
                        context.startActivity(Intent.createChooser(intent, "Export Nav Graph"))
                        exportStatus = "Exported ${nodes.size} nodes, ${edges.size} edges"
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64FFDA)),
                border   = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64FFDA))
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share Graph JSON")
            }

            if (exportStatus.isNotEmpty()) {
                Text(exportStatus, color = Color(0xFF64FFDA), fontSize = 12.sp)
            }

            // Clear graph
            OutlinedButton(
                onClick  = { showClearDlg = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                border   = androidx.compose.foundation.BorderStroke(1.dp, Color.Red)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear Graph Data")
            }

            // Manage door / room assignments
            val unnamedDoors = doorNodes.count { it.label.isBlank() }
            OutlinedButton(
                onClick  = { showDoorMgmtDlg = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (unnamedDoors > 0) Color(0xFFFFA726) else Color(0xFF64FFDA)
                ),
                border   = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (unnamedDoors > 0) Color(0xFFFFA726) else Color(0xFF64FFDA)
                )
            ) {
                Icon(Icons.Default.DoorFront, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (unnamedDoors > 0)
                        "Manage Rooms  ·  $unnamedDoors unnamed"
                    else
                        "Manage Rooms (${doorNodes.size})"
                )
            }

            // Instructions
            Card(
                colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A2A1A)),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("How to map a building", color = Color(0xFF64FFDA), fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    InstructionStep("1", "Tap 'Start Recording Walk'")
                    InstructionStep("2", "Walk corridors at normal speed")
                    InstructionStep("3", "Mark nodes as Door, Stair Top/Bottom where relevant — enter a room name for each door")
                    InstructionStep("4", "Change floor label when using stairs/elevator")
                    InstructionStep("5", "Tap 'Finish Session' when done")
                    InstructionStep("6", "Tap 'Upload Graph to Gist' — all users get the update on next app launch")
                }
            }
        }
    }

    // Door / room management dialog
    if (showDoorMgmtDlg) {
        DoorManagementDialog(
            doorNodes = doorNodes,
            onRename  = { node ->
                renameTarget    = node
                showDoorMgmtDlg = false
                showRenameDlg   = true
            },
            onUnLink  = { node ->
                scope.launch {
                    val updated = node.copy(anchorQrId = null, label = "")
                    db.navDao().updateNode(updated)
                    // Remove the room from the Gist if it had an ID
                    if (node.anchorQrId != null) {
                        withContext(Dispatchers.IO) { RoomGistSync.removeRoom(node.anchorQrId) }
                    }
                    doorNodes = db.navDao().getNodesByType(NodeType.DOOR.name)
                }
            },
            onDismiss = { showDoorMgmtDlg = false }
        )
    }

    // Rename dialog for an existing door room
    if (showRenameDlg) {
        val capturedTarget = renameTarget
        DoorNameDialog(
            initialName = capturedTarget?.label ?: "",
            onConfirm   = { newName ->
                showRenameDlg = false
                renameTarget  = null
                if (capturedTarget != null) {
                    scope.launch {
                        val roomId  = newName.toRoomId()
                        val updated = capturedTarget.copy(anchorQrId = roomId, label = newName)
                        db.navDao().updateNode(updated)
                        val room = Room(
                            id    = roomId,
                            name  = newName,
                            floor = capturedTarget.floor,
                            lat   = capturedTarget.lat,
                            lon   = capturedTarget.lon,
                            alt   = capturedTarget.alt
                        )
                        withContext(Dispatchers.IO) { RoomGistSync.addRoom(room) }
                        doorNodes = db.navDao().getNodesByType(NodeType.DOOR.name)
                    }
                }
            },
            onDismiss = {
                showRenameDlg   = false
                renameTarget    = null
                showDoorMgmtDlg = true   // return to management dialog
            }
        )
    }

    // Clear confirmation dialog
    if (showClearDlg) {
        AlertDialog(
            onDismissRequest = { showClearDlg = false },
            title   = { Text("Clear Graph?") },
            text    = { Text("This will delete all $nodeCount nodes and $edgeCount edges. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        db.navDao().clearEdges()
                        db.navDao().clearNodes()
                        nodeCount = 0
                        edgeCount = 0
                    }
                    showClearDlg = false
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDlg = false }) { Text("Cancel") }
            }
        )
    }
}
