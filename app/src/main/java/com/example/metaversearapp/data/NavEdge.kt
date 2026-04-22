package com.example.metaversearapp.data

import androidx.room.Entity
import kotlinx.serialization.Serializable

/**
 * A directed edge in the navigation graph. Stored as undirected by the pathfinder
 * (it adds both directions at query time).
 *
 * Composite primary key: only one edge per (fromId, toId) pair.
 */
@Serializable
@Entity(tableName = "nav_edges", primaryKeys = ["fromId", "toId"])
data class NavEdge(
    val fromId: String,
    val toId: String,
    /** Pre-computed Haversine distance in metres between the two nodes. */
    val weight: Double
)
