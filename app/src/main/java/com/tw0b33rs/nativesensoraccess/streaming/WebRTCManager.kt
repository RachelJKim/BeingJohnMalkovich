package com.tw0b33rs.nativesensoraccess.streaming

import android.content.Context
import com.tw0b33rs.nativesensoraccess.logging.SensorLogger
import org.webrtc.*
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnection.IceGatheringState

/**
 * Connection state for the streaming session.
 */
enum class StreamingState {
    DISCONNECTED,
    WAITING_FOR_PEER,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Role of this device in the streaming session.
 */
enum class StreamingRole {
    SENDER,
    RECEIVER
}

/**
 * Listener for streaming state changes and remote video.
 */
interface StreamingStateListener {
    fun onStateChanged(state: StreamingState)
    fun onRemoteVideoTrackReceived(videoTrack: VideoTrack)
    fun onRemoteVideoTrackRemoved()
    fun onError(message: String)
}

/**
 * Manages WebRTC peer connections for camera streaming between devices.
 *
 * Handles:
 * - PeerConnectionFactory initialization
 * - SDP offer/answer creation and exchange
 * - ICE candidate handling
 * - Video track management (sending and receiving)
 * - Signaling via TCP socket
 */
class WebRTCManager(
    private val context: Context,
    private val stateListener: StreamingStateListener
) : SignalingListener {

    private val log = SensorLogger.Logger("NativeSensor.WebRTC")

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var frameEncoder: VideoFrameEncoder? = null
    private var signalingServer: SignalingServer? = null

    private var role: StreamingRole = StreamingRole.SENDER
    private var state: StreamingState = StreamingState.DISCONNECTED

    // Pending ICE candidates received before remote description is set
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    /**
     * Initialize the WebRTC peer connection factory.
     * Must be called before any other operations.
     */
    fun initialize() {
        log.info("Initializing WebRTC")

        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext,
            true,  // enableIntelVp8Encoder
            true   // enableH264HighProfile
        )

        val decoderFactory = DefaultVideoDecoderFactory(
            EglBase.create().eglBaseContext
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        log.info("WebRTC initialized successfully")
    }

    /**
     * Start as sender - host a signaling server and wait for a receiver to connect.
     * @param cameraId Camera ID to stream
     * @param width Capture width
     * @param height Capture height
     */
    fun startAsSender(cameraId: String, width: Int, height: Int) {
        role = StreamingRole.SENDER
        updateState(StreamingState.WAITING_FOR_PEER)

        // Create video source and track
        videoSource = peerConnectionFactory?.createVideoSource(false)
        localVideoTrack = peerConnectionFactory?.createVideoTrack("camera_track", videoSource)

        // Create frame encoder that feeds frames into the video source
        frameEncoder = videoSource?.let { VideoFrameEncoder(it) }

        // Register the frame encoder as the native frame callback
        StreamingBridge.setFrameCallback(frameEncoder)

        // Start native frame capture
        StreamingBridge.startFrameCapture(cameraId, width, height)

        // Start signaling server
        signalingServer = SignalingServer(this)
        signalingServer?.startServer()

        log.info("Started as sender", mapOf(
            "cameraId" to cameraId,
            "resolution" to "${width}x${height}"
        ))
    }

    /**
     * Start as receiver - connect to a sender's signaling server.
     * @param remoteIp IP address of the sending device
     */
    fun startAsReceiver(remoteIp: String) {
        role = StreamingRole.RECEIVER
        updateState(StreamingState.CONNECTING)

        // Connect to signaling server — the offer/answer exchange
        // happens in onPeerConnected() after TCP is established
        signalingServer = SignalingServer(this)
        signalingServer?.connectToServer(remoteIp)

        log.info("Started as receiver, connecting to $remoteIp")
    }

    /**
     * Stop the streaming session and release all resources.
     */
    fun stop() {
        log.info("Stopping WebRTC session")

        // Stop native frame capture
        StreamingBridge.setFrameCallback(null)
        StreamingBridge.stopFrameCapture()

        // Stop signaling
        signalingServer?.stop()
        signalingServer = null

        // Close peer connection
        peerConnection?.close()
        peerConnection = null

        // Release video resources
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        frameEncoder = null

        pendingIceCandidates.clear()
        remoteDescriptionSet = false

        updateState(StreamingState.DISCONNECTED)
    }

    /**
     * Release the factory. Call when done with WebRTC entirely.
     */
    fun release() {
        stop()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        StreamingBridge.release()
        log.info("WebRTC released")
    }

    /**
     * Get current streaming state.
     */
    fun getState(): StreamingState = state

    /**
     * Get current role.
     */
    fun getRole(): StreamingRole = role

    // =========================================================================
    // Signaling Listener
    // =========================================================================

    override fun onPeerConnected() {
        log.info("Signaling TCP connection established (role: $role)")
        updateState(StreamingState.CONNECTING)

        if (role == StreamingRole.SENDER) {
            // Sender creates peer connection and sends the offer
            createPeerConnection()

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }

            peerConnection?.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    log.info("Offer created by sender")
                    peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                    signalingServer?.send(SignalingMessage(
                        type = SignalingMessageType.OFFER,
                        sdp = sdp.description
                    ))
                }

                override fun onCreateFailure(error: String) {
                    log.error("Failed to create offer: $error")
                    stateListener.onError("Failed to create offer: $error")
                }
            }, constraints)
        }
        // Receiver just waits for the offer — handled in onOfferReceived()
    }

    override fun onOfferReceived(sdp: String) {
        log.info("Received offer from peer")

        if (peerConnection == null) {
            createPeerConnection()
        }

        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                log.info("Remote offer set")
                remoteDescriptionSet = true
                drainPendingIceCandidates()

                // Create and send answer
                peerConnection?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        log.info("Answer created")
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                        signalingServer?.send(SignalingMessage(
                            type = SignalingMessageType.ANSWER,
                            sdp = sdp.description
                        ))
                    }

                    override fun onCreateFailure(error: String) {
                        log.error("Failed to create answer: $error")
                        stateListener.onError("Failed to create answer: $error")
                    }
                }, MediaConstraints())
            }

            override fun onSetFailure(error: String) {
                log.error("Failed to set remote offer: $error")
            }
        }, sessionDescription)
    }

    override fun onAnswerReceived(sdp: String) {
        log.info("Received answer from peer")
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                log.info("Remote answer set")
                remoteDescriptionSet = true
                drainPendingIceCandidates()
            }

            override fun onSetFailure(error: String) {
                log.error("Failed to set remote answer: $error")
            }
        }, sessionDescription)
    }

    override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)

        if (remoteDescriptionSet) {
            peerConnection?.addIceCandidate(iceCandidate)
        } else {
            pendingIceCandidates.add(iceCandidate)
        }
    }

    override fun onPeerDisconnected() {
        log.info("Peer disconnected")
        updateState(StreamingState.DISCONNECTED)
        stateListener.onRemoteVideoTrackRemoved()
    }

    override fun onError(message: String) {
        log.error("Signaling error: $message")
        updateState(StreamingState.ERROR)
        stateListener.onError(message)
    }

    // =========================================================================
    // Private
    // =========================================================================

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    log.debug("Signaling state: $state")
                }

                override fun onIceConnectionChange(state: IceConnectionState) {
                    log.info("ICE connection state: $state")
                    when (state) {
                        IceConnectionState.CONNECTED -> updateState(StreamingState.CONNECTED)
                        IceConnectionState.DISCONNECTED,
                        IceConnectionState.FAILED,
                        IceConnectionState.CLOSED -> updateState(StreamingState.DISCONNECTED)
                        else -> {}
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    log.debug("ICE receiving: $receiving")
                }

                override fun onIceGatheringChange(state: IceGatheringState) {
                    log.debug("ICE gathering state: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate) {
                    log.debug("Local ICE candidate generated")
                    signalingServer?.send(SignalingMessage(
                        type = SignalingMessageType.ICE_CANDIDATE,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp
                    ))
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                    log.debug("ICE candidates removed")
                }

                override fun onAddStream(stream: MediaStream) {
                    log.info("Remote stream added: ${stream.videoTracks.size} video tracks")
                    if (stream.videoTracks.isNotEmpty()) {
                        stateListener.onRemoteVideoTrackReceived(stream.videoTracks[0])
                    }
                }

                override fun onRemoveStream(stream: MediaStream) {
                    log.info("Remote stream removed")
                    stateListener.onRemoteVideoTrackRemoved()
                }

                override fun onDataChannel(dataChannel: DataChannel) {
                    log.debug("Data channel received")
                }

                override fun onRenegotiationNeeded() {
                    log.debug("Renegotiation needed")
                }

                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    log.info("Remote track added")
                    val track = receiver.track()
                    if (track is VideoTrack) {
                        stateListener.onRemoteVideoTrackReceived(track)
                    }
                }
            }
        )

        // Add local video track if we are the sender
        if (role == StreamingRole.SENDER && localVideoTrack != null) {
            peerConnection?.addTrack(localVideoTrack)
            log.info("Local video track added to peer connection")
        }

        log.info("Peer connection created")
    }

    private fun drainPendingIceCandidates() {
        for (candidate in pendingIceCandidates) {
            peerConnection?.addIceCandidate(candidate)
        }
        if (pendingIceCandidates.isNotEmpty()) {
            log.debug("Drained ${pendingIceCandidates.size} pending ICE candidates")
        }
        pendingIceCandidates.clear()
    }

    private fun updateState(newState: StreamingState) {
        if (state != newState) {
            log.info("State: $state -> $newState")
            state = newState
            stateListener.onStateChanged(newState)
        }
    }
}

/**
 * Adapter for SdpObserver with default no-op implementations.
 */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
