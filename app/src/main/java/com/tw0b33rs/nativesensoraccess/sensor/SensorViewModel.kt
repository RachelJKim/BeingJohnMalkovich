package com.tw0b33rs.nativesensoraccess.sensor

import android.content.Context
import android.net.wifi.WifiManager
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tw0b33rs.nativesensoraccess.logging.SensorLogExtensions.logPerformanceStats
import com.tw0b33rs.nativesensoraccess.logging.SensorLogger
import com.tw0b33rs.nativesensoraccess.streaming.StreamingState
import com.tw0b33rs.nativesensoraccess.streaming.StreamingStateListener
import com.tw0b33rs.nativesensoraccess.streaming.WebRTCManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.VideoTrack

/**
 * Navigation destinations for sensor clusters.
 * Using enum instead of sealed class for guaranteed exhaustiveness at compile time.
 */
enum class NavigationDestination(val title: String, val icon: String) {
    ImuSensors("IMU", "🧠"),
    PassthroughCameras("Passthrough", "👁"),
    Avatar("Avatar", "🤖"),
    EyeTrackingCameras("Eye Tracking", "👀"),
    Streaming("Stream", "📡");

    companion object {
        /** Primary navigation items shown in the sidebar */
        val primaryItems: List<NavigationDestination> = listOf(
            ImuSensors,
            PassthroughCameras,
            Avatar,
            EyeTrackingCameras,
            Streaming
        )

        /** Default destination when navigation state is unclear */
        val default: NavigationDestination = ImuSensors

        /** Safely resolve a destination, falling back to default if needed */
        @Suppress("unused")  // Part of public API for state restoration
        fun safeValueOf(name: String?): NavigationDestination {
            return entries.find { it.name == name } ?: default
        }
    }
}

/**
 * Camera cluster state for a specific cluster type.
 */
data class CameraClusterState(
    val cameras: List<CameraInfo> = emptyList(),
    val selectedCameraId: String? = null,
    val stats: CameraStats = CameraStats(0f, 0f, 0, 0),
    val isStreaming: Boolean = false
)

/**
 * UI State for sensor display.
 */
data class SensorUiState(
    // Navigation
    val currentDestination: NavigationDestination = NavigationDestination.ImuSensors,

    // Permissions
    val hasCameraPermission: Boolean = false,

    // IMU State
    val accelSample: ImuSample = ImuSample(0f, 0f, 0f, 0f),
    val gyroSample: ImuSample = ImuSample(0f, 0f, 0f, 0f),
    val stats: ImuStats = ImuStats(0f, 0f, 0f, 0f),
    val metadata: ImuMetadata = ImuMetadata(0, 0, 0, 0),
    val availableAccelerometers: List<SensorInfo> = emptyList(),
    val availableGyroscopes: List<SensorInfo> = emptyList(),
    val selectedAccelHandle: Int = -1,
    val selectedGyroHandle: Int = -1,
    val isImuRunning: Boolean = false,

    // Camera States per cluster
    val passthroughCluster: CameraClusterState = CameraClusterState(),
    val trackingCluster: CameraClusterState = CameraClusterState(),
    val eyeTrackingCluster: CameraClusterState = CameraClusterState(),

    // Streaming state
    val streamingState: StreamingState = StreamingState.DISCONNECTED,
    val remoteIp: String = "",
    val remoteVideoTrack: VideoTrack? = null,
    val deviceIp: String = "",
    val streamingError: String? = null
) {
    /**
     * Get cluster state for a destination.
     */
    @Suppress("unused")  // Part of public API for external consumers
    fun getClusterState(destination: NavigationDestination?): CameraClusterState? = when (destination) {
        NavigationDestination.PassthroughCameras -> passthroughCluster
        NavigationDestination.Avatar -> trackingCluster
        NavigationDestination.EyeTrackingCameras -> eyeTrackingCluster
        NavigationDestination.ImuSensors, NavigationDestination.Streaming, null -> null
    }
}

/**
 * ViewModel for IMU sensor data and camera streams.
 * Manages sensor lifecycle and UI state updates.
 */
class SensorViewModel : ViewModel() {

    private val log = SensorLogger.general
    private val camLog = SensorLogger.camera
    private val perfLog = SensorLogger.perf

    private val _uiState = MutableStateFlow(SensorUiState())
    val uiState: StateFlow<SensorUiState> = _uiState.asStateFlow()

