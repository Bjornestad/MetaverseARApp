@file:Suppress("DEPRECATION")
package com.example.metaversearapp.ui.components

import android.graphics.Paint
import android.preference.PreferenceManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.metaversearapp.data.NavEdge
import com.example.metaversearapp.data.NavNode
import com.example.metaversearapp.data.NodeType
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// Tile source: CartoDB DarkMatter — same provider used by navgraph-viewer.html
// ─────────────────────────────────────────────────────────────────────────────
private val CARTO_DARK = XYTileSource(
    "CartoDB-DarkMatter",
    /* minZoom */ 0,
    /* maxZoom */ 20,
    /* tileSizePx */ 256,
    /* fileExtension */ ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/",
        "https://d.basemaps.cartocdn.com/dark_all/"
    )
)

// ─────────────────────────────────────────────────────────────────────────────
// Custom overlay — draws nav graph, path, and user position on top of the tiles
// ─────────────────────────────────────────────────────────────────────────────
private class NavGraphOverlay : Overlay() {

    var userLat  : Double          = 0.0
    var userLon  : Double          = 0.0
    var heading  : Double          = 0.0   // corrected compass, 0 = north
    var nodes    : List<NavNode>   = emptyList()
    var edges    : List<NavEdge>   = emptyList()
    var pathNodes: List<NavNode>   = emptyList()

