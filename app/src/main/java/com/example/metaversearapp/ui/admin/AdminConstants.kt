package com.example.metaversearapp.ui.admin

internal const val ADMIN_PIN              = "1234"   // change in production

/**
 * Guards the Gist sync so it only runs once per process lifetime.
 * Resets automatically on app restart (which is the desired behaviour —
 * a fresh launch should always pull the latest graph).
 * Prevents the race where returning from a recording session re-enters
 * AdminHubScreen, fires LaunchedEffect(Unit) again, and wipes new nodes.
 */
internal var adminGistSyncDone            = false

internal const val MIN_NODE_DISTANCE_M     = 1.5      // metres between auto-captured nodes
internal const val CAPTURE_INTERVAL_MS     = 2_000L   // max capture rate
internal const val STAIR_CONNECT_RADIUS_M  = 10.0     // max horizontal metres to auto-link stair endpoints
internal const val QR_LINK_RADIUS_M        = 12.0     // max metres to auto-link a DOOR node to a nearby QR room anchor
internal const val SEGMENT_SNAP_RADIUS_M   = 5.0      // max metres to auto-bridge a new node to an existing one during recording

/** Node capture is suppressed when VPS horizontal accuracy exceeds this threshold.
 *  Nodes placed with poor precision end up in the wrong corridor, causing A* to
 *  route through walls.  3 m is a reasonable indoor ceiling — tighten to 2.0 for
 *  very dense graphs or loosen to 5.0 where VPS signal is weak. */
internal const val MAX_CAPTURE_PRECISION_M = 3.0

internal val FLOOR_OPTIONS = listOf("-2", "-1", "0", "1", "2", "3", "4", "5")

/** Represents the lifecycle of a Cloud Anchor host operation. */
internal sealed class HostState {
    object Idle                           : HostState()
    object Hosting                        : HostState()
    data class Hosted(val id: String)     : HostState()
    data class Failed(val reason: String) : HostState()
}
