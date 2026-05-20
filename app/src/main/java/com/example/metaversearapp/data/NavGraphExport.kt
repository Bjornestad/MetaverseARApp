package com.example.metaversearapp.data

import kotlinx.serialization.Serializable

/** Wrapper used when exporting / importing the full nav graph as JSON. */
@Serializable
data class NavGraphExport(
    val nodes: List<NavNode>,
    val edges: List<NavEdge>,
    val floorAltitudes: Map<String, Double> = emptyMap()
)
