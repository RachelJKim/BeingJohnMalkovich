package com.tw0b33rs.nativesensoraccess.streaming

import com.tw0b33rs.nativesensoraccess.logging.SensorLogger
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import java.nio.ByteBuffer

/**
 * Converts raw I420 camera frames from native layer into WebRTC VideoFrame
 * objects and feeds them to a WebRTC VideoSource for encoding and transmission.
 */
class VideoFrameEncoder(
    private val videoSource: VideoSource
) : NativeFrameCallback {

    private val log = SensorLogger.Logger("NativeSensor.Encoder")
    private var frameCount = 0L

    override fun onFrame(data: ByteArray, width: Int, height: Int, timestampNs: Long) {
        try {
            // I420 layout: Y plane = width * height, U plane = width/2 * height/2, V plane = same
            val ySize = width * height
            val uvWidth = width / 2
            val uvHeight = height / 2
            val uvSize = uvWidth * uvHeight

            if (data.size < ySize + uvSize * 2) {
                log.warn("Frame data too small: ${data.size} < ${ySize + uvSize * 2}")
                return
            }

            // Create I420 buffer from the raw data
            val yBuffer = ByteBuffer.allocateDirect(ySize)
            val uBuffer = ByteBuffer.allocateDirect(uvSize)
            val vBuffer = ByteBuffer.allocateDirect(uvSize)

            yBuffer.put(data, 0, ySize)
            yBuffer.rewind()

            uBuffer.put(data, ySize, uvSize)
            uBuffer.rewind()

            vBuffer.put(data, ySize + uvSize, uvSize)
            vBuffer.rewind()

            val i420Buffer = JavaI420Buffer.wrap(
                width, height,
                yBuffer, width,       // Y plane: stride = width
                uBuffer, uvWidth,     // U plane: stride = width/2
                vBuffer, uvWidth,     // V plane: stride = width/2
                null                  // No release callback needed for direct buffers
            )

            val videoFrame = VideoFrame(i420Buffer, 0, timestampNs)

            // Feed frame to WebRTC video source
            videoSource.capturerObserver.onFrameCaptured(videoFrame)

            videoFrame.release()

            frameCount++
            if (frameCount % 300 == 0L) {
                log.debug("Encoded $frameCount frames", mapOf(
                    "resolution" to "${width}x${height}"
                ))
            }
        } catch (e: Exception) {
            log.error("Error encoding frame", throwable = e)
        }
    }

    /**
     * Get total number of frames encoded.
     */
    fun getFrameCount(): Long = frameCount

    /**
     * Reset frame counter.
     */
    fun reset() {
        frameCount = 0
    }
}
