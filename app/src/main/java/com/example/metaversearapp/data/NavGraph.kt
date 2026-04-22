package com.example.metaversearapp.data

import java.util.PriorityQueue
import kotlin.math.*

/**
 * In-memory A* pathfinder and helper utilities for the nav graph.
 * All graph data comes from Room (NavNode / NavEdge tables).
 */
object NavGraphPathfinder {

    // ── Geometry ──────────────────────────────────────────────────────────────

    /** Haversine great-circle distance in metres between two lat/lon points. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    /** Returns the node closest to the given lat/lon, or null if the list is empty. */
    fun nearestNode(nodes: List<NavNode>, lat: Double, lon: Double): NavNode? =
        nodes.minByOrNull { haversine(lat, lon, it.lat, it.lon) }

    // ── A* ────────────────────────────────────────────────────────────────────

    /**
     * Finds the shortest path between [startId] and [goalId] using A*.
     *
     * Edges are treated as **undirected** (both directions are explored).
     *
     * @return Ordered list of [NavNode]s from start to goal, or empty if no path exists.
     */
    fun aStar(
        nodes: List<NavNode>,
        edges: List<NavEdge>,
        startId: String,
        goalId: String
    ): List<NavNode> {
        if (startId == goalId) return listOfNotNull(nodes.find { it.id == startId })

        val nodeMap = nodes.associateBy { it.id }
        val goal   = nodeMap[goalId] ?: return emptyList()

        // Build undirected adjacency list: nodeId -> [(neighborId, weight)]
        val adj = HashMap<String, MutableList<Pair<String, Double>>>(nodes.size * 2)
        for (e in edges) {
            adj.getOrPut(e.fromId) { mutableListOf() } += e.toId   to e.weight
            adj.getOrPut(e.toId)   { mutableListOf() } += e.fromId to e.weight
        }

        val gScore   = HashMap<String, Double>().withDefault { Double.MAX_VALUE }
        val cameFrom = HashMap<String, String>()
        val closed   = HashSet<String>()

        gScore[startId] = 0.0

        // Min-heap ordered by fScore = gScore + heuristic
        val open = PriorityQueue<Pair<Double, String>>(compareBy { it.first })
        open += h(nodeMap[startId]!!, goal) to startId

        while (open.isNotEmpty()) {
            val (_, current) = open.poll()!!
            if (!closed.add(current)) continue        // already processed
            if (current == goalId) return reconstruct(cameFrom, nodeMap, goalId)

            val g = gScore.getValue(current)
            for ((nb, w) in adj[current] ?: emptyList()) {
                if (nb in closed) continue
                val tentG = g + w
                if (tentG < gScore.getValue(nb)) {
                    cameFrom[nb] = current
                    gScore[nb]   = tentG
                    val nbNode   = nodeMap[nb] ?: continue
                    open        += (tentG + h(nbNode, goal)) to nb
                }
            }
        }
        return emptyList()   // no path found
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun h(node: NavNode, goal: NavNode) =
        haversine(node.lat, node.lon, goal.lat, goal.lon)

    private fun reconstruct(
        cameFrom: Map<String, String>,
        nodeMap: Map<String, NavNode>,
        goalId: String
    ): List<NavNode> {
        val path = ArrayDeque<NavNode>()
        var cur  = goalId
        while (true) {
            nodeMap[cur]?.let { path.addFirst(it) }
            cur = cameFrom[cur] ?: break
        }
        return path.toList()
    }
}
