package com.example.metaversearapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── AP observation ────────────────────────────────────────────────────────────

/**
 * One access-point observation within a WiFi fingerprint.
 *
 * [rank] rather than [rssi] is the primary matching signal.  Rank order
 * (AP_1 is strongest, AP_2 is second…) is device-agnostic and stable
 * across time-of-day fluctuations, whereas absolute dBm readings can
 * differ by 10–15 dBm between phone models in the same physical spot.
 */
@Serializable
data class BssidObservation(
    val bssid: String,   // AP MAC address — globally unique per physical radio
    val rssi:  Int,      // dBm, kept for diagnostics and fallback
    val rank:  Int,      // 1 = strongest AP in this snapshot
    val freq:  Int       // MHz: 2412 = 2.4 GHz ch 1, 5180 = 5 GHz ch 36
)

/** Room TypeConverter — stores List<BssidObservation> as a compact JSON string. */
class BssidListConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter fun fromJson(value: String): List<BssidObservation> =
        json.decodeFromString(value)

    @TypeConverter fun toJson(list: List<BssidObservation>): String =
        json.encodeToString(list)
}

// ── Grid fingerprint ──────────────────────────────────────────────────────────

/**
 * A WiFi scan snapshot captured at a specific nav node during the admin walk.
 *
 * Each nav node is a grid cell (~1.5 m spacing).  Attaching a fingerprint
 * to every grid cell turns the nav walk into an indoor positioning grid:
 * the same data structure that powers commercial systems like IndoorAtlas.
 *
 * Multiple fingerprints per node are normal — the admin may walk the same
 * corridor on different days.  More samples per cell → more robust matching.
 */
@Serializable
@Entity(
    tableName = "wifi_fingerprints",
    indices   = [Index(value = ["nodeId"])]
)
data class WifiFingerprint(
    @PrimaryKey val id:           String,
    val nodeId:                   String,
    val observations:             List<BssidObservation>,
    val timestamp:                Long = System.currentTimeMillis()
)

// ── Room AP ───────────────────────────────────────────────────────────────────

/**
 * Records the dominant AP when the admin stood at a specific room door.
 *
 * Because every room has a dedicated AP, the strongest BSSID at a door
 * is almost always that room's own AP.  When a user later sees this BSSID
 * at high strength, the app infers they are near that room — a lightweight
 * room-proximity signal with zero extra admin effort.
 */
@Serializable
@Entity(tableName = "room_aps")
data class RoomAp(
    @PrimaryKey val qrId:    String,   // matches QrLocation.qrID
    val bssid:               String,
    val rssi:                Int,
    val timestamp:           Long = System.currentTimeMillis()
)
