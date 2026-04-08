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
    suspend fun insertAll(locations: List<QrLocation>)
}
