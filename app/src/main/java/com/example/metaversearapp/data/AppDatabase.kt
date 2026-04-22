package com.example.metaversearapp.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [QrLocation::class, NavNode::class, NavEdge::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun qrDao(): QrDao
    abstract fun navDao(): NavDao
}
