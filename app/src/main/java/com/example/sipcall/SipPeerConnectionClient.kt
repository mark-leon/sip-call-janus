package com.example.sipcall

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.*
import java.util.*

class SipPeerConnectionClient(
    private val context: Context,
    private val webSocketClient: JanusSipWebSocketClient,
    private val listener: SipPeerConnectionListener
) {

    interface SipPeerConnectionListener {
        fun onLocalStream(stream: MediaStream)
        fun onRemoteStream(stream: MediaStream)
        fun onIceCandidate(candidate: IceCandidate)
        fun onConnectionChange(state: PeerConnection.PeerConnectionState)
        fun onError(error: String)
    }

    companion object {
        private const val TAG = "SipPeerConnectionClient"
        private const val LOCAL_STREAM_ID = "ARDAMSlocal"
        private const val AUDIO_TRACK_ID = "ARDAAUDIOlocal"
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localStream: MediaStream? = null
    private var pendingRemoteSessionDescription: SessionDescription? = null
    private var isInitiator = false
    private var pendingTargetUri: String? = null

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )

            val options = PeerConnectionFactory.Options()
            factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(null))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(null, false, false))
                .createPeerConnectionFactory()

            Log.d(TAG, "PeerConnectionFactory initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PeerConnectionFactory", e)
            listener.onError("Failed to initialize WebRTC: ${e.message}")
        }
    }

    fun createPeerConnection() {
        try {
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                keyType = PeerConnection.KeyType.ECDSA
            }

            peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling state changed: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state changed: $state")
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                    Log.d(TAG, "Connection state changed: $state")
                    state?.let { listener.onConnectionChange(it) }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "ICE connection receiving changed: $receiving")
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE gathering state changed: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "ICE candidate: ${it.sdp}")
                        sendIceCandidate(it)
                        listener.onIceCandidate(it)
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                    Log.d(TAG, "ICE candidates removed: ${candidates?.size}")
                }

                override fun onAddStream(stream: MediaStream?) {
                    Log.d(TAG, "Remote stream added")
                    stream?.let { listener.onRemoteStream(it) }
                }

                override fun onRemoveStream(stream: MediaStream?) {
                    Log.d(TAG, "Remote stream removed")
                }

                override fun onDataChannel(dataChannel: DataChannel?) {
                    Log.d(TAG, "Data channel created")
                }

                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed")
                }

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    Log.d(TAG, "Track added")
                }
            })

            createLocalAudioTrack()
            Log.d(TAG, "PeerConnection created successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PeerConnection", e)
            listener.onError("Failed to create peer connection: ${e.message}")
        }
    }

    private fun createLocalAudioTrack() {
        try {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }

            val audioSource: AudioSource? = factory?.createAudioSource(audioConstraints)
            val audioTrack: AudioTrack? = factory?.createAudioTrack(AUDIO_TRACK_ID, audioSource)

            localStream = factory?.createLocalMediaStream(LOCAL_STREAM_ID)
            audioTrack?.let { localStream?.addTrack(it) }

            localStream?.let {
                peerConnection?.addStream(it)
                listener.onLocalStream(it)
            }

            Log.d(TAG, "Local audio track created and added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create local audio track", e)
            listener.onError("Failed to create audio track: ${e.message}")
        }
    }

    fun createOffer(targetUri: String) {
        isInitiator = true
        pendingTargetUri = targetUri

        try {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }

            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    Log.d(TAG, "Create offer success")
                    sdp?.let { setLocalDescription(it, targetUri) }
                }

                override fun onSetSuccess() {
                    Log.d(TAG, "Set description success in createOffer")
                }

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create offer failed: $error")
                    listener.onError("Create offer failed: $error")
                }

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Set description failed in createOffer: $error")
                    listener.onError("Set local description failed: $error")
                }
            }, constraints)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create offer", e)
            listener.onError("Failed to create offer: ${e.message}")
        }
    }

    fun createAnswer() {
        isInitiator = false

        try {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }

            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    Log.d(TAG, "Create answer success")
                    sdp?.let { setLocalDescription(it, null) }
                }

                override fun onSetSuccess() {
                    Log.d(TAG, "Set description success in createAnswer")
                }

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create answer failed: $error")
                    listener.onError("Create answer failed: $error")
                }

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Set description failed in createAnswer: $error")
                    listener.onError("Set local description failed: $error")
                }
            }, constraints)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create answer", e)
            listener.onError("Failed to create answer: ${e.message}")
        }
    }

    private fun setLocalDescription(sdp: SessionDescription, targetUri: String?) {
        peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Set local description success")

                // Send the SDP to Janus
                try {
                    val jsep = JSONObject().apply {
                        put("type", sdp.type.canonicalForm())
                        put("sdp", sdp.description)
                    }

                    if (isInitiator && targetUri != null) {
                        // This is an offer for outgoing call
                        webSocketClient.call(targetUri, jsep)
                    } else if (!isInitiator) {
                        // This is an answer for incoming call
                        webSocketClient.accept(jsep)
                    }
                } catch (e: JSONException) {
                    Log.e(TAG, "Failed to create JSEP", e)
                    listener.onError("Failed to create SDP JSON: ${e.message}")
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set local description failed: $error")
                listener.onError("Set local description failed: $error")
            }

            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, sdp)
    }

    fun setRemoteDescription(jsep: JSONObject) {
        try {
            val type = jsep.getString("type")
            val sdp = jsep.getString("sdp")

            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type),
                sdp
            )

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "Set remote description success")

                    // If this was an offer, we need to create an answer
                    if (type == "offer") {
                        createAnswer()
                    }
                }

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Set remote description failed: $error")
                    listener.onError("Set remote description failed: $error")
                }

                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, sessionDescription)

        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse remote SDP", e)
            listener.onError("Failed to parse remote SDP: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set remote description", e)
            listener.onError("Failed to set remote description: ${e.message}")
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        try {
            val candidateJson = JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("sdpMid", candidate.sdpMid)
            }

            webSocketClient.trickle(candidateJson)
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to send ICE candidate", e)
            listener.onError("Failed to send ICE candidate: ${e.message}")
        }
    }

    fun addIceCandidate(candidate: JSONObject) {
        try {
            val sdp = candidate.getString("candidate")
            val sdpMLineIndex = candidate.getInt("sdpMLineIndex")
            val sdpMid = candidate.getString("sdpMid")

            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
            peerConnection?.addIceCandidate(iceCandidate)

            Log.d(TAG, "Added ICE candidate: $sdp")
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to add ICE candidate", e)
            listener.onError("Failed to add ICE candidate: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add ICE candidate", e)
            listener.onError("Failed to add ICE candidate: ${e.message}")
        }
    }

    fun close() {
        try {
            localStream?.dispose()
            peerConnection?.close()
            peerConnection = null
            localStream = null
            pendingRemoteSessionDescription = null
            isInitiator = false
            pendingTargetUri = null

            Log.d(TAG, "PeerConnection closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PeerConnection", e)
        }
    }

    fun dispose() {
        close()
        factory?.dispose()
        factory = null
    }
}