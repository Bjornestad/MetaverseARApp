package com.example.metaversearapp.data

import androidx.room.*

@Dao
interface WifiDao {

    // ── Grid fingerprints ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFingerprint(fp: WifiFingerprint)

    @Query("SELECT * FROM wifi_fingerprints")
    suspend fun getAllFingerprints(): List<WifiFingerprint>

    @Query("SELECT * FROM wifi_fingerprints WHERE nodeId = :nodeId")
    suspend fun getFingerprintsForNode(nodeId: String): List<WifiFingerprint>

    @Query("SELECT COUNT(*) FROM wifi_fingerprints")
    suspend fun fingerprintCount(): Int

    @Query("DELETE FROM wifi_fingerprints")
    suspend fun clearFingerprints()

    // ── Room APs ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRoomAp(ap: RoomAp)

    @Query("SELECT * FROM room_aps")
    suspend fun getAllRoomAps(): List<RoomAp>

    @Query("SELECT * FROM room_aps WHERE qrId = :qrId LIMIT 1")
    suspend fun getRoomAp(qrId: String): RoomAp?

    /** Finds the room associated with a given dominant BSSID — room-proximity detection. */
    @Query("SELECT * FROM room_aps WHERE bssid = :bssid LIMIT 1")
    suspend fun getRoomApByBssid(bssid: String): RoomAp?

    @Query("DELETE FROM room_aps")
    suspend fun clearRoomAps()
}
