package com.example.metaversearapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.room.Room
import com.example.metaversearapp.data.AppDatabase
import com.example.metaversearapp.data.MIGRATION_3_4
import com.example.metaversearapp.ui.ARScreen
import com.example.metaversearapp.ui.AdminScreen
import com.example.metaversearapp.ui.theme.MetaverseARappTheme

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "ar-db")
            .addMigrations(MIGRATION_3_4)
            .fallbackToDestructiveMigration(true)
            .build()

        setContent {
            MetaverseARappTheme {
                var isAdminMode by rememberSaveable { mutableStateOf(false) }

                if (isAdminMode) {
                    AdminScreen(
                        db           = db,
                        onExitAdmin  = { isAdminMode = false }
                    )
                } else {
                    ARScreen(
                        db             = db,
                        onAdminRequest = { isAdminMode = true }
                    )
                }
            }
        }
    }
}
