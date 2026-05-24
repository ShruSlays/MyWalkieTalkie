package com.moc.walkietalkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moc.walkietalkie.ui.WalkieTalkieViewModel

@Composable
fun WalkieTalkieScreen(
    viewModel: WalkieTalkieViewModel
) {
    val isTransmitting by viewModel.isTransmitting.collectAsState()
    val isListening by viewModel.isListening.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = if (isTransmitting) {
                        listOf(Color(0xFFD32F2F), Color(0xFFB71C1C))
                    } else if (isListening) {
                        listOf(Color(0xFF388E3C), Color(0xFF1B5E20))
                    } else {
                        listOf(Color(0xFF424242), Color(0xFF212121))
                    }
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Status indicator text
            Text(
                text = if (isTransmitting) {
                    "TRANSMITTING"
                } else if (isListening) {
                    "LISTENING"
                } else {
                    "STOP SPEAKING"
                },
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            // Large toggle button
            Button(
                onClick = { viewModel.toggleTransmit() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTransmitting) {
                        Color(0xFFFFEBEE)
                    } else {
                        Color(0xFFE8F5E9)
                    }
                ),
                modifier = Modifier
                    .size(200.dp)
                    .scale(if (isTransmitting) 1.05f else 1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Microphone icon (simple circle representation)
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(
                                    color = if (isTransmitting) {
                                        Color(0xFFD32F2F)
                                    } else {
                                        Color(0xFF388E3C)
                                    },
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isTransmitting) "🎤" else "👂",
                                fontSize = 40.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = if (isTransmitting) {
                                "RELEASE TO LISTEN"
                            } else {
                                "PRESS TO TALK"
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isTransmitting) {
                                Color(0xFFD32F2F)
                            } else {
                                Color(0xFF388E3C)
                            },
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Additional status info
            Text(
                text = if (isTransmitting) {
                    "Broadcasting audio to all devices on Wi-Fi"
                } else {
                    "Receiving audio from all devices on Wi-Fi"
                },
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
