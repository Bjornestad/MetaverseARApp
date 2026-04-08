package com.example.metaversearapp.data

import android.annotation.SuppressLint
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Entity(tableName = "qr_locations")
@Serializable
data class QrLocation(
    @PrimaryKey val qrID: String,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val direction: String,
    val floor: String,
    val building: String
)
