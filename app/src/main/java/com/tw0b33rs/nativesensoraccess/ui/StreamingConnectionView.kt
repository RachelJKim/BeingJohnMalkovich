@file:Suppress("ComposeUnstableReceiver", "ComposeModifierMissing")

package com.tw0b33rs.nativesensoraccess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tw0b33rs.nativesensoraccess.streaming.StreamingState
import org.webrtc.VideoTrack
import com.tw0b33rs.nativesensoraccess.streaming.RemoteVideoView

/**
 * View mode for the camera preview area.
 */
enum class CameraViewMode {
    LOCAL,
    REMOTE
}

/**
 * Main streaming view that includes connection controls and video display.
 */
@Composable
fun StreamingContent(
    streamingState: StreamingState,
    remoteVideoTrack: VideoTrack?,
    remoteIp: String,
    onRemoteIpChanged: (String) -> Unit,
    onStartSender: () -> Unit,
    onStartReceiver: (String) -> Unit,
    onStop: () -> Unit,
    // Local camera passthrough
    localCameraId: String?,
    onLocalSurfaceReady: (String, android.view.Surface) -> Unit,
    onLocalSurfaceDestroyed: (String) -> Unit,
    deviceIp: String = "",
    streamingError: String? = null,
    cameraCount: Int = 0,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Camera Streaming",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Device IP display
        if (deviceIp.isNotBlank()) {
            Text(
                text = "This device's IP: $deviceIp",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Connection status
        StreamingStatusIndicator(state = streamingState)

        // Error message
        if (streamingError != null) {
            Text(
                text = streamingError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Camera count debug info
        if (cameraCount == 0) {
            Text(
                text = "⚠ No passthrough cameras detected — grant camera permission first",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (streamingState) {
            StreamingState.DISCONNECTED,
            StreamingState.ERROR -> {
                // Connection controls
                StreamingConnectionControls(
                    remoteIp = remoteIp,
                    onRemoteIpChanged = onRemoteIpChanged,
                    onStartSender = onStartSender,
                    onStartReceiver = onStartReceiver
                )
            }

            StreamingState.WAITING_FOR_PEER -> {
                // Waiting for peer
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Waiting for receiver to connect...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (deviceIp.isNotBlank()) {
                            Text(
                                text = "Tell the receiver to enter: $deviceIp",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = "Listening on port 8080",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = onStop) {
                            Text("Cancel")
                        }
                    }
                }
            }

            StreamingState.CONNECTING -> {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Establishing connection...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = onStop) {
                            Text("Cancel")
                        }
                    }
                }
            }

            StreamingState.CONNECTED -> {
                // Connected - show toggle and video
                ConnectedStreamingView(
                    remoteVideoTrack = remoteVideoTrack,
                    localCameraId = localCameraId,
                    onLocalSurfaceReady = onLocalSurfaceReady,
                    onLocalSurfaceDestroyed = onLocalSurfaceDestroyed,
                    onDisconnect = onStop,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Connection controls for entering IP and choosing sender/receiver role.
 */
@Composable
fun StreamingConnectionControls(
    remoteIp: String,
    onRemoteIpChanged: (String) -> Unit,
    onStartSender: () -> Unit,
    onStartReceiver: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Stream Setup",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Sender button
            Button(
                onClick = onStartSender,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start as Sender (Host)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Stream this device's passthrough camera to another device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Receiver section
            Text(
                text = "Or connect to a sender:",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = remoteIp,
                onValueChange = onRemoteIpChanged,
                label = { Text("Sender IP Address") },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { onStartReceiver(remoteIp) },
                enabled = remoteIp.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect as Receiver")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "View the remote device's passthrough camera on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * View shown when connected - includes toggle between local and remote video.
 */
@Composable
fun ConnectedStreamingView(
    remoteVideoTrack: VideoTrack?,
    localCameraId: String?,
    onLocalSurfaceReady: (String, android.view.Surface) -> Unit,
    onLocalSurfaceDestroyed: (String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf(CameraViewMode.REMOTE) }

    Column(modifier = modifier) {
        // Toggle button row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // View mode toggle
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = viewMode == CameraViewMode.LOCAL,
                    onClick = { viewMode = CameraViewMode.LOCAL },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text("Local") }
                )
                SegmentedButton(
                    selected = viewMode == CameraViewMode.REMOTE,
                    onClick = { viewMode = CameraViewMode.REMOTE },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text("Remote") }
                )
            }

            OutlinedButton(
                onClick = onDisconnect,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Disconnect")
            }
        }

        // Video display area
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (viewMode) {
                CameraViewMode.LOCAL -> {
                    CameraPreviewSurface(
                        cameraId = localCameraId,
                        onSurfaceReady = { surface ->
                            localCameraId?.let { id -> onLocalSurfaceReady(id, surface) }
                        },
                        onSurfaceDestroyed = {
                            localCameraId?.let { id -> onLocalSurfaceDestroyed(id) }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                CameraViewMode.REMOTE -> {
                    RemoteVideoView(
                        videoTrack = remoteVideoTrack,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Status line
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Viewing: ${if (viewMode == CameraViewMode.LOCAL) "Local Camera" else "Remote Stream"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Status indicator for the streaming connection state.
 */
@Composable
fun StreamingStatusIndicator(
    state: StreamingState,
    modifier: Modifier = Modifier
) {
    val (statusText, statusColor) = when (state) {
        StreamingState.DISCONNECTED -> "Disconnected" to MaterialTheme.colorScheme.onSurfaceVariant
        StreamingState.WAITING_FOR_PEER -> "Waiting for peer..." to MaterialTheme.colorScheme.tertiary
        StreamingState.CONNECTING -> "Connecting..." to MaterialTheme.colorScheme.tertiary
        StreamingState.CONNECTED -> "Connected" to MaterialTheme.colorScheme.primary
        StreamingState.ERROR -> "Error" to MaterialTheme.colorScheme.error
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor
        )
    }
}
