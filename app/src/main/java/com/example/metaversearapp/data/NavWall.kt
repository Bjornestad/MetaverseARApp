package com.example.metaversearapp.data

import androidx.room.*
import kotlinx.serialization.Serializable

/**
 * A wall segment that prohibits pathfinding through it.
 *
 * Walls are line segments placed by the admin in the navgraph-viewer tool.
 * During A* pathfinding, any edge whose straight-line geometry intersects a
 * wall on the same floor is silently dropped from the adjacency list — unless
 * at least one endpoint of that edge is a [NodeType.DOOR] node, because door
 * nodes represent the authorised crossing point at the wall.
 *
 * Walls are stored in the Room database and synced as part of [NavGraphExport]
 * via [NavGistSync].
 *
 * @param id       UUID — stable across Gist round-trips.
 * @param building Building slug (matches [NavNode.building]).  Empty string for
 *                 legacy data recorded before buildings were tracked.
 * @param floor    Floor label this wall belongs to.  Only edges whose both
 *                 endpoints share this floor value are checked against the wall.
 * @param lat1     First endpoint latitude.
 * @param lon1     First endpoint longitude.
 * @param lat2     Second endpoint latitude.
 * @param lon2     Second endpoint longitude.
 * @param label    Optional human-readable note (e.g. "North corridor wall").
 */
@Serializable
@Entity(tableName = "nav_walls")
data class NavWall(
    @PrimaryKey val id:       String,
    val building:             String = "",
    val floor:                String,
    val lat1:                 Double,
    val lon1:                 Double,
    val lat2:                 Double,
    val lon2:                 Double,
    val label:                String = ""
)

@Dao
interface NavWallDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(wall: NavWall)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(walls: List<NavWall>)

    @Query("SELECT * FROM nav_walls")
    suspend fun getAll(): List<NavWall>

    @Query("SELECT * FROM nav_walls WHERE floor = :floor")
    suspend fun getForFloor(floor: String): List<NavWall>

    @Query("DELETE FROM nav_walls WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM nav_walls")
    suspend fun clearAll()
}
