package com.example.metaversearapp.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Accepts two historical formats for the floorAltitudes field:
 *
 *   Old (map): { "0": 70.5, "1": 67.4 }
 *   New (list): [ { "building": "", "floor": "0", "alt": 70.5 }, … ]
 *
 * On read, maps the old format to the new one so old Gist files deserialise
 * without error. On write, always emits the new list format.
 */
private object FloorAltListSerializer :
    JsonTransformingSerializer<List<FloorAltitude>>(ListSerializer(FloorAltitude.serializer())) {

    override fun transformDeserialize(element: JsonElement): JsonElement {
        // New format — already a JSON array, pass straight through.
        if (element is JsonArray) return element
        // Old format — JSON object with floor-label keys mapping to raw altitude doubles.
        // Convert each entry to a FloorAltitude-shaped object before deserialising.
        if (element is JsonObject) {
            return JsonArray(element.entries.map { (floor, alt) ->
                JsonObject(
                    mapOf(
                        "building" to JsonPrimitive(""),
                        "floor"    to JsonPrimitive(floor),
                        "alt"      to alt
                    )
                )
            })
        }
        return element
    }
}

/** Wrapper used when exporting / importing the full nav graph as JSON. */
@Serializable
data class NavGraphExport(
    val nodes: List<NavNode>,
    val edges: List<NavEdge>,
    /**
     * Per-(building, floor) canonical GPS altitudes.
     * Stored as a flat list so each entry carries its own building + floor key.
     *
     * Two formats are accepted on read via [FloorAltListSerializer]:
     *  • Old Gists: a JSON object  { "0": 70.5, "1": 67.4 }
     *  • New Gists: a JSON array   [ { "building": "", "floor": "0", "alt": 70.5 } ]
     *
     * The next upload from the admin screen will rewrite the field in the new
     * list format, migrating the Gist automatically.
     */
    @Serializable(with = FloorAltListSerializer::class)
    val floorAltitudes: List<FloorAltitude> = emptyList(),
    /**
     * Wall segments that block pathfinding.
     * Any edge whose straight-line geometry crosses a wall on the same floor is
     * dropped from the A* adjacency list (unless one endpoint is a DOOR node).
     * Older Gist files without this field deserialise to an empty list.
     */
    val walls: List<NavWall> = emptyList()
)
