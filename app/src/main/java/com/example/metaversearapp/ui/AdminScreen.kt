package com.example.metaversearapp.ui

import android.content.Intent
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.metaversearapp.data.AppDatabase
import com.example.metaversearapp.data.NavEdge
import com.example.metaversearapp.data.NavGraphExport
import com.example.metaversearapp.data.NavGraphPathfinder
import com.example.metaversearapp.data.NavNode
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.TrackingState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.sceneview.ar.ARScene
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID

// ── Constants ──────────────────────────────────────────────────────────────────
private const val ADMIN_PIN            = "1234"          // change in production
private const val MIN_NODE_DISTANCE_M  = 1.5             // metres between auto-captured nodes
private const val CAPTURE_INTERVAL_MS  = 2_000L          // max capture rate

private val FLOOR_OPTIONS = listOf("BK", "1", "2", "3", "4", "5")

// ── Top-level screen ───────────────────────────────────────────────────────────
@Composable
fun AdminScreen(db: AppDatabase, onExitAdmin: () -> Unit) {
    var isAuthenticated by remember { mutableStateOf(false) }
    var isRecording     by remember { mutableStateOf(false) }

    if (!isAuthenticated) {
        PinGateScreen(
            onSuccess = { isAuthenticated = true },
            onCancel  = onExitAdmin
        )
    } else if (!isRecording) {
        AdminHubScreen(
            db            = db,
            onStartRecord = { isRecording = true },
            onExit        = onExitAdmin
        )
    } else {
        AdminRecordingScreen(
            db        = db,
            onFinished = { isRecording = false }
        )
    }
}

