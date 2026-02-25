package com.tw0b33rs.nativesensoraccess.streaming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Composable that renders a remote WebRTC video track using SurfaceViewRenderer.
 *
 * @param videoTrack The remote video track to render, or null if no track available
 * @param modifier Modifier for the composable
 */
@Composable
fun RemoteVideoView(
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier
) {
    var rendererReady by remember { mutableStateOf(false) }
    var currentTrack by remember { mutableStateOf<VideoTrack?>(null) }

    Box(
        modifier = modifier.background(Color.Black)
    ) {
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    try {
                        val eglBase = EglBase.create()
                        init(eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setMirror(false)
                        setEnableHardwareScaler(true)
                        rendererReady = true
                    } catch (e: Exception) {
                        // Renderer initialization may fail if EGL context is unavailable
                    }
                }
            },
            update = { renderer ->
                if (rendererReady && videoTrack != currentTrack) {
                    // Remove previous track's sink
                    currentTrack?.removeSink(renderer)

                    // Add new track's sink
                    videoTrack?.addSink(renderer)
                    currentTrack = videoTrack
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay when no video track
        if (videoTrack == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Waiting for remote video...",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    // Cleanup when composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            currentTrack = null
        }
    }
}
