package com.example.metaversearapp.data

import androidx.room.*

/**
 * Canonical GPS altitude for each recorded floor label.
 * Written once (first 5 GPS samples on a new floor) and never overwritten by
 * subsequent sessions, so all nodes and calibration share the same reference.
 */
@Entity(tableName = "floor_altitudes")
data class FloorAltitude(
    @PrimaryKey val floor: String,
    val alt: Double
)

@Dao
interface FloorAltDao {
    @Query("SELECT * FROM floor_altitudes ORDER BY floor ASC")
    suspend fun getAll(): List<FloorAltitude>

    @Query("SELECT alt FROM floor_altitudes WHERE floor = :floor LIMIT 1")
    suspend fun getAlt(floor: String): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fa: FloorAltitude)

    @Query("DELETE FROM floor_altitudes")
    suspend fun clearAll()
}
