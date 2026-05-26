package com.example.metaversearapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [QrLocation::class, NavNode::class, NavEdge::class, FloorAltitude::class],
    version = 9,
    exportSchema = false
)
@TypeConverters(NodeTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun qrDao(): QrDao
    abstract fun navDao(): NavDao
    abstract fun floorAltDao(): FloorAltDao
}

/**
 * Adds the [NodeType] column to nav_nodes with a safe default of 'WAYPOINT'.
 * Existing recorded nodes keep all their data and are treated as plain waypoints.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE nav_nodes ADD COLUMN type TEXT NOT NULL DEFAULT 'WAYPOINT'"
        )
    }
}

/**
 * Adds the [cloudAnchorId] column to nav_nodes.
 * Nullable — existing nodes have no cloud anchor until an admin explicitly hosts one.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE nav_nodes ADD COLUMN cloudAnchorId TEXT"
        )
    }
}

/**
 * Adds the [cloudAnchorHeading] column to nav_nodes.
 * Nullable REAL — records the compass heading (degrees) of the device at the
 * moment the cloud anchor was hosted, so the viewer can show a direction arrow.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE nav_nodes ADD COLUMN cloudAnchorHeading REAL"
        )
    }
}

/**
 * Adds [QrLocation.facingDeg] — the sensor-captured compass bearing an admin
 * faces when looking at this QR code.  Used to replace GPS-based bearing
 * calculation in [ARViewModel.onQrScanned], avoiding 180° heading flips.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE qr_locations ADD COLUMN facingDeg REAL"
        )
    }
}

/**
 * Adds the [FloorAltitude] table. Canonical GPS altitude per floor label,
 * established at first recording and shared across all sessions.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS floor_altitudes (floor TEXT NOT NULL PRIMARY KEY, alt REAL NOT NULL)"
        )
    }
}

/**
 * Adds per-building floor altitude support.
 *
 * 1. Adds [NavNode.building] column (default "" for all existing nodes so they
 *    continue to belong to the implicit "unknown building").
 * 2. Recreates [floor_altitudes] with a composite primary key (building, floor).
 *    Existing single-floor rows are migrated with building = "".
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add building column to nav_nodes
        database.execSQL(
            "ALTER TABLE nav_nodes ADD COLUMN building TEXT NOT NULL DEFAULT ''"
        )
        // Recreate floor_altitudes with composite PK (building, floor)
        database.execSQL(
            "CREATE TABLE floor_altitudes_new (" +
            "building TEXT NOT NULL DEFAULT '', " +
            "floor TEXT NOT NULL, " +
            "alt REAL NOT NULL, " +
            "PRIMARY KEY (building, floor))"
        )
        // Migrate existing rows, assigning them to the empty-string building
        database.execSQL(
            "INSERT INTO floor_altitudes_new (building, floor, alt) " +
            "SELECT '', floor, alt FROM floor_altitudes"
        )
        database.execSQL("DROP TABLE floor_altitudes")
        database.execSQL("ALTER TABLE floor_altitudes_new RENAME TO floor_altitudes")
    }
}
