package com.moc.walkietalkie.ui

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class WalkieTalkieViewModel(application: Context) : ViewModel() {

    companion object {
        private const val TAG = "WalkieTalkieVM"
        
        // Audio configuration
        private const val SAMPLE_RATE = 8000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
        
        // Network configuration
        private const val UDP_PORT = 5000
    }

    private val _context = MutableStateFlow<Context?>(null)
    
    fun setContext(context: Context) {
        _context.value = context.applicationContext
    }
    
    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    private val _isListening = MutableStateFlow(true)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var udpSocket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null
    
    private var transmitJob: Job? = null
    private var receiveJob: Job? = null
    
    private val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private val audioBufferSize = minBufferSize * BUFFER_SIZE_FACTOR
    
    // Get the actual Wi-Fi broadcast address
    private fun getBroadcastAddress(context: Context): InetAddress? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            if (dhcpInfo.ipAddress == 0) return null
            
            val ipAddress = dhcpInfo.ipAddress
            val netmask = dhcpInfo.netmask
            
            // Calculate broadcast address: IP OR (NOT netmask)
            val broadcast = (ipAddress and netmask) or (netmask.inv())
            
            val bytes = ByteArray(4)
            for (i in 0..3) {
                bytes[i] = ((broadcast shr (i * 8)) and 0xFF).toByte()
            }
            
            return InetAddress.getByAddress(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting broadcast address", e)
            return null
        }
    }

    fun initializeAudio() {
        if (_isInitialized.value) return
        
        viewModelScope.launch {
            try {
                // Initialize AudioRecord for microphone input
                audioRecord = AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    audioBufferSize
                )

                // Initialize AudioTrack for playback with proper AudioAttributes
                val trackBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AUDIO_FORMAT
                )
                
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                    
                val audioFormat = AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
                    
                audioTrack = AudioTrack(
                    audioAttributes,
                    audioFormat,
                    trackBufferSize,
                    AudioTrack.MODE_STREAM,
                    0
                )

                // Initialize UDP socket for broadcasting
                udpSocket = DatagramSocket()
                udpSocket?.broadcast = true
                udpSocket?.reuseAddress = true

                // Initialize receive socket - bind to all interfaces with reuse address
                receiveSocket = DatagramSocket(UDP_PORT)
                receiveSocket?.broadcast = true
                receiveSocket?.reuseAddress = true
                receiveSocket?.soTimeout = 100

                _isInitialized.value = true
                _isListening.value = true
                
                Log.d(TAG, "Audio system initialized successfully")
                Log.d(TAG, "Buffer size: $audioBufferSize, Min buffer: $minBufferSize")
                
                // Start listening for incoming audio
                startListening()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing audio", e)
                _isInitialized.value = false
            }
        }
    }

    fun toggleTransmit() {
        if (!_isInitialized.value) return
        
        if (_isTransmitting.value) {
            stopTransmitting()
        } else {
            startTransmitting()
        }
    }

    private fun startTransmitting() {
        viewModelScope.launch {
            try {
                _isTransmitting.value = true
                _isListening.value = false
                
                // Stop receiving while transmitting to avoid feedback
                stopListening()
                
                // Start recording and transmitting
                audioRecord?.startRecording()
                
                transmitJob = viewModelScope.launch(Dispatchers.IO) {
                    val buffer = ByteArray(audioBufferSize)
                    val context = _context.value
                    
                    while (_isTransmitting.value && isActive) {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        
                        if (bytesRead > 0 && context != null) {
                            sendAudioData(context, buffer, bytesRead)
                        }
                        
                        // Small delay to prevent overwhelming the network
                        delay(10)
                    }
                }
                
                Log.d(TAG, "Started transmitting")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting transmission", e)
                _isTransmitting.value = false
                _isListening.value = true
            }
        }
    }

    private fun stopTransmitting() {
        viewModelScope.launch {
            try {
                _isTransmitting.value = false
                
                transmitJob?.cancel()
                transmitJob = null
                
                audioRecord?.stop()
                
                // Resume listening
                _isListening.value = true
                startListening()
                
                Log.d(TAG, "Stopped transmitting")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping transmission", e)
            }
        }
    }

    private fun sendAudioData(context: Context, data: ByteArray, size: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val broadcastAddress = getBroadcastAddress(context)
                if (broadcastAddress == null) {
                    Log.w(TAG, "Could not get broadcast address, using 255.255.255.255")
                    // Fallback to general broadcast
                    val address = InetAddress.getByName("255.255.255.255")
                    val packet = DatagramPacket(data, size, address, UDP_PORT)
                    udpSocket?.send(packet)
                    Log.d(TAG, "Sent ${size} bytes to 255.255.255.255")
                } else {
                    Log.d(TAG, "Sending ${size} bytes to broadcast address: ${broadcastAddress.hostAddress}")
                    val packet = DatagramPacket(data, size, broadcastAddress, UDP_PORT)
                    udpSocket?.send(packet)
                    Log.d(TAG, "Successfully sent packet")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audio data", e)
            }
        }
    }

    private fun startListening() {
        if (receiveJob != null && receiveJob?.isActive == true) return
        
        receiveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(audioBufferSize)
                
                while (_isListening.value && !_isTransmitting.value && isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        receiveSocket?.receive(packet)
                        
                        if (packet.length > 0) {
                            Log.d(TAG, "Received ${packet.length} bytes from ${packet.address.hostAddress}, playing now")
                            playReceivedAudio(packet.data, packet.length)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Expected timeout, continue listening
                    } catch (e: Exception) {
                        if (_isListening.value) {
                            Log.e(TAG, "Error receiving audio", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in receive loop", e)
            }
        }
        
        Log.d(TAG, "Started listening for incoming audio on port $UDP_PORT")
    }

    private fun stopListening() {
        receiveJob?.cancel()
        receiveJob = null
    }

    private fun playReceivedAudio(data: ByteArray, size: Int) {
        viewModelScope.launch {
            try {
                audioTrack?.play()
                audioTrack?.write(data, 0, size)
                Log.d(TAG, "Playing ${size} bytes of audio data")
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio", e)
            }
        }
    }

    fun cleanup() {
        viewModelScope.launch {
            try {
                _isTransmitting.value = false
                _isListening.value = false
                
                transmitJob?.cancel()
                receiveJob?.cancel()
                
                audioRecord?.apply {
                    if (state == AudioRecord.STATE_INITIALIZED) {
                        stop()
                    }
                    release()
                }
                
                audioTrack?.apply {
                    stop()
                    release()
                }
                
                udpSocket?.close()
                receiveSocket?.close()
                
                audioRecord = null
                audioTrack = null
                udpSocket = null
                receiveSocket = null
                
                _isInitialized.value = false
                
                Log.d(TAG, "Cleanup completed")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
