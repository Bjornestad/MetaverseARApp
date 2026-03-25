package com.example.metaversearapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.metaversearapp.ui.theme.MetaverseARappTheme
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.*

class MainActivity : ComponentActivity() {
    private var arSession: Session? = null
    private var isGeospatialSupported by mutableStateOf(false)
    private var geospatialStatus by mutableStateOf("Initializing...")

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                setupSession()
            } else {
                Toast.makeText(this, "Permissions required for AR & Location", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkPermissionsAndStart()

        setContent {
            MetaverseARappTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GeospatialDashboard(status = geospatialStatus, isSupported = isGeospatialSupported)
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            setupSession()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun setupSession() {
        try {
            arSession = Session(this)
            val config = Config(arSession)
            
            // Enable Geospatial Mode
            if (arSession?.isGeospatialModeSupported(Config.GeospatialMode.ENABLED) == true) {
                config.geospatialMode = Config.GeospatialMode.ENABLED
                isGeospatialSupported = true
                geospatialStatus = "Geospatial Mode Enabled"
            } else {
                isGeospatialSupported = false
                geospatialStatus = "Geospatial not supported on this device"
            }
            
            arSession?.configure(config)
            arSession?.resume()
        } catch (e: Exception) {
            handleSessionException(e)
        }
    }

    private fun handleSessionException(e: Exception) {
        val message = when (e) {
            is UnavailableArcoreNotInstalledException -> "Please install ARCore"
            is UnavailableApkTooOldException -> "Please update ARCore"
            is UnavailableSdkTooOldException -> "Please update the app"
            is UnavailableDeviceNotCompatibleException -> "Device not compatible with AR"
            else -> "Error initializing AR: ${e.message}"
        }
        geospatialStatus = message
        Log.e("ARCore", message, e)
    }

    override fun onResume() {
        super.onResume()
        try {
            arSession?.resume()
        } catch (e: Exception) {
            handleSessionException(e)
        }
    }

    override fun onPause() {
        super.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSession?.close()
        arSession = null
    }
}

@Composable
fun GeospatialDashboard(status: String, isSupported: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "ARCore Geospatial Status:", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = status,
            color = if (isSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}
