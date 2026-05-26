package com.example.metaversearapp.data

import kotlinx.serialization.Serializable

/**
 * A named navigable destination (a room, office, or landmark) in the building.
 *
 * Rooms are stored in a standalone GitHub Gist ([RoomGistSync]) that is completely
 * independent of QR codes.  Admins create rooms by placing a DOOR node during a
 * recording walk and entering a name — the app generates an [id] from the name,
 * stores it on the [NavNode] ([NavNode.anchorQrId] / [NavNode.label]), and pushes
 * the room to the Gist so all users see the updated destination list on next launch.
 *
 * [lat] / [lon] / [alt] mirror the recorded door node's GPS position so A*
 * pathfinding can snap to the nearest navgraph node for the route calculation.
 */
@Serializable
data class Room(
    val id:    String,
    val name:  String,
    val floor: String,
    val lat:   Double,
    val lon:   Double,
    val alt:   Double = 0.0
)

/**
 * Converts a human-readable room name into a URL/ID-safe slug.
 * e.g. "Room 101 (North)" → "room_101_north"
 */
fun String.toRoomId(): String = trim()
    .lowercase()
    .replace(Regex("\\s+"), "_")
    .replace(Regex("[^a-z0-9_]"), "")
    .take(40)
    .trimEnd('_')
    .ifBlank { "room" }