    private var isPolling = false
    private var perfLogCounter = 0
    private var activeCameraSurface: Surface? = null

    // ==========================================================================
    // Navigation
    // ==========================================================================

    /**
     * Navigate to a sensor cluster destination.
     */
    fun navigateTo(destination: NavigationDestination) {
        log.info("Navigating to ${destination.title}")

        // Stop current camera preview when leaving camera views
        val currentDest = _uiState.value.currentDestination
        if (currentDest != NavigationDestination.ImuSensors &&
            currentDest != NavigationDestination.Streaming &&
            (destination == NavigationDestination.ImuSensors ||
             destination == NavigationDestination.Streaming)) {
            stopCameraPreview()
        }

        _uiState.value = _uiState.value.copy(currentDestination = destination)
    }

    // ==========================================================================
    // Permissions
    // ==========================================================================

    /**
     * Update camera permission state.
     */
    fun setCameraPermission(granted: Boolean) {
        log.info("Camera permission ${if (granted) "granted" else "denied"}")
        _uiState.value = _uiState.value.copy(hasCameraPermission = granted)

        if (granted) {
            enumerateCameras()
        }
    }

    // ==========================================================================
    // IMU Sensors
    // ==========================================================================

    /**
     * Initialize sensors and start data collection.
     * Runs native initialization on IO dispatcher to avoid blocking UI.
     */
    fun startSensors() {
        viewModelScope.launch(Dispatchers.IO) {
            log.info("Starting sensor system (async)")

            NativeSensorBridge.init()
            val accelerometers = NativeSensorBridge.getAccelerometers()
            val gyroscopes = NativeSensorBridge.getGyroscopes()

            withContext(Dispatchers.Main) {
                log.info("Sensor enumeration complete", mapOf(
                    "accelerometers" to accelerometers.size,
                    "gyroscopes" to gyroscopes.size
                ))

                _uiState.value = _uiState.value.copy(
                    availableAccelerometers = accelerometers,
                    availableGyroscopes = gyroscopes,
                    selectedAccelHandle = accelerometers.firstOrNull()?.handle ?: -1,
                    selectedGyroHandle = gyroscopes.firstOrNull()?.handle ?: -1,
                    isImuRunning = true
                )

                // Start polling for UI updates
                startPolling()
            }
        }
    }

    /**
     * Stop sensors and release resources.
     */
    fun stopSensors() {
        log.info("Stopping sensor system")
        isPolling = false
        viewModelScope.launch(Dispatchers.IO) {
            NativeSensorBridge.stop()
            stopCameraPreview()
        }
        _uiState.value = _uiState.value.copy(isImuRunning = false)
    }

