package com.example.metaversearapp.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface QrDao {
    @Query("SELECT * FROM qr_locations WHERE qrID = :id")
    suspend fun getById(id: String): QrLocation?

    @Query("SELECT * FROM qr_locations")
    suspend fun getAll(): List<QrLocation>

    @Upsert
    suspend fun insertAll(locations: List<QrLocation>): List<Long>

    /** Persists the sensor-captured facing direction for a single QR location. */
    @Query("UPDATE qr_locations SET facingDeg = :deg WHERE qrID = :id")
    suspend fun updateFacingDeg(id: String, deg: Double)
}
