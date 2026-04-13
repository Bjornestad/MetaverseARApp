package com.example.metaversearapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The flat database entity used by Room.
 */
@Entity(tableName = "qr_locations")
data class QrLocation(
    @PrimaryKey val qrID: String,
    val name: String,
    val building: String,
    val floor: String,
    val lat: Double,
    val lon: Double,
    val alt: Double = 0.0,
    val direction: String = "Unknown"
)

/**
 * Data classes matching the new JSON structure for API deserialization.
 */
@Serializable
data class QrFeature(
    val id: String? = null,
    val properties: QrProperties,
    val area: Int = 0
)

@Serializable
data class QrProperties(
    @SerialName("QRId") val qrId: String,
    val name: String,
    val building: String,
    val floor: String,
    val anchor: QrAnchor,
    val externalId: String? = null,
    val roomId: String? = null
)

@Serializable
data class QrAnchor(
    val coordinates: List<Double>, // [longitude, latitude]
    val type: String = "Point"
)

/**
 * Mapper extension to convert the API model to the Database entity.
 */
fun QrFeature.toEntity(): QrLocation {
    return QrLocation(
        qrID = properties.qrId,
        name = properties.name,
        building = properties.building,
        floor = properties.floor,
        // JSON coordinates are [longitude, latitude]
        lon = properties.anchor.coordinates.getOrNull(0) ?: 0.0,
        lat = properties.anchor.coordinates.getOrNull(1) ?: 0.0,
        alt = 0.0
    )
}
