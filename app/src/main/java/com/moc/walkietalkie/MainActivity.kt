package com.moc.walkietalkie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.moc.walkietalkie.ui.WalkieTalkieViewModel
import com.moc.walkietalkie.ui.WalkieTalkieScreen

class MainActivity : ComponentActivity() {

    private val viewModel by lazy { WalkieTalkieViewModel(this) }

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
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.cleanup()
    }
}
