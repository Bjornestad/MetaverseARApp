package com.example.metaversearapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.metaversearapp.data.AppDatabase
import com.example.metaversearapp.ui.admin.AdminHubScreen
import com.example.metaversearapp.ui.admin.AdminRecordingScreen
import com.example.metaversearapp.ui.admin.PinGateScreen

/**
 * Top-level Admin screen router.
 *
 * Manages authentication state and delegates to the appropriate sub-screen:
 *  - [PinGateScreen]        — PIN entry (unauthenticated)
 *  - [AdminHubScreen]       — stats, upload, export (authenticated, not recording)
 *  - [AdminRecordingScreen] — live AR recording session
 *
 * [currentFloor] is hoisted here so the selected floor persists across
 * recording sessions (AdminRecordingScreen is destroyed on finish, which
 * would otherwise reset the floor back to its default).
 */
@Composable
fun AdminScreen(db: AppDatabase, onExitAdmin: () -> Unit) {
    var isAuthenticated by rememberSaveable { mutableStateOf(false) }
    var isRecording     by rememberSaveable { mutableStateOf(false) }
    var currentFloor    by rememberSaveable { mutableStateOf("1") }

    // Back gesture / button handling:
    //  - During a recording session → stop recording and return to the hub
    //  - On the hub or PIN screen   → exit admin and return to the AR screen
    BackHandler {
        if (isRecording) isRecording = false else onExitAdmin()
    }

    if (!isAuthenticated) {
        PinGateScreen(
            onSuccess = { isAuthenticated = true },
            onCancel  = onExitAdmin
        )
    } else if (!isRecording) {
        AdminHubScreen(
            db            = db,
            onStartRecord = { isRecording = true },
            onExit        = onExitAdmin
        )
    } else {
        AdminRecordingScreen(
            db            = db,
            currentFloor  = currentFloor,
            onFloorChange = { currentFloor = it },
            onFinished    = { isRecording = false }
        )
    }
}
