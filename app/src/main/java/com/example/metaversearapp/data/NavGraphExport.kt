package com.example.metaversearapp.data

import kotlinx.serialization.Serializable

/** Wrapper used when exporting / importing the full nav graph as JSON. */
@Serializable
data class NavGraphExport(
    val nodes: List<NavNode>,
    val edges: List<NavEdge>,
    /**
     * Per-(building, floor) canonical GPS altitudes.
     * Stored as a flat list so each entry carries its own building + floor key.
     * Gists uploaded before the building field was introduced will have an empty
     * list here; the app will re-derive altitudes from node medians on first load.
     */
    val floorAltitudes: List<FloorAltitude> = emptyList()
)
