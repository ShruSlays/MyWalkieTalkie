package com.moc.walkietalkie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private lateinit var viewModel: WalkieTalkieViewModel
    private var permissionsGranted by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val locGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        } else {
            true
        }
        
        permissionsGranted = micGranted && locGranted
        
        if (permissionsGranted) {
            viewModel = WalkieTalkieViewModel(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (permissionsGranted && ::viewModel.isInitialized) {
                        WalkieTalkieScreen(viewModel = viewModel)
                    } else {
                        PermissionRequiredScreen(
                            onRequestPermissions = { checkPermissions() },
                            isPermanentDenied = shouldShowRequestPermissionRationale()
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        val micPermission = Manifest.permission.RECORD_AUDIO
        val locPermission = Manifest.permission.ACCESS_FINE_LOCATION
        
        val hasMic = ContextCompat.checkSelfPermission(this, micPermission) == PackageManager.PERMISSION_GRANTED
        val hasLoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, locPermission) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (hasMic && hasLoc) {
            permissionsGranted = true
            viewModel = WalkieTalkieViewModel(this)
        } else {
            requestPermissionLauncher.launch(arrayOf(micPermission, locPermission))
        }
    }

    private fun shouldShowRequestPermissionRationale(): Boolean {
        val micPermission = Manifest.permission.RECORD_AUDIO
        val locPermission = Manifest.permission.ACCESS_FINE_LOCATION
        
        val showMic = shouldShowRequestPermissionRationale(micPermission)
        val showLoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shouldShowRequestPermissionRationale(locPermission)
        } else {
            false
        }
        
        return showMic || showLoc
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (::viewModel.isInitialized) {
            viewModel.cleanup()
        }
    }
}

@Composable
fun PermissionRequiredScreen(onRequestPermissions: () -> Unit, isPermanentDenied: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Permissions Required",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "This app needs Microphone and Location permissions to function over Wi-Fi.\n\n" +
                   if (isPermanentDenied) 
                       "Permissions were denied permanently. Please enable them in Settings > Apps > MoC Walkie Talkie > Permissions." 
                   else 
                       "Please grant permissions to continue.",
            fontSize = 16.sp,
            color = Color.Gray,
            lineHeight = 24.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        if (!isPermanentDenied) {
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
            ) {
                Text("Grant Permissions", color = Color.White, fontSize = 18.sp)
            }
        }
    }
}
