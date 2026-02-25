package com.tw0b33rs.nativesensoraccess.streaming

import com.google.gson.Gson
import com.tw0b33rs.nativesensoraccess.logging.SensorLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Signaling message types exchanged between peers.
 */
enum class SignalingMessageType {
    OFFER,
    ANSWER,
    ICE_CANDIDATE,
    BYE
}

/**
 * Signaling message wrapper for JSON serialization.
 */
data class SignalingMessage(
    val type: SignalingMessageType,
    val sdp: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val candidate: String? = null
)

/**
 * Listener for incoming signaling messages.
 */
interface SignalingListener {
    fun onPeerConnected()
    fun onOfferReceived(sdp: String)
    fun onAnswerReceived(sdp: String)
    fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String)
    fun onPeerDisconnected()
    fun onError(message: String)
}

/**
 * Simple TCP-based signaling server/client for WebRTC SDP and ICE candidate exchange.
 * 
 * One device acts as the server (waits for connection), the other connects as client.
 * Messages are exchanged as newline-delimited JSON over TCP.
 */
class SignalingServer(
    private val listener: SignalingListener,
    private val port: Int = DEFAULT_PORT
) {
    private val log = SensorLogger.Logger("NativeSensor.Signaling")
    private val gson = Gson()

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    @Volatile private var running = false

    /**
     * Start listening for incoming connections (server mode).
     */
    fun startServer() {
        running = true
        thread(name = "SignalingServer") {
            try {
                serverSocket = ServerSocket(port)
                log.info("Signaling server started on port $port")

                val socket = serverSocket?.accept() ?: return@thread
                log.info("Peer connected from ${socket.inetAddress.hostAddress}")

                setupConnection(socket)
                listener.onPeerConnected()
                readLoop()
            } catch (e: SocketException) {
                if (running) {
                    log.error("Server socket error", throwable = e)
                    listener.onError("Server socket error: ${e.message}")
                }
            } catch (e: Exception) {
                log.error("Signaling server error", throwable = e)
                listener.onError("Signaling error: ${e.message}")
            }
        }
    }

    /**
     * Connect to a remote signaling server (client mode).
     */
    fun connectToServer(remoteIp: String) {
        running = true
        thread(name = "SignalingClient") {
            try {
                log.info("Connecting to signaling server at $remoteIp:$port")
                val socket = Socket(remoteIp, port)
                log.info("Connected to signaling server")

                setupConnection(socket)
                listener.onPeerConnected()
                readLoop()
            } catch (e: Exception) {
                log.error("Failed to connect to server", throwable = e)
                listener.onError("Connection failed: ${e.message}")
            }
        }
    }

    /**
     * Send a signaling message to the peer.
     */
    fun send(message: SignalingMessage) {
        thread(name = "SignalingSend") {
            try {
                val json = gson.toJson(message)
                synchronized(this) {
                    writer?.println(json)
                    writer?.flush()
                }
                log.debug("Sent signaling message: ${message.type}")
            } catch (e: Exception) {
                log.error("Failed to send message", throwable = e)
            }
        }
    }

    /**
     * Stop the signaling server/client and release resources.
     */
    fun stop() {
        running = false
        try {
            writer?.println(gson.toJson(SignalingMessage(type = SignalingMessageType.BYE)))
            writer?.flush()
        } catch (_: Exception) {}

        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}

        reader = null
        writer = null
        clientSocket = null
        serverSocket = null

        log.info("Signaling stopped")
    }

    /**
     * Check if connected to a peer.
     */
    fun isConnected(): Boolean = clientSocket?.isConnected == true && !clientSocket!!.isClosed

    private fun setupConnection(socket: Socket) {
        clientSocket = socket
        writer = PrintWriter(socket.getOutputStream(), true)
        reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    }

    private fun readLoop() {
        try {
            while (running) {
                val line = reader?.readLine() ?: break
                try {
                    val message = gson.fromJson(line, SignalingMessage::class.java)
                    handleMessage(message)
                } catch (e: Exception) {
                    log.warn("Failed to parse signaling message: $line", throwable = e)
                }
            }
        } catch (e: SocketException) {
            if (running) {
                log.info("Peer disconnected")
            }
        } catch (e: Exception) {
            if (running) {
                log.error("Read loop error", throwable = e)
            }
        } finally {
            if (running) {
                listener.onPeerDisconnected()
            }
        }
    }

    private fun handleMessage(message: SignalingMessage) {
        log.debug("Received signaling message: ${message.type}")
        when (message.type) {
            SignalingMessageType.OFFER -> {
                message.sdp?.let { listener.onOfferReceived(it) }
            }
            SignalingMessageType.ANSWER -> {
                message.sdp?.let { listener.onAnswerReceived(it) }
            }
            SignalingMessageType.ICE_CANDIDATE -> {
                val sdpMid = message.sdpMid ?: return
                val sdpMLineIndex = message.sdpMLineIndex ?: return
                val candidate = message.candidate ?: return
                listener.onIceCandidateReceived(sdpMid, sdpMLineIndex, candidate)
            }
            SignalingMessageType.BYE -> {
                listener.onPeerDisconnected()
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}