    // Paints — created once to avoid per-frame allocations
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = android.graphics.Color.argb(46, 0x38, 0xBD, 0xF8)  // 0.18 alpha sky-blue
        strokeWidth = 2f
        style       = Paint.Style.STROKE
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(115, 0x38, 0xBD, 0xF8)  // 0.45 alpha sky-blue
        style = Paint.Style.FILL
    }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = android.graphics.Color.argb(217, 0xFF, 0xB3, 0x00)  // 0.85 alpha amber
        strokeWidth = 4f
        style       = Paint.Style.STROKE
        strokeCap   = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
    }
    private val destPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(0xFF, 0xFF, 0xB3, 0x00)
        style = Paint.Style.FILL
    }
    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(230, 0x64, 0xFF, 0xDA)  // ~0.9 alpha teal
        style = Paint.Style.FILL
    }
    private val userRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    private val userHolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(0xFF, 0x0D, 0x11, 0x17)
        style = Paint.Style.FILL
    }
    private val doorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(200, 0xFF, 0xA7, 0x26)  // orange
        style = Paint.Style.FILL
    }
    private val stairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(200, 0x66, 0xBB, 0x6A)  // green
        style = Paint.Style.FILL
    }

    override fun draw(
        canvas  : android.graphics.Canvas,
        mapView : MapView,
        shadow  : Boolean
    ) {
        if (shadow) return
        val proj    = mapView.projection
        val nodeMap = nodes.associateBy { it.id }

        // ── Nav edges ────────────────────────────────────────────────────────
        val ptBuf = android.graphics.Point()
        edges.forEach { edge ->
            val a = nodeMap[edge.fromId] ?: return@forEach
            val b = nodeMap[edge.toId]   ?: return@forEach
            val pa = proj.toPixels(GeoPoint(a.lat, a.lon), null)
            val pb = proj.toPixels(GeoPoint(b.lat, b.lon), null)
            canvas.drawLine(pa.x.toFloat(), pa.y.toFloat(),
                            pb.x.toFloat(), pb.y.toFloat(), edgePaint)
        }

        // ── Nav nodes (type-differentiated) ──────────────────────────────────
        nodes.forEach { node ->
            val p = proj.toPixels(GeoPoint(node.lat, node.lon), ptBuf)
            val px = p.x.toFloat(); val py = p.y.toFloat()
            when (node.type) {
                NodeType.DOOR -> canvas.drawRect(px - 4f, py - 4f, px + 4f, py + 4f, doorPaint)
                NodeType.STAIR_TOP, NodeType.STAIR_MIDDLE, NodeType.STAIR_BOTTOM ->
                    canvas.drawCircle(px, py, 5f, stairPaint)
                else -> canvas.drawCircle(px, py, 3f, nodePaint)
            }
        }

        // ── Active route ─────────────────────────────────────────────────────
        if (pathNodes.size >= 2) {
            for (i in 0 until pathNodes.size - 1) {
                val a  = pathNodes[i]
                val b  = pathNodes[i + 1]
                val pa = proj.toPixels(GeoPoint(a.lat, a.lon), null)
                val pb = proj.toPixels(GeoPoint(b.lat, b.lon), null)
                canvas.drawLine(pa.x.toFloat(), pa.y.toFloat(),
                                pb.x.toFloat(), pb.y.toFloat(), pathPaint)
            }
            val dest = proj.toPixels(GeoPoint(pathNodes.last().lat, pathNodes.last().lon), null)
            canvas.drawCircle(dest.x.toFloat(), dest.y.toFloat(), 7f, destPaint)
        }

        // ── User heading cone ─────────────────────────────────────────────────
        // OsmDroid map is always north-up, so heading 0 = up (+y is south on screen).
        val up  = proj.toPixels(GeoPoint(userLat, userLon), null)
        val ux  = up.x.toFloat()
        val uy  = up.y.toFloat()
        val rad = Math.toRadians(heading).toFloat()
        val fwdX = sin(rad)
        val fwdY = -cos(rad)   // screen y increases downward; north = screen-up
        val rgtX = cos(rad)
        val rgtY = sin(rad)

        val coneH  = 20f
        val coneHW = 10f
        val conePath = android.graphics.Path().apply {
            moveTo(ux + fwdX * coneH,            uy + fwdY * coneH)
            lineTo(ux - rgtX * coneHW / 2f,      uy - rgtY * coneHW / 2f)
            lineTo(ux + rgtX * coneHW / 2f,      uy + rgtY * coneHW / 2f)
            close()
        }
        canvas.drawPath(conePath, conePaint)

        // ── User dot (white ring + dark centre) ──────────────────────────────
        canvas.drawCircle(ux, uy, 6f, userRingPaint)
        canvas.drawCircle(ux, uy, 3f, userHolePaint)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Public composable — drop-in replacement for the Canvas MiniMap
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A tiled map minimap backed by OsmDroid + CartoDB DarkMatter tiles.
 *
 * The map is always north-up and locked (no touch panning/zooming) so it
 * doesn't interfere with AR touch events.  It automatically re-centres on
 * [lat]/[lon] and redraws the nav-graph overlay whenever its inputs change.
 *
 * Returns early (renders nothing) when [lat] or [lon] is null.
 */
@Composable
fun TiledMiniMap(
    lat      : Double?,
    lon      : Double?,
    heading  : Double        = 0.0,
    nodes    : List<NavNode> = emptyList(),
    edges    : List<NavEdge> = emptyList(),
    pathNodes: List<NavNode> = emptyList(),
    modifier : Modifier      = Modifier,
    sizeDp   : Dp            = 130.dp,
    zoomLevel: Double        = 19.5
) {
    if (lat == null || lon == null) return

    val context   = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // One-time OsmDroid init — idempotent, safe to call multiple times
    remember(context) {
        Configuration.getInstance().apply {
            load(context, PreferenceManager.getDefaultSharedPreferences(context))
            userAgentValue  = context.packageName
            osmdroidTileCache = File(context.cacheDir, "osmdroid")
        }
    }

    // The overlay is a stable object — we mutate its fields in the AndroidView update lambda
    val overlay = remember { NavGraphOverlay() }

    // Pause / resume tile loading with the Activity lifecycle
    var mapViewHolder by remember { mutableStateOf<MapView?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewHolder?.onResume()
                Lifecycle.Event.ON_PAUSE  -> mapViewHolder?.onPause()
                else                      -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = modifier.size(sizeDp),
        shape    = RoundedCornerShape(10.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF0D1117).copy(alpha = 0.88f)),
        border   = BorderStroke(1.dp, Color(0xFF2A2A3E))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(CARTO_DARK)
                        // Lock the map — all touch goes to AR beneath it
                        setMultiTouchControls(false)
                        isClickable         = false
                        setOnTouchListener { _, _ -> true }
                        zoomController.setVisibility(
                            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
                        )
                        controller.setZoom(zoomLevel)
                        controller.setCenter(GeoPoint(lat, lon))
                        overlays.add(overlay)
                        mapViewHolder = this
                    }
                },
                update = { mv ->
                    // Update overlay data and re-centre on every recomposition
                    // (triggered whenever lat/lon/heading/nodes/edges/pathNodes change)
                    overlay.userLat   = lat
                    overlay.userLon   = lon
                    overlay.heading   = heading
                    overlay.nodes     = nodes
                    overlay.edges     = edges
                    overlay.pathNodes = pathNodes
                    mv.controller.setCenter(GeoPoint(lat, lon))
                    mv.invalidate()
                }
            )

            // Zoom hint — bottom-right
            Text(
                text     = "z${zoomLevel.toInt()}",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 2.dp),
                color      = Color.White.copy(alpha = 0.3f),
                fontSize   = 8.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}
