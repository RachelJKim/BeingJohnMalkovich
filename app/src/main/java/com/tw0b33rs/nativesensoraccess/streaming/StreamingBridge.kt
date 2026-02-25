package com.tw0b33rs.nativesensoraccess.streaming

import com.tw0b33rs.nativesensoraccess.logging.SensorLogger

/**
 * Callback interface for receiving raw camera frames from native layer.
 * Implemented in Java/Kotlin and called from C++ via JNI.
 */
interface NativeFrameCallback {
    /**
     * Called when a new frame is available from the native camera.
     * @param data I420 YUV frame data
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     * @param timestampNs Hardware timestamp in nanoseconds
     */
    fun onFrame(data: ByteArray, width: Int, height: Int, timestampNs: Long)
}

/**
 * JNI bridge to native encoder/frame capture layer.
 * Captures camera frames via AImageReader for WebRTC encoding.
 */
object StreamingBridge {

    private val log = SensorLogger.Logger("NativeSensor.Streaming")

    init {
        try {
            System.loadLibrary("nativesensor")
            log.info("Streaming native library ready")
        } catch (e: UnsatisfiedLinkError) {
            log.error("Failed to load native library for streaming", throwable = e)
        }
    }

    // Native method declarations
    private external fun nativeSetFrameCallback(callback: NativeFrameCallback?)
    private external fun nativeStartFrameCapture(cameraId: String, width: Int, height: Int): Boolean
    private external fun nativeStopFrameCapture()
    private external fun nativeIsCapturing(): Boolean
    private external fun nativeReleaseEncoder()

    /**
     * Register a callback to receive raw camera frames.
     * Must be called before startFrameCapture.
     */
    fun setFrameCallback(callback: NativeFrameCallback?) {
        log.info("Setting frame callback: ${if (callback != null) "registered" else "cleared"}")
        nativeSetFrameCallback(callback)
    }

    /**
     * Start capturing frames from a camera for encoding.
     * @param cameraId Camera ID to capture from
     * @param width Desired capture width
     * @param height Desired capture height
     * @return true if capture started successfully
     */
    fun startFrameCapture(cameraId: String, width: Int, height: Int): Boolean {
        log.info("Starting frame capture", mapOf(
            "cameraId" to cameraId,
            "resolution" to "${width}x${height}"
        ))
        return nativeStartFrameCapture(cameraId, width, height).also { success ->
            if (success) {
                log.info("Frame capture started: $cameraId")
            } else {
                log.error("Failed to start frame capture: $cameraId")
            }
        }
    }

    /**
     * Stop frame capture.
     */
    fun stopFrameCapture() {
        log.info("Stopping frame capture")
        nativeStopFrameCapture()
    }

    /**
     * Check if currently capturing frames.
     */
    fun isCapturing(): Boolean = nativeIsCapturing()

    /**
     * Release all encoder resources.
     */
    fun release() {
        log.info("Releasing encoder resources")
        nativeReleaseEncoder()
    }
}
