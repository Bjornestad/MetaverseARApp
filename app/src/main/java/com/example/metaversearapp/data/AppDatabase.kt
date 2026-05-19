package com.example.metaversearapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        QrLocation::class,
        NavNode::class,
        NavEdge::class,
        WifiFingerprint::class,
        RoomAp::class
    ],
    version      = 5,
    exportSchema = false
)
@TypeConverters(NodeTypeConverter::class, BssidListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun qrDao():   QrDao
    abstract fun navDao():  NavDao
    abstract fun wifiDao(): WifiDao
}

/** Adds NodeType column to nav_nodes (3 → 4). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE nav_nodes ADD COLUMN type TEXT NOT NULL DEFAULT 'WAYPOINT'"
        )
    }
}

/** Adds wifi_fingerprints and room_aps grid tables (4 → 5). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS wifi_fingerprints (
                id           TEXT    NOT NULL PRIMARY KEY,
                nodeId       TEXT    NOT NULL,
                observations TEXT    NOT NULL,
                timestamp    INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_wifi_fp_nodeId ON wifi_fingerprints(nodeId)"
        )
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS room_aps (
                qrId      TEXT    NOT NULL PRIMARY KEY,
                bssid     TEXT    NOT NULL,
                rssi      INTEGER NOT NULL,
                timestamp INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}
