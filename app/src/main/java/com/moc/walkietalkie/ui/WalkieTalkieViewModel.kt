package com.moc.walkietalkie.ui

import android.content.Context
import android.media.AudioFormat
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
import java.net.InetSocketAddress
import java.net.NetworkInterface

class WalkieTalkieViewModel(application: Context) : ViewModel() {

    companion object {
        private const val TAG = "WalkieTalkieVM"
        private const val SAMPLE_RATE = 8000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 8
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
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var transmitJob: Job? = null
    private var receiveJob: Job? = null

    private val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private val audioBufferSize = minBufferSize * BUFFER_SIZE_FACTOR

    init {
        Log.d(TAG, "ViewModel created. Min buffer: $minBufferSize, Using buffer: $audioBufferSize")
    }

    private fun getBroadcastAddress(context: Context): InetAddress? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            
            if (dhcpInfo.ipAddress == 0 || dhcpInfo.netmask == 0) {
                Log.w(TAG, "DHCP info not available, trying alternative method")
                return getBroadcastAddressFromNetworkInterface()
            }

            val ipAddress = dhcpInfo.ipAddress
            val netmask = dhcpInfo.netmask
            val broadcast = (ipAddress and netmask) or (netmask.inv())

            val bytes = ByteArray(4)
            for (i in 0..3) {
                bytes[i] = ((broadcast shr (i * 8)) and 0xFF).toByte()
            }

            val addr = InetAddress.getByAddress(bytes)
            val ipBytes = ByteArray(4) { ((ipAddress shr (it * 8)) and 0xFF).toByte() }
            Log.d(TAG, "IP: ${InetAddress.getByAddress(ipBytes).hostAddress}, Broadcast: ${addr.hostAddress}")
            return addr
        } catch (e: Exception) {
            Log.e(TAG, "Error getting broadcast address from DHCP", e)
            return getBroadcastAddressFromNetworkInterface()
        }
    }

    private fun getBroadcastAddressFromNetworkInterface(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.interfaceAddresses
                for (address in addresses) {
                    val broadcast = address.broadcast
                    if (broadcast != null && !broadcast.isLoopbackAddress) {
                        Log.d(TAG, "Found broadcast from ${networkInterface.name}: ${broadcast.hostAddress}")
                        return broadcast
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting broadcast from network interface", e)
        }
        
        Log.w(TAG, "Using fallback broadcast 255.255.255.255")
        return InetAddress.getByName("255.255.255.255")
    }

    fun initializeAudio() {
        if (_isInitialized.value) return

        viewModelScope.launch {
            try {
                audioRecord = AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    audioBufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    _isInitialized.value = false
                    return@launch
                }

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

                if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack failed to initialize")
                    _isInitialized.value = false
                    return@launch
                }

                udpSocket = DatagramSocket()
                udpSocket?.broadcast = true
                udpSocket?.reuseAddress = true

                receiveSocket = DatagramSocket()
                receiveSocket?.reuseAddress = true
                receiveSocket?.soTimeout = 100
                receiveSocket?.bind(InetSocketAddress(UDP_PORT))

                val wifiManager = _context.value?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WalkieTalkieLock")
                wifiLock?.acquire()
                
                multicastLock = wifiManager?.createMulticastLock("WalkieTalkieMulticastLock")
                multicastLock?.setReferenceCounted(true)
                multicastLock?.acquire()

                _isInitialized.value = true
                _isListening.value = true

                Log.d(TAG, "Audio initialized. Buffer: $audioBufferSize, Track: $trackBufferSize")
                Log.d(TAG, "WiFi lock: ${wifiLock?.isHeld}, Multicast: ${multicastLock?.isHeld}")

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
                stopListening()
                audioRecord?.startRecording()

                transmitJob = viewModelScope.launch(Dispatchers.IO) {
                    val buffer = ByteArray(audioBufferSize)
                    val context = _context.value

                    while (_isTransmitting.value && isActive) {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (bytesRead > 0 && context != null) {
                            sendAudioData(context, buffer, bytesRead)
                        }
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
                _isListening.value = true
                startListening()
                Log.d(TAG, "Stopped transmitting")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping transmission", e)
            }
        }
    }

    private fun sendAudioData(context: Context, data: ByteArray, size: Int) {
        try {
            val broadcastAddress = getBroadcastAddress(context)
            val packet = DatagramPacket(data, size, broadcastAddress, UDP_PORT)
            udpSocket?.send(packet)
            Log.d(TAG, "Sent $size bytes to ${broadcastAddress?.hostAddress}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio data", e)
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
                            Log.d(TAG, "Received ${packet.length} bytes from ${packet.address.hostAddress}")
                            playReceivedAudio(packet.data, packet.length)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Expected
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

        Log.d(TAG, "Started listening on port $UDP_PORT")
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
                Log.d(TAG, "Playing $size bytes")
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
                    if (state == AudioRecord.STATE_INITIALIZED) stop()
                    release()
                }

                audioTrack?.apply {
                    stop()
                    release()
                }

                udpSocket?.close()
                receiveSocket?.close()

                wifiLock?.release()
                multicastLock?.release()

                audioRecord = null
                audioTrack = null
                udpSocket = null
                receiveSocket = null
                wifiLock = null
                multicastLock = null

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