    /**
     * Switch to different sensors.
     */
    fun switchSensors(accelHandle: Int, gyroHandle: Int) {
        log.info("Switching sensors", mapOf(
            "accelHandle" to accelHandle,
            "gyroHandle" to gyroHandle
        ))
        viewModelScope.launch(Dispatchers.IO) {
            NativeSensorBridge.switchSensors(accelHandle, gyroHandle)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    selectedAccelHandle = accelHandle,
                    selectedGyroHandle = gyroHandle
                )
            }
            delay(SENSOR_SWITCH_DELAY_MS)
            updateMetadata()
        }
    }

    /**
     * Select a specific accelerometer.
     */
    fun selectAccelerometer(handle: Int) {
        val sensor = _uiState.value.availableAccelerometers.find { it.handle == handle }
        SensorLogger.imu.info("Accelerometer selected", mapOf(
            "handle" to handle,
            "name" to (sensor?.name ?: "Unknown"),
            "maxFrequency" to "${sensor?.maxFrequencyHz?.toInt() ?: 0} Hz"
        ))
        switchSensors(handle, _uiState.value.selectedGyroHandle)
    }

    /**
     * Select a specific gyroscope.
     */
    fun selectGyroscope(handle: Int) {
        val sensor = _uiState.value.availableGyroscopes.find { it.handle == handle }
        SensorLogger.imu.info("Gyroscope selected", mapOf(
            "handle" to handle,
            "name" to (sensor?.name ?: "Unknown"),
            "maxFrequency" to "${sensor?.maxFrequencyHz?.toInt() ?: 0} Hz"
        ))
        switchSensors(_uiState.value.selectedAccelHandle, handle)
    }

    // ==========================================================================
    // Camera
    // ==========================================================================

    /**
     * Enumerate all cameras and organize by cluster.
     * Runs on IO dispatcher to avoid blocking UI.
     */
    fun enumerateCameras() {
        if (!_uiState.value.hasCameraPermission) {
            camLog.warn("Cannot enumerate cameras: no permission")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            camLog.info("Enumerating cameras (async)")
            val cameras = CameraBridge.enumerateCameras()
            val passthrough = cameras.filter { it.clusterType == CameraClusterType.PASSTHROUGH }
            val avatar = cameras.filter { it.clusterType == CameraClusterType.AVATAR }
            val eyeTracking = cameras.filter { it.clusterType == CameraClusterType.EYE_TRACKING }
            // Include unknown in tracking as fallback
            val unknown = cameras.filter { it.clusterType == CameraClusterType.UNKNOWN }

            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    passthroughCluster = CameraClusterState(
                        cameras = passthrough,
                        selectedCameraId = passthrough.firstOrNull()?.id
                    ),
                    trackingCluster = CameraClusterState(
                        cameras = avatar + unknown,
                        selectedCameraId = (avatar + unknown).firstOrNull()?.id
                    ),
                    eyeTrackingCluster = CameraClusterState(
                        cameras = eyeTracking,
                        selectedCameraId = eyeTracking.firstOrNull()?.id
                    )
                )

                camLog.info("Camera clusters populated", mapOf(
                    "passthrough" to passthrough.size,
                    "avatar" to (avatar.size + unknown.size),
                    "eyeTracking" to eyeTracking.size
                ))
            }
        }
    }

    /**
     * Select camera within a cluster.
     * Updates the selected camera ID in state; the Compose UI will handle
     * stopping/starting the preview via recomposition.
     */
    fun selectCamera(clusterType: CameraClusterType, cameraId: String) {
        camLog.info("Camera selected", mapOf("cluster" to clusterType, "cameraId" to cameraId))

        _uiState.value = when (clusterType) {
            CameraClusterType.PASSTHROUGH -> _uiState.value.copy(
                passthroughCluster = _uiState.value.passthroughCluster.copy(selectedCameraId = cameraId)
            )
            CameraClusterType.AVATAR, CameraClusterType.UNKNOWN -> _uiState.value.copy(
                trackingCluster = _uiState.value.trackingCluster.copy(selectedCameraId = cameraId)
            )
            CameraClusterType.EYE_TRACKING -> _uiState.value.copy(
                eyeTrackingCluster = _uiState.value.eyeTrackingCluster.copy(selectedCameraId = cameraId)
            )
            CameraClusterType.DEPTH -> _uiState.value // Depth cameras not yet supported
        }
    }

    /**
     * Start camera preview on a surface.
     * Runs camera opening on IO dispatcher to prevent blocking UI.
     */
    fun startCameraPreview(cameraId: String, surface: Surface) {
        if (!_uiState.value.hasCameraPermission) {
            camLog.warn("Cannot start preview: no camera permission")
            return
        }

        camLog.info("Starting camera preview (async)", mapOf("cameraId" to cameraId))
        activeCameraSurface = surface

        viewModelScope.launch(Dispatchers.IO) {
            val success = CameraBridge.startPreview(cameraId, surface)
            if (success) {
                withContext(Dispatchers.Main) {
                    updateCameraStreamingState(true)
                }
            }
        }
    }

    /**
     * Stop camera preview.
     * @param cameraId Specific camera to stop, or null to stop all cameras
     */
    fun stopCameraPreview(cameraId: String? = null) {
        camLog.info("Stopping camera preview", mapOf("cameraId" to (cameraId ?: "all")))
        viewModelScope.launch(Dispatchers.IO) {
            if (cameraId != null) {
                // Stop specific camera
                if (CameraBridge.isStreaming(cameraId)) {
                    CameraBridge.stopPreview(cameraId)
                }
            } else {
                // Stop all cameras
                CameraBridge.stopPreview()
                withContext(Dispatchers.Main) {
                    updateCameraStreamingState(false)
                }
                activeCameraSurface = null
            }
        }
    }

    private fun updateCameraStreamingState(isStreaming: Boolean) {
        val currentDest = _uiState.value.currentDestination
        _uiState.value = when (currentDest) {
            NavigationDestination.PassthroughCameras -> _uiState.value.copy(
                passthroughCluster = _uiState.value.passthroughCluster.copy(isStreaming = isStreaming)
            )
            NavigationDestination.Avatar -> _uiState.value.copy(
                trackingCluster = _uiState.value.trackingCluster.copy(isStreaming = isStreaming)
            )
            NavigationDestination.EyeTrackingCameras -> _uiState.value.copy(
                eyeTrackingCluster = _uiState.value.eyeTrackingCluster.copy(isStreaming = isStreaming)
            )
            NavigationDestination.ImuSensors,
            NavigationDestination.Streaming -> _uiState.value
        }
    }

    // ==========================================================================
    // Streaming
    // ==========================================================================

    private var webRTCManager: WebRTCManager? = null
    private val streamLog = SensorLogger.Logger("NativeSensor.Stream")

    /**
     * Initialize WebRTC manager. Must be called with application context.
     */
    @Suppress("DEPRECATION")
    fun initializeStreaming(context: Context) {
        if (webRTCManager != null) return

        // Get device IP for display
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                val ip = "${ipInt and 0xFF}.${(ipInt shr 8) and 0xFF}.${(ipInt shr 16) and 0xFF}.${(ipInt shr 24) and 0xFF}"
                _uiState.value = _uiState.value.copy(deviceIp = ip)
                streamLog.info("Device IP: $ip")
            } else {
                // Try network interfaces as fallback
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (intf in interfaces) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            _uiState.value = _uiState.value.copy(deviceIp = addr.hostAddress ?: "")
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            streamLog.warn("Could not get device IP", throwable = e)
        }

        webRTCManager = WebRTCManager(context.applicationContext, object : StreamingStateListener {
            override fun onStateChanged(state: StreamingState) {
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        streamingState = state,
                        streamingError = if (state == StreamingState.ERROR) _uiState.value.streamingError else null
                    )
                }
            }

            override fun onRemoteVideoTrackReceived(videoTrack: VideoTrack) {
                streamLog.info("Remote video track received")
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(remoteVideoTrack = videoTrack)
                }
            }

            override fun onRemoteVideoTrackRemoved() {
                streamLog.info("Remote video track removed")
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(remoteVideoTrack = null)
                }
            }

            override fun onError(message: String) {
                streamLog.error("Streaming error: $message")
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        streamingError = message,
                        streamingState = StreamingState.ERROR
                    )
                }
            }
        })

        try {
            webRTCManager?.initialize()
            streamLog.info("WebRTC manager initialized")
        } catch (e: Exception) {
            streamLog.error("Failed to initialize WebRTC", throwable = e)
            _uiState.value = _uiState.value.copy(
                streamingError = "WebRTC init failed: ${e.message}"
            )
            webRTCManager = null
        }
    }

    /**
     * Start streaming as sender (host).
     * Uses the first passthrough camera by default.
     */
    fun startStreamingSender() {
        if (webRTCManager == null) {
            streamLog.error("WebRTC not initialized — cannot start sender")
            _uiState.value = _uiState.value.copy(
                streamingState = StreamingState.ERROR
            )
            return
        }

        val passthrough = _uiState.value.passthroughCluster.cameras.firstOrNull()
        if (passthrough == null) {
            streamLog.warn("No passthrough camera available for streaming — trying to enumerate")
            // Try to enumerate cameras first
            if (_uiState.value.hasCameraPermission) {
                viewModelScope.launch(Dispatchers.IO) {
                    val cameras = CameraBridge.enumerateCameras()
                    val ptCameras = cameras.filter { it.clusterType == CameraClusterType.PASSTHROUGH }
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            passthroughCluster = _uiState.value.passthroughCluster.copy(
                                cameras = ptCameras,
                                selectedCameraId = ptCameras.firstOrNull()?.id
                            )
                        )
                        // Retry with freshly enumerated cameras
                        val cam = ptCameras.firstOrNull()
                        if (cam != null) {
                            streamLog.info("Found camera after enumeration, starting sender", mapOf(
                                "cameraId" to cam.id,
                                "resolution" to "${cam.width}x${cam.height}"
                            ))
                            webRTCManager?.startAsSender(cam.id, cam.width, cam.height)
                        } else {
                            streamLog.error("No passthrough cameras found on device")
                            _uiState.value = _uiState.value.copy(
                                streamingState = StreamingState.ERROR
                            )
                        }
                    }
                }
            } else {
                streamLog.error("No camera permission — cannot start sender")
                _uiState.value = _uiState.value.copy(
                    streamingState = StreamingState.ERROR
                )
            }
            return
        }

        streamLog.info("Starting as sender", mapOf(
            "cameraId" to passthrough.id,
            "resolution" to "${passthrough.width}x${passthrough.height}"
        ))

        webRTCManager?.startAsSender(passthrough.id, passthrough.width, passthrough.height)
    }

    /**
     * Start streaming as receiver, connecting to a sender.
     */
    fun startStreamingReceiver(remoteIp: String) {
        streamLog.info("Starting as receiver, connecting to $remoteIp")
        webRTCManager?.startAsReceiver(remoteIp)
    }

    /**
     * Stop the streaming session.
     */
    fun stopStreaming() {
        streamLog.info("Stopping streaming")
        webRTCManager?.stop()
        _uiState.value = _uiState.value.copy(
            streamingState = StreamingState.DISCONNECTED,
            remoteVideoTrack = null
        )
    }

    /**
     * Update remote IP address.
     */
    fun updateRemoteIp(ip: String) {
        _uiState.value = _uiState.value.copy(remoteIp = ip)
    }

    // ==========================================================================
    // Polling & Updates
    // ==========================================================================

    private fun startPolling() {
        if (isPolling) return
        isPolling = true
        perfLogCounter = 0

        viewModelScope.launch(Dispatchers.Default) {
            while (isActive && isPolling) {
                val sensorData = fetchSensorData()

                // Update UI state on Main thread
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        accelSample = sensorData.accel,
                        gyroSample = sensorData.gyro,
                        stats = sensorData.stats,
                        metadata = sensorData.metadata,
                        isImuRunning = sensorData.isRunning
                    )
                }

                perfLogCounter++
                if (perfLogCounter >= PERF_LOG_INTERVAL) {
                    perfLogCounter = 0
                    perfLog.logPerformanceStats("Accelerometer", sensorData.stats.accelFrequencyHz, sensorData.stats.accelLatencyMs)
                    perfLog.logPerformanceStats("Gyroscope", sensorData.stats.gyroFrequencyHz, sensorData.stats.gyroLatencyMs)
                }

                updateCameraStats()
                delay(UI_UPDATE_INTERVAL_MS)
            }
        }
    }

    private data class SensorDataSnapshot(
        val accel: ImuSample,
        val gyro: ImuSample,
        val stats: ImuStats,
        val metadata: ImuMetadata,
        val isRunning: Boolean
    )

    private fun fetchSensorData(): SensorDataSnapshot {
        return SensorDataSnapshot(
            accel = NativeSensorBridge.getAccelData(),
            gyro = NativeSensorBridge.getGyroData(),
            stats = NativeSensorBridge.getStats(),
            metadata = NativeSensorBridge.getMetadata(),
            isRunning = NativeSensorBridge.isRunning()
        )
    }

    private suspend fun updateCameraStats() {
        if (!CameraBridge.isStreaming()) return

        val stats = CameraBridge.getStats()
        val currentDest = _uiState.value.currentDestination

        withContext(Dispatchers.Main) {
            _uiState.value = when (currentDest) {
                NavigationDestination.PassthroughCameras -> _uiState.value.copy(
                    passthroughCluster = _uiState.value.passthroughCluster.copy(stats = stats)
                )
                NavigationDestination.Avatar -> _uiState.value.copy(
                    trackingCluster = _uiState.value.trackingCluster.copy(stats = stats)
                )
                NavigationDestination.EyeTrackingCameras -> _uiState.value.copy(
                    eyeTrackingCluster = _uiState.value.eyeTrackingCluster.copy(stats = stats)
                )
                NavigationDestination.ImuSensors,
                NavigationDestination.Streaming -> _uiState.value
            }
        }
    }

    private suspend fun updateMetadata() {
        val metadata = withContext(Dispatchers.IO) {
            NativeSensorBridge.getMetadata()
        }
        _uiState.value = _uiState.value.copy(metadata = metadata)
    }

    override fun onCleared() {
        super.onCleared()
        log.info("ViewModel cleared, stopping sensors and streaming")
        stopSensors()
        webRTCManager?.release()
        webRTCManager = null
    }

    companion object {
        private const val UI_UPDATE_INTERVAL_MS = 100L
        private const val PERF_LOG_INTERVAL = 100
        private const val SENSOR_SWITCH_DELAY_MS = 100L
    }
}
