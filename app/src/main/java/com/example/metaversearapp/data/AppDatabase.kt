package com.example.metaversearapp.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [QrLocation::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun qrDao(): QrDao
}