// ── PIN Gate ───────────────────────────────────────────────────────────────────
@Composable
private fun PinGateScreen(onSuccess: () -> Unit, onCancel: () -> Unit) {
    var pin   by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            shape  = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(
                modifier            = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    tint   = Color(0xFF64FFDA),
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    "Admin Access",
                    fontSize   = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White
                )
                OutlinedTextField(
                    value         = pin,
                    onValueChange = { if (it.length <= 6) { pin = it; error = false } },
                    label         = { Text("PIN", color = Color.Gray) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error,
                    supportingText = if (error) ({ Text("Wrong PIN", color = Color.Red) }) else null,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Color(0xFF64FFDA),
                        unfocusedBorderColor = Color.Gray,
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier            = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel", color = Color.Gray) }

                    Button(
                        onClick = {
                            if (pin == ADMIN_PIN) onSuccess()
                            else { error = true; pin = "" }
                        },
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF64FFDA))
                    ) { Text("Enter", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ── Hub ────────────────────────────────────────────────────────────────────────
@Composable
private fun AdminHubScreen(
    db: AppDatabase,
    onStartRecord: () -> Unit,
    onExit: () -> Unit
) {
    val scope    = rememberCoroutineScope()
    val context  = LocalContext.current

    var nodeCount    by remember { mutableIntStateOf(0) }
    var edgeCount    by remember { mutableIntStateOf(0) }
    var showClearDlg by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }

    // Load stats on entry
    LaunchedEffect(Unit) {
        nodeCount = db.navDao().nodeCount()
        edgeCount = db.navDao().edgeCount()
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
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment   = Alignment.CenterVertically
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
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape  = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Graph Status", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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

            // Export
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
                Text("Export Graph JSON")
            }

            if (exportStatus.isNotEmpty()) {
                Text(exportStatus, color = Color(0xFF64FFDA), fontSize = 12.sp)
            }

            // Clear graph
            OutlinedButton(
                onClick = { showClearDlg = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                border   = androidx.compose.foundation.BorderStroke(1.dp, Color.Red)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear Graph Data")
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
                    InstructionStep("2", "Scan a QR code to calibrate GPS")
                    InstructionStep("3", "Walk corridors at normal speed")
                    InstructionStep("4", "Scan QRs you pass — they anchor the path")
                    InstructionStep("5", "Change floor label when using stairs/elevator")
                    InstructionStep("6", "Tap 'Finish Session' when done")
                    InstructionStep("7", "Export and upload the JSON to the Gist/backend")
                }
            }
        }
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

// ── Recording Screen ──────────────────────────────────────────────────────────
@Composable
private fun AdminRecordingScreen(db: AppDatabase, onFinished: () -> Unit) {
    val scope         = rememberCoroutineScope()
    val context       = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // AR engine
    val engine         = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val modelLoader    = rememberModelLoader(engine)

    // Lifecycle gate (same pattern as ARScreen)
    var canRenderAR          by remember { mutableStateOf(false) }
    var currentLifecycleState by remember { mutableStateOf(Lifecycle.State.INITIALIZED) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            currentLifecycleState = event.targetState
            when (event) {
                Lifecycle.Event.ON_RESUME -> scope.launch {
                    delay(500)
                    if (currentLifecycleState == Lifecycle.State.RESUMED) canRenderAR = true
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP  -> canRenderAR = false
                else                     -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { canRenderAR = false; lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // VPS state
    val earthRef              = remember { mutableStateOf<com.google.ar.core.Earth?>(null) }
    var geospatialPose        by remember { mutableStateOf<GeospatialPose?>(null) }
    var earthTrackingState    by remember { mutableStateOf(TrackingState.STOPPED) }

    // Calibration offset (set by QR scan)
    var latOffset  by remember { mutableStateOf(0.0) }
    var lonOffset  by remember { mutableStateOf(0.0) }
    var altOffset  by remember { mutableStateOf(0.0) }
    var isCalibrated by remember { mutableStateOf(false) }

    // Recording state
    var isRecording          by remember { mutableStateOf(false) }
    var currentFloor         by remember { mutableStateOf("1") }
    var sessionNodes         by remember { mutableIntStateOf(0) }
    var sessionEdges         by remember { mutableIntStateOf(0) }
    var lastRecordedNode     by remember { mutableStateOf<NavNode?>(null) }
    var lastCaptureTime      by remember { mutableLongStateOf(0L) }
    var statusMsg            by remember { mutableStateOf("Scan a QR code to calibrate GPS first") }

    // QR scanning
    var isScanning           by remember { mutableStateOf(false) }
    var lastScanTime         by remember { mutableLongStateOf(0L) }

    val scanner = remember {
        val opts = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        BarcodeScanning.getClient(opts)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── AR background ───────────────────────────────────────────────────
        if (canRenderAR && currentLifecycleState == Lifecycle.State.RESUMED) {
            ARScene(
                modifier = Modifier.fillMaxSize(),
                engine   = engine,
                modelLoader    = modelLoader,
                materialLoader = materialLoader,
                sessionConfiguration = { _, config ->
                    config.geospatialMode = Config.GeospatialMode.ENABLED
                    config.focusMode      = Config.FocusMode.AUTO
                },
                onSessionUpdated = { session, frame ->
                    val earth = session.earth
                    earthRef.value = earth

                    if (earth != null) {
                        earthTrackingState = earth.trackingState
                        if (earthTrackingState == TrackingState.TRACKING) {
                            val pose = earth.cameraGeospatialPose
                            geospatialPose = pose

                            val now = System.currentTimeMillis()

                            // ── Auto-node capture ────────────────────────
                            if (isRecording && isCalibrated && (now - lastCaptureTime > CAPTURE_INTERVAL_MS)) {
                                val corrLat = pose.latitude  - latOffset
                                val corrLon = pose.longitude - lonOffset
                                val corrAlt = pose.altitude  - altOffset

                                val dist = lastRecordedNode?.let {
                                    NavGraphPathfinder.haversine(corrLat, corrLon, it.lat, it.lon)
                                } ?: Double.MAX_VALUE

                                if (dist >= MIN_NODE_DISTANCE_M) {
                                    val newNode = NavNode(
                                        id    = UUID.randomUUID().toString(),
                                        lat   = corrLat,
                                        lon   = corrLon,
                                        alt   = corrAlt,
                                        floor = currentFloor
                                    )
                                    val prevNode = lastRecordedNode
                                    lastRecordedNode = newNode
                                    lastCaptureTime  = now

                                    scope.launch {
                                        db.navDao().insertNode(newNode)
                                        sessionNodes++
                                        if (prevNode != null) {
                                            db.navDao().insertEdge(
                                                NavEdge(
                                                    fromId = prevNode.id,
                                                    toId   = newNode.id,
                                                    weight = NavGraphPathfinder.haversine(
                                                        prevNode.lat, prevNode.lon,
                                                        newNode.lat,  newNode.lon
                                                    )
                                                )
                                            )
                                            sessionEdges++
                                        }
                                    }
                                }
                            }

                            // ── QR scan ──────────────────────────────────
                            if (isScanning && (now - lastScanTime > 1_000L)) {
                                try {
                                    if (currentLifecycleState == Lifecycle.State.RESUMED) {
                                        val image = frame.acquireCameraImage()
                                        lastScanTime = now

                                        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                            context.display?.rotation ?: Surface.ROTATION_0
                                        } else {
                                            @Suppress("DEPRECATION")
                                            (context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager)
                                                .defaultDisplay.rotation
                                        }

                                        val inputImage = InputImage.fromMediaImage(
                                            image,
                                            when (rotation) {
                                                Surface.ROTATION_90  -> 0
                                                Surface.ROTATION_180 -> 270
                                                Surface.ROTATION_270 -> 180
                                                else                 -> 90
                                            }
                                        )

                                        scanner.process(inputImage)
                                            .addOnSuccessListener { barcodes ->
                                                if (barcodes.isNotEmpty()) {
                                                    val qrId = barcodes[0].rawValue ?: return@addOnSuccessListener
                                                    scope.launch {
                                                        val loc = db.qrDao().getById(qrId)
                                                        if (loc != null) {
                                                            // Recalibrate horizontal offset
                                                            latOffset = pose.latitude  - loc.lat
                                                            lonOffset = pose.longitude - loc.lon
                                                            
                                                            // ONLY calibrate altitude if the QR has a valid altitude
                                                            if (loc.alt != 0.0) {
                                                                altOffset = pose.altitude - loc.alt
                                                            }
                                                            
                                                            isCalibrated = true
                                                            isScanning   = false

                                                            // Anchor the most recent node to this QR
                                                            lastRecordedNode?.let { node ->
                                                                val anchored = node.copy(
                                                                    anchorQrId = qrId,
                                                                    label      = loc.name,
                                                                    lat        = loc.lat,
                                                                    lon        = loc.lon,
                                                                    // Only overwrite node altitude if QR has it
                                                                    alt        = if (loc.alt != 0.0) loc.alt else node.alt
                                                                )
                                                                db.navDao().updateNode(anchored)
                                                                lastRecordedNode = anchored
                                                            }
                                                            statusMsg = if (isRecording)
                                                                "Recalibrated at ${loc.name} — keep walking"
                                                            else
                                                                "Calibrated at ${loc.name} — ready to record"
                                                        } else {
                                                            statusMsg = "QR not in database: $qrId"
                                                        }
                                                    }
                                                }
                                            }
                                            .addOnCompleteListener { image.close() }
                                    }
                                } catch (_: Exception) { /* Native buffer safety */ }
                            }
                        }
                    }
                }
            ) { /* No AR nodes rendered in admin mode */ }
        } else {
            Box(
                modifier           = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
                contentAlignment   = Alignment.Center
            ) { CircularProgressIndicator(color = Color(0xFF64FFDA)) }
        }

        // ── HUD overlay ─────────────────────────────────────────────────────
        Column(
            modifier            = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: status + VPS info
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Status bar
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.75f)
                    ),
                    shape    = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier            = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment   = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Pulsing red dot when recording
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

                // Status message
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                    shape  = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        statusMsg,
                        modifier = Modifier.padding(10.dp),
                        color    = Color.White,
                        fontSize = 13.sp
                    )
                }

                // GPS readout
                geospatialPose?.let { pose ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                        shape  = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val trackColor = if (earthTrackingState == TrackingState.TRACKING)
                            Color(0xFF64FFDA) else Color.Red
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "VPS: ${earthTrackingState.name}",
                                color    = trackColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (isCalibrated) {
                                Text(
                                    String.format(
                                        Locale.US,
                                        "%.6f, %.6f  alt %.1fm",
                                        pose.latitude - latOffset,
                                        pose.longitude - lonOffset,
                                        pose.altitude  - altOffset
                                    ),
                                    color    = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // Bottom: controls
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                // Floor selector
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
                    shape  = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Floor", color = Color.Gray, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FLOOR_OPTIONS.forEach { fl ->
                                FilterChip(
                                    selected = currentFloor == fl,
                                    onClick  = { currentFloor = fl },
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

                // Scan QR button
                Button(
                    onClick = {
                        isScanning = !isScanning
                        if (isScanning) statusMsg = "Point camera at a QR code..."
                    },
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

                // Start / Stop recording
                Button(
                    onClick = {
                        if (!isCalibrated) {
                            statusMsg = "Scan a QR code first to calibrate!"
                            return@Button
                        }
                        isRecording = !isRecording
                        if (isRecording) {
                            lastRecordedNode = null   // start fresh segment
                            statusMsg = "Recording — walk the corridor"
                        } else {
                            statusMsg = "Paused — scan QR or start new segment"
                        }
                    },
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

                // Finish session
                OutlinedButton(
                    onClick  = {
                        isRecording = false
                        onFinished()
                    },
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
        }
    }
}

// ── Small helper composables ───────────────────────────────────────────────────
@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64FFDA))
        Text(label, fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
private fun InstructionStep(num: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier          = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            "$num.",
            color    = Color(0xFF64FFDA),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp),
            fontSize = 13.sp
        )
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}
