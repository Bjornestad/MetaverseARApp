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
import com.example.metaversearapp.data.NodeType
import com.example.metaversearapp.data.QrLocation
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

    // Door assignment management
    var doorNodes        by remember { mutableStateOf<List<NavNode>>(emptyList()) }
    var allQrLocations   by remember { mutableStateOf<List<QrLocation>>(emptyList()) }
    var showDoorMgmtDlg  by remember { mutableStateOf(false) }
    var reLinkTarget     by remember { mutableStateOf<NavNode?>(null) }
    var showReLinkDlg    by remember { mutableStateOf(false) }
    var reLinkCandidates by remember { mutableStateOf<List<Pair<QrLocation, Double>>>(emptyList()) }

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
                    // Upsert — existing IDs get overwritten with identical Gist data (no-op),
                    // locally recorded nodes (not yet in Gist) are untouched.
                    export.nodes.forEach { db.navDao().insertNode(it) }
                    export.edges.forEach { db.navDao().insertEdge(it) }
                    // Floor altitudes from the Gist are the canonical values — upsert so all
                    // devices share the same reference altitude for each floor.
                    export.floorAltitudes.forEach { (floor, alt) ->
                        db.floorAltDao().upsert(FloorAltitude(floor, alt))
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
                    .groupBy { it.floor }
                    .forEach { (floor, nodes) ->
                        val sorted = nodes.map { it.alt }.sorted()
                        db.floorAltDao().upsert(FloorAltitude(floor, sorted[sorted.size / 2]))
                    }
            }
        }

        // Refresh counts from DB (covers both first entry and post-recording re-entry)
        nodeCount      = db.navDao().nodeCount()
        edgeCount      = db.navDao().edgeCount()
        doorNodes      = db.navDao().getNodesByType(NodeType.DOOR.name)
        allQrLocations = db.qrDao().getAll()
        isSyncing      = false
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
                        val nodes        = db.navDao().getAllNodes()
                        val edges        = db.navDao().getAllEdges()
                        val floorAlts    = db.floorAltDao().getAll()
                            .associate { it.floor to it.alt }
                        val result = NavGistSync.upload(nodes, edges, floorAlts)
                        isUploading  = false
                        uploadStatus = result.fold(
                            onSuccess = { "✓ Uploaded ${nodes.size} nodes, ${edges.size} edges to Gist" },
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

            // Manage door assignments
            val unlinkedDoors = doorNodes.count { it.anchorQrId == null }
            OutlinedButton(
                onClick  = { showDoorMgmtDlg = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (unlinkedDoors > 0) Color(0xFFFFA726) else Color(0xFF64FFDA)
                ),
                border   = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (unlinkedDoors > 0) Color(0xFFFFA726) else Color(0xFF64FFDA)
                )
            ) {
                Icon(Icons.Default.DoorFront, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (unlinkedDoors > 0)
                        "Manage Doors  ·  $unlinkedDoors unlinked"
                    else
                        "Manage Door Assignments (${doorNodes.size})"
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
                    InstructionStep("3", "Mark nodes as Door, Stair Top/Bottom where relevant — scan QR on doors if present")
                    InstructionStep("4", "Change floor label when using stairs/elevator")
                    InstructionStep("5", "Tap 'Finish Session' when done")
                    InstructionStep("6", "Tap 'Upload Graph to Gist' — all users get the update on next app launch")
                }
            }
        }
    }

    // Door management dialog
    if (showDoorMgmtDlg) {
        DoorManagementDialog(
            doorNodes = doorNodes,
            qrMap     = allQrLocations.associateBy { it.qrID },
            onReLink  = { node ->
                reLinkTarget     = node
                reLinkCandidates = allQrLocations
                    .sortedBy { it.name }
                    .map { qr -> qr to 0.0 }
                showDoorMgmtDlg  = false
                showReLinkDlg    = true
            },
            onUnLink  = { node ->
                scope.launch {
                    db.navDao().updateNode(node.copy(anchorQrId = null, label = ""))
                    doorNodes = db.navDao().getNodesByType(NodeType.DOOR.name)
                }
            },
            onDismiss = { showDoorMgmtDlg = false }
        )
    }

    // Room picker for re-linking a specific door
    if (showReLinkDlg) {
        val capturedTarget = reLinkTarget
        DoorLinkPickerDialog(
            candidates   = reLinkCandidates,
            showDistance = false,
            onLink       = { qr ->
                showReLinkDlg    = false
                reLinkTarget     = null
                reLinkCandidates = emptyList()
                if (capturedTarget != null) {
                    scope.launch {
                        db.navDao().updateNode(capturedTarget.copy(anchorQrId = qr.qrID, label = qr.name))
                        doorNodes = db.navDao().getNodesByType(NodeType.DOOR.name)
                    }
                }
            },
            onDismiss  = {
                showReLinkDlg    = false
                reLinkTarget     = null
                reLinkCandidates = emptyList()
                showDoorMgmtDlg  = true  // return to management dialog
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
