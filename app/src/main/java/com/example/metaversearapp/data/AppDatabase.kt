package com.example.metaversearapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [QrLocation::class, NavNode::class, NavEdge::class],
    version = 6,
    exportSchema = false
)
@TypeConverters(NodeTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun qrDao(): QrDao
    abstract fun navDao(): NavDao
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
