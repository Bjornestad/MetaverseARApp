package com.example.metaversearapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metaversearapp.data.QrLocation
import com.example.metaversearapp.ui.ARViewModel
import com.google.ar.core.GeospatialPose
import com.google.ar.core.TrackingState
import java.util.Locale
import kotlin.math.*

/** Shared dark-translucent surface colour used across all AR overlay cards. */
val OverlayBackground = Color(0xFF0F1923).copy(alpha = 0.88f)

/**
 * A small persistent chip that surfaces cloud-anchor activity outside debug mode.
 *
 * Shows:
 *  • amber pulse text while a resolve is in flight ([isResolving] == true)
 *  • gold anchor-info string once resolved ([info] != null)
 *  • nothing when neither condition is met (returns early)
 *
 * The chip sits in the top status column so users always know whether the system
 * has calibrated via cloud anchors — previously this was buried in the debug overlay.
 */
@Composable
fun CloudAnchorChip(
    isResolving : Boolean,
    info        : String?,
    modifier    : Modifier = Modifier
) {
    val (text, color) = when {
        isResolving  -> "⚓  Locating via cloud anchor…" to Color(0xFFFFCC80)
        info != null -> info                              to Color(0xFFFFD700)
        else         -> return
    }
    Card(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = OverlayBackground.copy(alpha = 0.75f)
        )
    ) {
        Text(
            text     = text,
            style    = MaterialTheme.typography.labelSmall,
            color    = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun StatusOverlay(
    status               : String,
    trackingState        : TrackingState = TrackingState.STOPPED,
    isCloudAnchorActive  : Boolean       = false,
    cloudAnchorResolved  : Boolean       = false
) {
    val (vpsIcon, vpsColor) = when (trackingState) {
        TrackingState.TRACKING -> Icons.Default.GpsFixed    to Color(0xFF64FFDA)
        TrackingState.PAUSED   -> Icons.Default.GpsNotFixed to Color(0xFFFFCC80)
        else                   -> Icons.Default.GpsOff      to Color(0xFFFF5252)
    }
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OverlayBackground)
    ) {
        Column {
            Row(
                modifier          = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    status,
                    modifier = Modifier.weight(1f),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = Color.White.copy(alpha = 0.9f)
                )
                Icon(
                    imageVector        = vpsIcon,
                    contentDescription = "VPS status",
                    tint               = vpsColor,
                    modifier           = Modifier.size(16.dp)
                )
            }

            // ── Cloud anchor indicator ─────────────────────────────────────────
            // Shown inside the card whenever an anchor is being resolved or has
            // been resolved, so the user knows the system is using high-accuracy
            // anchor tracking rather than GPS alone.
            if (isCloudAnchorActive) {
                HorizontalDivider(
                    modifier  = Modifier.padding(horizontal = 14.dp),
                    thickness = 1.dp,
                    color     = Color.White.copy(alpha = 0.08f)
                )
                Row(
                    modifier             = Modifier
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .fillMaxWidth(),
                    verticalAlignment    = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val anchorColor = if (cloudAnchorResolved) Color(0xFFFFD700)
                                      else Color(0xFFFFCC80)
                    Text("⚓", fontSize = 11.sp, color = anchorColor)
                    Text(
                        text     = if (cloudAnchorResolved) "Anchor tracking active"
                                   else "Locating anchor…",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = anchorColor,
                        modifier = Modifier.weight(1f)
                    )
                    // Filled dot: gold when resolved, hollow-ish amber when still resolving
                    Surface(
                        shape    = CircleShape,
                        color    = anchorColor,
                        modifier = Modifier.size(6.dp)
                    ) {}
                }
            }
        }
    }
}

@Composable
fun NavigationArrow(currentPose: GeospatialPose, destination: QrLocation, latOffset: Double, lonOffset: Double) {
    val targetLat = destination.lat + latOffset
    val targetLon = destination.lon + lonOffset
    val lat1 = Math.toRadians(currentPose.latitude)
    val lon1 = Math.toRadians(currentPose.longitude)
    val lat2 = Math.toRadians(targetLat)
    val lon2 = Math.toRadians(targetLon)
    val dLon = lon2 - lon1
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val bearing = Math.toDegrees(atan2(y, x))
    val relativeAngle = (bearing - currentPose.heading).toFloat()
    val compassRotation = (-currentPose.heading).toFloat()

    Card(
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = OverlayBackground),
        modifier = Modifier.size(72.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            // Compass cardinal directions
            Box(modifier = Modifier.fillMaxSize().padding(8.dp).rotate(compassRotation)) {
                Text("N", modifier = Modifier.align(Alignment.TopCenter).rotate(-compassRotation),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFF5252))
                Text("S", modifier = Modifier.align(Alignment.BottomCenter).rotate(-compassRotation),
                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                Text("E", modifier = Modifier.align(Alignment.CenterEnd).rotate(-compassRotation),
                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                Text("W", modifier = Modifier.align(Alignment.CenterStart).rotate(-compassRotation),
                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
            }
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = null,
                modifier = Modifier.size(40.dp).rotate(relativeAngle),
                tint = Color(0xFF64FFDA)
            )
        }
    }
}

