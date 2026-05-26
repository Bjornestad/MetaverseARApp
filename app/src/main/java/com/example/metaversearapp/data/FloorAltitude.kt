package com.example.metaversearapp.data

import androidx.room.*
import kotlinx.serialization.Serializable

/**
 * Canonical GPS altitude for a specific (building, floor) pair.
 *
 * Buildings at a university have completely different floor heights, so altitude
 * must be tracked per-building rather than globally per floor label.
 *
 * Written once (from the median of the first 5 GPS samples) and never
 * overwritten by subsequent sessions — all nodes and calibration in that
 * building share the same stable reference altitude.
 *
 * [building] defaults to "" for nodes recorded before the building field was
 * introduced, keeping backward compatibility with older DB rows.
 */
@Serializable
@Entity(
    tableName    = "floor_altitudes",
    primaryKeys  = ["building", "floor"]
)
data class FloorAltitude(
    val building: String = "",
    val floor:    String,
    val alt:      Double
)

@Dao
interface FloorAltDao {
    @Query("SELECT * FROM floor_altitudes ORDER BY building ASC, floor ASC")
    suspend fun getAll(): List<FloorAltitude>

    @Query("SELECT alt FROM floor_altitudes WHERE building = :building AND floor = :floor LIMIT 1")
    suspend fun getAlt(building: String, floor: String): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fa: FloorAltitude)

    @Query("DELETE FROM floor_altitudes")
    suspend fun clearAll()
}
