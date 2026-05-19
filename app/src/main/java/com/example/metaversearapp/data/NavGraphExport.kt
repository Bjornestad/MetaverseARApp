package com.example.metaversearapp.data

import kotlinx.serialization.Serializable

/**
 * Full serialisable snapshot of the nav graph — nodes, edges, WiFi grid
 * fingerprints, and room-AP associations — written to / read from the Gist.
 *
 * [wifiFingerprints] and [roomAps] default to empty so that older Gist
 * exports (pre-WiFi) still deserialise correctly without errors.
 */
@Serializable
data class NavGraphExport(
    val nodes:            List<NavNode>,
    val edges:            List<NavEdge>,
    val wifiFingerprints: List<WifiFingerprint> = emptyList(),
    val roomAps:          List<RoomAp>          = emptyList()
)