@Composable
fun GeospatialBottomOverlay(viewModel: ARViewModel, modifier: Modifier = Modifier) {
    val pose = viewModel.geospatialPose
    val trackingState = viewModel.earthTrackingState
    val isCalibrated = viewModel.isCalibrated

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = OverlayBackground)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // VPS status as a single coloured dot:
            //   green  = tracking + calibrated
            //   amber  = tracking, not yet calibrated
            //   red    = not tracking
            val vpsColor = when {
                trackingState == TrackingState.TRACKING && isCalibrated -> Color(0xFF4CAF50)
                trackingState == TrackingState.TRACKING                  -> Color(0xFFFFCC80)
                else                                                     -> Color(0xFFFF5252)
            }
            Row(
                modifier             = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment    = Alignment.CenterVertically
            ) {
                Text(
                    "VPS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Surface(shape = CircleShape, color = vpsColor, modifier = Modifier.size(8.dp)) {}
            }

            if (pose != null && trackingState == TrackingState.TRACKING) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = Color.White.copy(alpha = 0.1f)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "H.Acc:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        "%.2fm".format(viewModel.horizontalAccuracy),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (viewModel.horizontalAccuracy < 1.0) Color(0xFF4CAF50)
                                else Color(0xFFFFCC80)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "V.Acc:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        "%.2fm".format(viewModel.verticalAccuracy),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Hdg.Acc:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        "%.1f°".format(viewModel.headingAccuracy),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            viewModel.headingAccuracy < 10.0 -> Color(0xFF4CAF50)
                            viewModel.headingAccuracy < 20.0 -> Color(0xFFFFCC80)
                            else                             -> Color(0xFFFF5252)
                        }
                    )
                }
                Text(
                    String.format(Locale.US, "Lat: %.6f", pose.latitude),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64FFDA).copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    String.format(Locale.US, "Lon: %.6f", pose.longitude),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64FFDA).copy(alpha = 0.8f)
                )
            }

            // Cloud anchor resolution info — shown once an anchor has been resolved
            val anchorInfo = viewModel.lastCloudAnchorInfo
            if (anchorInfo != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color    = Color.White.copy(alpha = 0.1f)
                )
                Text(
                    anchorInfo,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = Color(0xFFFFD700).copy(alpha = 0.9f)
                )
            } else if (viewModel.isResolvingCloudAnchor) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color    = Color.White.copy(alpha = 0.1f)
                )
                Text(
                    "⚓ Resolving cloud anchor…",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFCC80).copy(alpha = 0.9f)
                )
            }

            // Floor indicator — shown whenever nav nodes are loaded
            val floor = viewModel.currentFloor
            if (floor != null || viewModel.navNodes.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color    = Color.White.copy(alpha = 0.1f)
                )
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        "Floor:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    if (floor != null) {
                        // ~F2 = estimated (uncalibrated VPS), F2 = confirmed (calibrated)
                        val label    = if (viewModel.isCalibrated) "F$floor" else "~F$floor"
                        val color    = if (viewModel.isCalibrated) Color(0xFF64FFDA)
                                       else Color(0xFF64FFDA).copy(alpha = 0.55f)
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = color
                        )
                    } else {
                        Text(
                            "—",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Battlefield-style HUD compass strip.
 *
 * A full-width horizontal bar showing ±60° of the compass around the current
 * [heading].  Ticks and cardinal/intercardinal labels scroll with the heading.
 * A fixed teal ▼ notch at the top-centre marks where the device is pointing.
 *
 * If [destinationBearing] is supplied:
 *  • When the destination is within the visible span, an amber ▼ appears at
 *    its bearing position on the bar.
 *  • When it is off-screen left/right, a small amber ◄/► arrow appears at
 *    the corresponding edge so the user knows which way to turn.
 */
@Composable
fun HUDCompass(
    heading: Double,
    destinationBearing: Double?,
    nextWaypointBearing: Double? = null,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val spanDeg  = 120f
    val teal     = Color(0xFF64FFDA)
    val amber    = Color(0xFFFFCC02)
    val halfSpan = spanDeg / 2f

    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = OverlayBackground),
        shape    = MaterialTheme.shapes.small
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp)
        ) {
            val w  = size.width
            val h  = size.height
            val cx = w / 2f

            // ── Tick marks + cardinal labels ─────────────────────────────────
            val startDeg = (heading - halfSpan).toInt() - 1
            val endDeg   = (heading + halfSpan).toInt() + 1

            for (rawDeg in startDeg..endDeg) {
                if (rawDeg % 5 != 0) continue

                val diff = compassRelDeg(rawDeg.toFloat() - heading.toFloat())
                if (abs(diff) > halfSpan + 1f) continue
                val x = cx + (diff / halfSpan) * cx

                val normDeg         = ((rawDeg % 360) + 360) % 360
                val isCardinal      = normDeg % 90 == 0
                val isIntercardinal = normDeg % 45 == 0
                val isMajor10       = normDeg % 10 == 0

                val tickTop = h * 0.28f
                val tickLen = when {
                    isIntercardinal -> h * 0.36f
                    isMajor10       -> h * 0.22f
                    else            -> h * 0.13f
                }
                drawLine(
                    color       = Color.White.copy(alpha = when {
                        isIntercardinal -> 0.90f
                        isMajor10       -> 0.55f
                        else            -> 0.28f
                    }),
                    start       = Offset(x, tickTop),
                    end         = Offset(x, tickTop + tickLen),
                    strokeWidth = when {
                        isCardinal      -> 2.5f
                        isIntercardinal -> 1.8f
                        else            -> 1.0f
                    }
                )

                val label = when (normDeg) {
                    0   -> "N";  45  -> "NE"; 90  -> "E";  135 -> "SE"
                    180 -> "S";  225 -> "SW"; 270 -> "W";  315 -> "NW"
                    else -> null
                }
                if (label != null) {
                    val tm = textMeasurer.measure(
                        label,
                        TextStyle(
                            fontSize   = if (isCardinal) 11.sp else 9.sp,
                            color      = if (isCardinal) Color.White
                                         else Color.White.copy(alpha = 0.7f),
                            fontWeight = if (isCardinal) FontWeight.Bold
                                         else FontWeight.Normal
                        )
                    )
                    drawText(tm, topLeft = Offset(x - tm.size.width / 2f, tickTop + tickLen + 2f))
                }
            }

            // ── Destination bearing marker ────────────────────────────────────
            if (destinationBearing != null) {
                val diff    = compassRelDeg(destinationBearing.toFloat() - heading.toFloat())
                val absDiff = abs(diff)
                val ts      = 7.dp.toPx()

                when {
                    absDiff <= halfSpan -> {
                        // On-screen: amber ▼ at destination x
                        val x  = cx + (diff / halfSpan) * cx
                        val ty = 1.dp.toPx()
                        drawPath(Path().apply {
                            moveTo(x,             ty + ts)
                            lineTo(x - ts * 0.55f, ty)
                            lineTo(x + ts * 0.55f, ty)
                            close()
                        }, amber)
                    }
                    diff < 0f -> {
                        // Off left: amber ◄ at left edge
                        val ex = 6.dp.toPx(); val ey = h * 0.15f
                        drawPath(Path().apply {
                            moveTo(ex,              ey)
                            lineTo(ex + ts * 0.9f, ey - ts * 0.6f)
                            lineTo(ex + ts * 0.9f, ey + ts * 0.6f)
                            close()
                        }, amber.copy(alpha = 0.75f))
                    }
                    else -> {
                        // Off right: amber ► at right edge
                        val ex = w - 6.dp.toPx(); val ey = h * 0.15f
                        drawPath(Path().apply {
                            moveTo(ex,              ey)
                            lineTo(ex - ts * 0.9f, ey - ts * 0.6f)
                            lineTo(ex - ts * 0.9f, ey + ts * 0.6f)
                            close()
                        }, amber.copy(alpha = 0.75f))
                    }
                }
            }

            // ── Next waypoint marker (teal dot) ──────────────────────────────
            // Sits at the same y-row as the destination marker but uses a filled
            // circle so the two are visually distinct even when close together.
            if (nextWaypointBearing != null) {
                val diff    = compassRelDeg(nextWaypointBearing.toFloat() - heading.toFloat())
                val absDiff = abs(diff)
                val dotR    = 4.5.dp.toPx()
                val dotY    = 13.dp.toPx()   // below the amber destination row

                when {
                    absDiff <= halfSpan -> {
                        val x = cx + (diff / halfSpan) * cx
                        drawCircle(color = teal, radius = dotR, center = Offset(x, dotY))
                        // Thin vertical stem connecting dot to tick area
                        drawLine(
                            color       = teal.copy(alpha = 0.5f),
                            start       = Offset(x, dotY + dotR),
                            end         = Offset(x, h * 0.28f),
                            strokeWidth = 1.2f
                        )
                    }
                    diff < 0f -> {
                        // Off left: small teal ◄
                        val ex = 6.dp.toPx()
                        drawCircle(color = teal.copy(alpha = 0.65f), radius = dotR * 0.8f,
                            center = Offset(ex + dotR, dotY + 6.dp.toPx()))
                    }
                    else -> {
                        // Off right: small teal ►
                        val ex = w - 6.dp.toPx()
                        drawCircle(color = teal.copy(alpha = 0.65f), radius = dotR * 0.8f,
                            center = Offset(ex - dotR, dotY + 6.dp.toPx()))
                    }
                }
            }

            // ── Fixed centre notch (teal ▼, always at top-centre) ────────────
            val ns = 8.dp.toPx()
            drawPath(Path().apply {
                moveTo(cx,              ns)
                lineTo(cx - ns * 0.55f, 0f)
                lineTo(cx + ns * 0.55f, 0f)
                close()
            }, teal)
        }
    }
}

/** Normalises a degree difference into the range −180..180. */
private fun compassRelDeg(diff: Float): Float {
    var d = diff % 360f
    if (d >  180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}
