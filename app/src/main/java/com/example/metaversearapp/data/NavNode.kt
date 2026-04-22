package com.example.metaversearapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

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
    val anchorQrId: String? = null,
    val label: String = ""
)
