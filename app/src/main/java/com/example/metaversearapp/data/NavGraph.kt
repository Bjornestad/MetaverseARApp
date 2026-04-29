package com.example.metaversearapp.data

import java.util.PriorityQueue
import kotlin.math.*

/**
 * In-memory A* pathfinder and helper utilities for the nav graph.
 * All graph data comes from Room (NavNode / NavEdge tables).
 */
object NavGraphPathfinder {

    // ── Geometry ──────────────────────────────────────────────────────────────

    /** Haversine great-circle distance in metres between two lat/lon points (2D). */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    /**
     * 3-D straight-line distance in metres, combining horizontal Haversine
     * with the vertical altitude difference.  Used for edge weights on stair /
     * multi-floor segments so A* correctly accounts for the cost of climbing.
     */
    fun distance3d(
        lat1: Double, lon1: Double, alt1: Double,
        lat2: Double, lon2: Double, alt2: Double
    ): Double {
        val horiz = haversine(lat1, lon1, lat2, lon2)
        val vert  = abs(alt2 - alt1)
        return sqrt(horiz * horiz + vert * vert)
    }

    /** Returns the node closest to the given lat/lon, or null if the list is empty. */
    fun nearestNode(nodes: List<NavNode>, lat: Double, lon: Double): NavNode? =
        nodes.minByOrNull { haversine(lat, lon, it.lat, it.lon) }

    /**
     * Compass bearing in degrees (0 = north, 90 = east, clockwise) from point 1 to point 2.
     */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon  = Math.toRadians(lon2 - lon1)
        val lat1R = Math.toRadians(lat1)
        val lat2R = Math.toRadians(lat2)
        val y = sin(dLon) * cos(lat2R)
        val x = cos(lat1R) * sin(lat2R) - sin(lat1R) * cos(lat2R) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Converts a compass [bearingDeg] (0 = north, clockwise) into the
     * (qx, qy, qz, qw) quaternion expected by [Earth.createAnchor].
     *
     * In ARCore's East-Up-South frame the anchor's +Z axis points South by
     * default.  We rotate around Y so that +Z ends up facing [bearingDeg].
     */
    fun bearingToQuaternion(bearingDeg: Double): FloatArray {
        val theta = Math.toRadians(180.0 - bearingDeg)   // rotation from South to bearing
        return floatArrayOf(
            0f,
            sin(theta / 2).toFloat(),
            0f,
            cos(theta / 2).toFloat()
        )
    }

    // ── A* ────────────────────────────────────────────────────────────────────

    /**
     * Finds the shortest path between [startId] and [goalId] using A*.
     *
     * Edges are treated as **undirected** (both directions are explored).
     * The heuristic uses 3-D straight-line distance so altitude differences
     * (e.g. navigating to a different floor via stairs) are properly estimated.
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
        val goal    = nodeMap[goalId] ?: return emptyList()

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

    /**
     * Admissible heuristic: 3-D straight-line distance to the goal.
     * Including altitude ensures the heuristic never over-estimates on
     * multi-floor paths, keeping A* optimal.
     */
    private fun h(node: NavNode, goal: NavNode) =
        distance3d(node.lat, node.lon, node.alt, goal.lat, goal.lon, goal.alt)

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
