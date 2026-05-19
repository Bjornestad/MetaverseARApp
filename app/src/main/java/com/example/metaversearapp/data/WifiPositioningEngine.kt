package com.example.metaversearapp.data

import android.net.wifi.ScanResult
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Rank-based k-NN indoor positioning engine.
 *
 * ## The grid model
 * The nav walk produces a spatial grid: nav nodes at ~1.5 m spacing along
 * every corridor.  Each node has a [WifiFingerprint] — a snapshot of the
 * 20 strongest APs at that grid cell.  Matching the user's current WiFi
 * scan to stored fingerprints tells you which grid cell they are in.
 *
 * ## Why rank order instead of raw RSSI
 * Absolute dBm values vary by ±10–15 dBm across device models and by
 * ±5 dBm over time.  Rank order (AP_1 strongest, AP_2 second, …) is
 * device-agnostic and much more stable.  Two phones in the same corridor
 * will almost always agree on which AP is rank-1.
 *
 * ## Scoring
 * For each stored fingerprint we compute a normalised Spearman rank
 * distance, then multiply by a coverage factor (fraction of BSSIDs shared
 * between the current scan and the fingerprint).  A fingerprint from across
 * the building shares few BSSIDs and scores near zero automatically —
 * no explicit radius filter is needed.
 *
 * ## k-NN position average
 * The top-K scoring fingerprints (K = 3) produce a score-weighted
 * geographic average.  Because grid cells are ~1.5 m apart, the result
 * interpolates smoothly between recorded positions rather than snapping.
 */
object WifiPositioningEngine {

    /** Minimum shared BSSIDs before a fingerprint is considered a candidate. */
    private const val MIN_COMMON_APS = 3

    /** Number of nearest neighbours used in the weighted position average. */
    private const val K = 3

    /** Virtual "not visible" rank assigned to BSSIDs absent from a scan. */
    private const val MAX_RANK = 20

    // ── Public result ─────────────────────────────────────────────────────────

    data class PositionEstimate(
        val lat:           Double,
        val lon:           Double,
        /** 0.0 = no confidence, 1.0 = perfect rank match across many shared APs. */
        val confidence:    Double,
        /** Strongest BSSID in this scan — used for room-proximity lookup. */
        val dominantBssid: String?
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Derives a position estimate from [currentScan].
     *
     * @param currentScan  Raw results from WifiManager.scanResults.
     * @param fingerprints All stored grid fingerprints from [WifiDao].
     * @param nodes        Nav-graph nodes to resolve lat/lon from nodeId.
     */
    fun findPosition(
        currentScan:  List<ScanResult>,
        fingerprints: List<WifiFingerprint>,
        nodes:        List<NavNode>
    ): PositionEstimate? {
        if (currentScan.isEmpty() || fingerprints.isEmpty() || nodes.isEmpty()) return null

        // Build rank map: bssid → rank (1 = strongest), capped at MAX_RANK
        val ranked = currentScan.sortedByDescending { it.level }.take(MAX_RANK)
        val currentRanks: Map<String, Int> = ranked.mapIndexed { i, r -> r.BSSID to (i + 1) }.toMap()
        val dominantBssid = ranked.firstOrNull()?.BSSID

        val nodeMap = nodes.associateBy { it.id }

        // Score every fingerprint; discard zero-scores
        val candidates = fingerprints.mapNotNull { fp ->
            val node  = nodeMap[fp.nodeId] ?: return@mapNotNull null
            val score = rankScore(currentRanks, fp)
            if (score <= 0.0) null else Triple(node, fp, score)
        }.sortedByDescending { it.third }

        if (candidates.isEmpty()) return null

        // Score-weighted geographic average of top-K candidates
        val topK        = candidates.take(K)
        val totalWeight = topK.sumOf { it.third }
        val lat         = topK.sumOf { (n, _, s) -> n.lat * s } / totalWeight
        val lon         = topK.sumOf { (n, _, s) -> n.lon * s } / totalWeight

        return PositionEstimate(
            lat           = lat,
            lon           = lon,
            confidence    = topK.first().third.coerceIn(0.0, 1.0),
            dominantBssid = dominantBssid
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Similarity score in [0, 1]:
     *   (1 − normalised_spearman_distance) × coverage_fraction
     *
     * coverage_fraction = |shared BSSIDs| / |current scan BSSIDs|
     * Naturally penalises far-away fingerprints that share only a few APs.
     */
    private fun rankScore(currentRanks: Map<String, Int>, fp: WifiFingerprint): Double {
        val fpRanks = fp.observations.associate { it.bssid to it.rank }
        val common  = currentRanks.keys.intersect(fpRanks.keys)
        if (common.size < MIN_COMMON_APS) return 0.0

        val dSquaredSum = common.sumOf { bssid ->
            val diff = abs(
                (currentRanks[bssid] ?: MAX_RANK) - (fpRanks[bssid] ?: MAX_RANK)
            )
            diff * diff
        }.toDouble()

        val worstCase  = common.size.toDouble() * (MAX_RANK - 1.0) * (MAX_RANK - 1.0)
        val normalised = (dSquaredSum / worstCase).coerceIn(0.0, 1.0)
        val coverage   = common.size.toDouble() / currentRanks.size.toDouble()

        return (1.0 - normalised) * coverage
    }
}
