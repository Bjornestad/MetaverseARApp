package com.example.metaversearapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.metaversearapp.data.AppDatabase
import com.example.metaversearapp.ui.components.ARUiOverlay
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.TrackingState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Color as SceneViewColor
import io.github.sceneview.node.CubeNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import com.example.metaversearapp.data.NavGraphPathfinder
import com.example.metaversearapp.data.NavNode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ARScreen(
    db: AppDatabase, 
    viewModel: ARViewModel = viewModel(factory = ARViewModel.Factory(db)), 
    onAdminRequest: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val modelLoader = rememberModelLoader(engine)

    // --- PERMISSIONS ---
    val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    var permissionsGranted by remember {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            launcher.launch(permissions)
        }
    }

    // --- SAFETY & LIFECYCLE STATE ---
    var canRenderAR by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    delay(500) // Hardware warmup delay
                    if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED) {
                        canRenderAR = true
                    }
                }
            } else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                canRenderAR = false
                viewModel.isScanning = false // Prevent background processing
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            canRenderAR = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var roomAnchor by remember { mutableStateOf<Anchor?>(null) }
    var testAnchors by remember { mutableStateOf<List<Anchor>>(emptyList()) }
    var lastProcessingTime by remember { mutableLongStateOf(0L) }
    val earthRef = remember { mutableStateOf<Earth?>(null) }

    // --- PATH / BREADCRUMBS ---
    var allNavNodes by remember { mutableStateOf<List<NavNode>>(emptyList()) }
    var allNavEdges by remember { mutableStateOf<List<com.example.metaversearapp.data.NavEdge>>(emptyList()) }
    var pathNodes   by remember { mutableStateOf<List<NavNode>>(emptyList()) }
    var crumbAnchors by remember { mutableStateOf<List<Anchor>>(emptyList()) }

    // Load nav graph from DB once
    LaunchedEffect(Unit) {
        allNavNodes = db.navDao().getAllNodes()
        allNavEdges = db.navDao().getAllEdges()
    }

    val scanner = remember {
        val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        BarcodeScanning.getClient(options)
    }
    
    DisposableEffect(scanner) {
        onDispose { scanner.close() }
    }

    // --- TEST ANCHOR LOGIC ---
    LaunchedEffect(viewModel.placedAnchors.size) {
        if (viewModel.placedAnchors.isEmpty()) return@LaunchedEffect
        val currentEarth = earthRef.value ?: return@LaunchedEffect
        val latest = viewModel.placedAnchors.last()
        val newAnchor = currentEarth.createAnchor(
            latest.lat, latest.lon, latest.alt,
            0f, 0f, 0f, 1f
        )
        testAnchors = testAnchors + newAnchor
    }

    // --- ANCHOR LOGIC ---
    LaunchedEffect(
        viewModel.selectedDestination,
        viewModel.isCalibrated,
        viewModel.latOffset,
        viewModel.lonOffset,
        viewModel.altOffset,
        viewModel.earthTrackingState,
        earthRef.value
    ) {
        val currentEarth = earthRef.value
        val dest = viewModel.selectedDestination
        if (currentEarth != null && viewModel.earthTrackingState == TrackingState.TRACKING && dest != null) {
            val floorAlt = (viewModel.geospatialPose?.altitude ?: (dest.alt + viewModel.altOffset)) - 1.7
            roomAnchor?.detach()
            roomAnchor = currentEarth.createAnchor(
                dest.lat + viewModel.latOffset,
                dest.lon + viewModel.lonOffset,
                floorAlt,
                0f, 0f, 0f, 1f
            )
        }
    }

    if (permissionsGranted) {
        Box(modifier = Modifier.fillMaxSize()) {

            // --- LAYER 1: AR SCENE ---
            if (canRenderAR) {
                ARScene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    sessionConfiguration = { session, config ->
                        config.geospatialMode = Config.GeospatialMode.ENABLED
                        config.focusMode = Config.FocusMode.AUTO
                        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    },
                    onSessionUpdated = { session, frame ->
                        val earth = session.earth
                        earthRef.value = earth
                        viewModel.updateGeospatialState(earth)

                        if (earth != null && earth.trackingState == TrackingState.TRACKING) {
                            val pose = earth.cameraGeospatialPose

                            // Tick off passed breadcrumbs
                            if (crumbAnchors.isNotEmpty() && pathNodes.size > 1) {
                                val nextNode = pathNodes[1]
                                val dist = NavGraphPathfinder.haversine(
                                    pose.latitude  - viewModel.latOffset,
                                    pose.longitude - viewModel.lonOffset,
                                    nextNode.lat, nextNode.lon
                                )
                                if (dist < 3.0) { // within 3 metres → passed it
                                    crumbAnchors.firstOrNull()?.detach()
                                    crumbAnchors = crumbAnchors.drop(1)
                                    pathNodes    = pathNodes.drop(1)
                                }
                            }

                            val currentTime = System.currentTimeMillis()
                            if (viewModel.isScanning && (currentTime - lastProcessingTime > 1000)) {
                                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    try {
                                        val image = frame.acquireCameraImage()
                                        lastProcessingTime = currentTime

                                        try {
                                            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                context.display?.rotation ?: Surface.ROTATION_0
                                            } else {
                                                @Suppress("DEPRECATION")
                                                (context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                                            }

                                            val inputImage = InputImage.fromMediaImage(image, when(rotation) {
                                                Surface.ROTATION_90 -> 0; Surface.ROTATION_180 -> 270; Surface.ROTATION_270 -> 180; else -> 90
                                            })

                                            scanner.process(inputImage)
                                                .addOnSuccessListener { barcodes ->
                                                    if (barcodes.isNotEmpty()) {
                                                        val id = barcodes[0].rawValue ?: return@addOnSuccessListener
                                                        viewModel.onQrScanned(id, pose)
                                                    }
                                                }
                                                .addOnCompleteListener { 
                                                    try { image.close() } catch (_: Exception) {}
                                                }
                                        } catch (e: Exception) {
                                            image.close()
                                            throw e
                                        }
                                    } catch (e: Exception) {
                                        // Silent catch for ARCore buffer/session exceptions
                                    }
                                }
                            }
                        }
                    }
                ) {
                    val yellowMaterial = remember(materialLoader) {
                        materialLoader.createColorInstance(
                            color = SceneViewColor(1.0f, 0.9f, 0.0f, 1.0f),
                            metallic = 0.0f,
                            roughness = 1.0f,
                            reflectance = 0.5f
                        )
                    }

                    roomAnchor?.let { anchor ->
                        AnchorNode(anchor = anchor) {
                            CubeNode(
                                size = Float3(0.5f, 3.5f, 0.5f),
                                center = Position(0f, 2.75f, 0f),
                                materialInstance = yellowMaterial
                            )
                            CubeNode(
                                size = Float3(0.5f, 0.5f, 0.5f),
                                center = Position(0f, 0.25f, 0f),
                                materialInstance = yellowMaterial
                            )
                        }
                    }

                    testAnchors.forEach { anchor ->
                        AnchorNode(anchor = anchor) {
                            CubeNode(
                                size = Float3(0.3f, 1.2f, 0.3f),
                                center = Position(0f, 1.15f, 0f),
                                materialInstance = yellowMaterial
                            )
                            CubeNode(
                                size = Float3(0.3f, 0.3f, 0.3f),
                                center = Position(0f, 0.15f, 0f),
                                materialInstance = yellowMaterial
                            )
                        }
                    }
                    crumbAnchors.forEachIndexed { index, anchor ->
                        AnchorNode(anchor = anchor) {
                            CubeNode(
                                size   = Float3(0.12f, 0.12f, 0.12f),
                                center = Position(0f, 1.2f, 0f),
                                materialInstance = materialLoader.createColorInstance(
                                    color = if (index == 0)
                                        SceneViewColor(0.0f, 1.0f, 0.4f, 1.0f)
                                    else
                                        SceneViewColor(0.0f, 0.7f, 0.3f, 0.8f)
                                )
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(ComposeColor.Black), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ComposeColor.Cyan)
                }
            }

            // --- LAYER 2: UI OVERLAYS ---
            ARUiOverlay(viewModel)

            // Admin button overlay
            IconButton(
                onClick  = onAdminRequest,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 8.dp)
            ) {
                Icon(
                    Icons.Default.AdminPanelSettings,
                    contentDescription = "Admin",
                    tint = ComposeColor.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(28.dp)
                )
            }

            // Path info overlay
            if (pathNodes.isNotEmpty() && viewModel.isCalibrated) {
                val remaining = pathNodes.size - 1
                Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)) {
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        colors   = CardDefaults.cardColors(
                            containerColor = ComposeColor(0xFF1A3A1A).copy(alpha = 0.9f)
                        )
                    ) {
                        Text(
                            "🟢 Path: $remaining waypoints remaining",
                            modifier = Modifier.padding(10.dp),
                            color    = ComposeColor(0xFF64FFDA),
                            style    = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera and Location Permissions Required")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { launcher.launch(permissions) }) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}
