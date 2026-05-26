package com.example.metaversearapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.Serializable

/** Semantic type of a nav node, used for routing hints and AR visuals. */
@Serializable
enum class NodeType {
    WAYPOINT,      // ordinary path node
    DOOR,          // doorway / entrance
    STAIR_TOP,     // top landing of a staircase
    STAIR_MIDDLE,  // intermediate node along a staircase (improves arrow tracking up stairs)
    STAIR_BOTTOM   // bottom landing of a staircase
}

/** Room TypeConverter so NodeType is stored as its name string. */
class NodeTypeConverter {
    @TypeConverter
    fun fromNodeType(type: NodeType): String = type.name

    @TypeConverter
    fun toNodeType(value: String): NodeType =
        NodeType.entries.firstOrNull { it.name == value } ?: NodeType.WAYPOINT
}

/**
 * A waypoint node in the navigation graph.
 * Nodes are captured by an admin walking the building.
 * When a QR code is scanned during recording, [anchorQrId] and [label] are populated,
 * locking this node to a known geospatial position.
 */
@Serializable
@Entity(tableName = "nav_nodes")
data class NavNode(
    @PrimaryKey val id: String,
    val lat: Double,
    val lon: Double,
    val alt: Double = 0.0,
    val floor: String = "1",
    val building: String = "",
    val anchorQrId: String? = null,
    val label: String = "",
    val type: NodeType = NodeType.WAYPOINT,
    /** ARCore Cloud Anchor ID hosted at this node's physical position.
     *  Non-null only for nodes that an admin has explicitly hosted.
     *  Used by the user-side to resolve the anchor and get a precise
     *  calibration offset, bypassing GPS inaccuracy. */
    val cloudAnchorId: String? = null,

    /** Compass heading (degrees, 0 = north, clockwise) of the device at the
     *  moment the cloud anchor was hosted.  Stored so the viewer can draw a
     *  direction indicator and so the app knows which way the anchor "faces"
     *  for alignment purposes. Null for nodes without a hosted anchor. */
    val cloudAnchorHeading: Double? = null
)
