package com.example.sipcall

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.*
import java.util.Collections


class SipPeerConnectionClient(
    private val context: Context,
    private val webSocketClient: JanusSipWebSocketClient,
//    private val localVideoView: SurfaceViewRenderer,
//    private val remoteVideoView: SurfaceViewRenderer,
    private val listener: PeerConnectionListener
) {
    interface PeerConnectionListener {
        fun onLocalStream(stream: MediaStream)
        fun onRemoteStream(stream: MediaStream)
        fun onIceCandidate(candidate: IceCandidate)
        fun onConnectionChange(state: PeerConnection.PeerConnectionState)
        fun onError(error: String)
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localStream: MediaStream? = null

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(
            eglBaseContext
        )

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun createPeerConnection() {
        val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(TAG, "onSignalingChange: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: $iceConnectionState")
            }

            override fun onIceConnectionReceivingChange(b: Boolean) {
                Log.d(TAG, "onIceConnectionReceivingChange: $b")
            }

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: $iceGatheringState")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Log.d(TAG, "onIceCandidate: $iceCandidate")
                try {
                    val candidateJson = JSONObject()
                    candidateJson.put("sdpMid", iceCandidate.sdpMid)
                    candidateJson.put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
                    candidateJson.put("candidate", iceCandidate.sdp)
                    webSocketClient.trickle(candidateJson)
                } catch (e: JSONException) {
                    Log.e(TAG, "Error creating candidate JSON", e)
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                Log.d(TAG, "onIceCandidatesRemoved")
            }

            override fun onAddStream(mediaStream: MediaStream) {
                Log.d(TAG, "onAddStream (deprecated): ${mediaStream.id}")
            }

            override fun onRemoveStream(mediaStream: MediaStream) {
                Log.d(TAG, "onRemoveStream: ${mediaStream.id}")
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.d(TAG, "onDataChannel: ${dataChannel.label()}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded")
            }

            override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                Log.d(TAG, "onAddTrack")
                if (rtpReceiver.track() is VideoTrack) {
                    val remoteVideoTrack = rtpReceiver.track() as VideoTrack
//                    remoteVideoTrack.addSink(remoteVideoView)
                    if (mediaStreams.isNotEmpty()) {
                        listener.onRemoteStream(mediaStreams[0])
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "onConnectionChange: $newState")
                listener.onConnectionChange(newState)
            }
        })
    }

    fun startLocalVideo() {
        videoCapturer = createCameraCapturer()
        if (videoCapturer == null) {
            listener.onError("Failed to create camera capturer")
            return
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)

        val videoSource = factory?.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer?.startCapture(640, 480, 30)

        val videoTrack = factory?.createVideoTrack("ARDAMSv0", videoSource)
        val audioSource = factory?.createAudioSource(MediaConstraints())
        val audioTrack = factory?.createAudioTrack("ARDAMSa0", audioSource)

        localStream = factory?.createLocalMediaStream("ARDAMS")
        localStream?.addTrack(videoTrack)
        localStream?.addTrack(audioTrack)

        val streamIds = Collections.singletonList("ARDAMS")
        peerConnection?.addTrack(videoTrack, streamIds)
        peerConnection?.addTrack(audioTrack, streamIds)

//        videoTrack?.addSink(localVideoView)
        listener.onLocalStream(localStream!!)
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val videoCapturer = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val videoCapturer = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        return null
    }

    fun createOffer(peerUsername: String) {
        val sdpConstraints = MediaConstraints()
        sdpConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        sdpConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sessionDescription: SessionDescription) {}

                    override fun onSetSuccess() {
                        try {
                            val jsep = JSONObject()
                            jsep.put("type", sessionDescription.type.canonicalForm())
                            jsep.put("sdp", sessionDescription.description)
                            webSocketClient.call(peerUsername, jsep)
                        } catch (e: JSONException) {
                            Log.e(TAG, "Error creating JSEP JSON", e)
                        }
                    }

                    override fun onCreateFailure(s: String) {
                        Log.e(TAG, "onCreateFailure: $s")
                    }

                    override fun onSetFailure(s: String) {
                        Log.e(TAG, "onSetFailure: $s")
                    }
                }, sessionDescription)
            }

            override fun onSetSuccess() {}

            override fun onCreateFailure(s: String) {
                Log.e(TAG, "onCreateFailure: $s")
            }

            override fun onSetFailure(s: String) {
                Log.e(TAG, "onSetFailure: $s")
            }
        }, sdpConstraints)
    }

    fun setRemoteDescription(jsep: JSONObject) {
        try {
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(jsep.getString("type")),
                jsep.getString("sdp")
            )

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sessionDescription: SessionDescription) {}

                override fun onSetSuccess() {
                    if (sessionDescription.type == SessionDescription.Type.OFFER) {
                        createAnswer()
                    }
                }

                override fun onCreateFailure(s: String) {
                    Log.e(TAG, "onCreateFailure: $s")
                }

                override fun onSetFailure(s: String) {
                    Log.e(TAG, "onSetFailure: $s")
                }
            }, sessionDescription)
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing JSEP", e)
        }
    }

    private fun createAnswer() {
        val sdpConstraints = MediaConstraints()
        sdpConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        sdpConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sessionDescription: SessionDescription) {}

                    override fun onSetSuccess() {
                        try {
                            val jsep = JSONObject()
                            jsep.put("type", sessionDescription.type.canonicalForm())
                            jsep.put("sdp", sessionDescription.description)

                            val body = JSONObject()
                            body.put("request", "accept")

                            val accept = JSONObject()
                            accept.put("janus", "message")
                            accept.put("session_id", webSocketClient.getSessionId())
                            accept.put("handle_id", webSocketClient.getHandleId())
                            accept.put("transaction", webSocketClient.generateTransactionId())
                            accept.put("body", body)
                            accept.put("jsep", jsep)

                            webSocketClient.send(accept.toString())
                            Log.d(TAG, "Sent answer SDP: $accept")
                        } catch (e: JSONException) {
                            Log.e(TAG, "Error creating answer JSEP", e)
                        }
                    }

                    override fun onCreateFailure(s: String) {
                        Log.e(TAG, "onCreateFailure: $s")
                    }

                    override fun onSetFailure(s: String) {
                        Log.e(TAG, "onSetFailure: $s")
                    }
                }, sessionDescription)
            }

            override fun onSetSuccess() {}

            override fun onCreateFailure(s: String) {
                Log.e(TAG, "onCreateFailure: $s")
            }

            override fun onSetFailure(s: String) {
                Log.e(TAG, "onSetFailure: $s")
            }
        }, sdpConstraints)
    }

    fun addIceCandidate(candidate: JSONObject) {
        try {
            val iceCandidate = IceCandidate(
                candidate.getString("sdpMid"),
                candidate.getInt("sdpMLineIndex"),
                candidate.getString("candidate")
            )
            peerConnection?.addIceCandidate(iceCandidate)
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing ICE candidate", e)
        }
    }

    fun close() {
        peerConnection?.let { pc ->
            pc.senders.forEach { sender ->
                pc.removeTrack(sender)
            }
            pc.close()
        }
        peerConnection = null

        videoCapturer?.let { vc ->
            try {
                vc.stopCapture()
                vc.dispose()
            } catch (e: InterruptedException) {
                Log.e(TAG, "Error stopping video capture", e)
            }
        }
        videoCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        localStream?.dispose()
        localStream = null
    }

    companion object {
        private const val TAG = "PeerConnectionClient"
        private val eglBase: EglBase by lazy { EglBase.create() }
        val eglBaseContext = eglBase.eglBaseContext
    }
}