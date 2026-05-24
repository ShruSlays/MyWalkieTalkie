package com.moc.walkietalkie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.moc.walkietalkie.ui.WalkieTalkieViewModel
import com.moc.walkietalkie.ui.WalkieTalkieScreen

class MainActivity : ComponentActivity() {

    private val viewModel by lazy { WalkieTalkieViewModel(this) }
    private var permissionsRequested = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            Log.d("MainActivity", "Permission granted, initializing audio...")
            viewModel.setContext(this)
            viewModel.initializeAudio()
        } else {
            Log.e("MainActivity", "Permission denied")
        }
        permissionsRequested = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set context for the view model immediately
        viewModel.setContext(this)
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WalkieTalkieScreen(
                        viewModel = viewModel,
                        onRequestPermissions = { checkAndRequestPermissions() }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (permissionsRequested) {
            Log.d("MainActivity", "Permissions already requested, skipping")
            return
        }
        
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            Log.d("MainActivity", "Permissions already granted, initializing audio...")
            viewModel.initializeAudio()
        } else {
            Log.d("MainActivity", "Requesting missing permissions: $missingPermissions")
            permissionsRequested = true
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        // Check permissions on resume only if not initialized
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED && !viewModel.isInitialized.value) {
            Log.d("MainActivity", "Resuming with permissions, initializing audio...")
            viewModel.setContext(this)
            viewModel.initializeAudio()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.cleanup()
    }
}
