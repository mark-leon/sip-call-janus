package com.example.sipcall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import java.net.URI

class MainActivity : AppCompatActivity(), JanusSipWebSocketClient.JanusListener,
    SipPeerConnectionClient.PeerConnectionListener {

    companion object {
        private const val TAG = "SipMainActivity"
        private const val PERMISSION_REQUEST_CODE = 1
        var currentSipUser: String? = null
        var isRegistered = false
    }

    private var webSocketClient: JanusSipWebSocketClient? = null
    private var peerConnectionClient: SipPeerConnectionClient? = null

    // SIP Configuration
    private lateinit var sipUsernameEditText: EditText
    private lateinit var sipPasswordEditText: EditText
    private lateinit var sipServerEditText: EditText
    private lateinit var sipDisplayNameEditText: EditText
    private lateinit var targetSipEditText: EditText

    // Control Buttons
    private lateinit var registerButton: Button
    private lateinit var unregisterButton: Button
    private lateinit var callButton: Button
    private lateinit var answerButton: Button
    private lateinit var hangupButton: Button

    // Status Display
    private lateinit var statusTextView: TextView
    private lateinit var callStatusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        requestPermissions()
    }

    private fun initializeViews() {
        // SIP Configuration inputs
        sipUsernameEditText = findViewById(R.id.sipUsernameEditText)
        sipPasswordEditText = findViewById(R.id.sipPasswordEditText)
        sipServerEditText = findViewById(R.id.sipServerEditText)
        sipDisplayNameEditText = findViewById(R.id.sipDisplayNameEditText)
        targetSipEditText = findViewById(R.id.targetSipEditText)

        // Control buttons
        registerButton = findViewById(R.id.registerButton)
        unregisterButton = findViewById(R.id.unregisterButton)
        callButton = findViewById(R.id.callButton)
        answerButton = findViewById(R.id.answerButton)
        hangupButton = findViewById(R.id.hangupButton)

        // Status displays
        statusTextView = findViewById(R.id.statusTextView)
        callStatusTextView = findViewById(R.id.callStatusTextView)

        // Set button listeners
        registerButton.setOnClickListener { registerSipUser() }
        unregisterButton.setOnClickListener { unregisterSipUser() }
        callButton.setOnClickListener { callPeer() }
        answerButton.setOnClickListener { answerIncomingCall() }
        hangupButton.setOnClickListener { hangupCall() }

        // Set default values for testing
        sipUsernameEditText.setText("1007")
        sipPasswordEditText.setText("1234")
        sipServerEditText.setText("103.209.42.30")
        sipDisplayNameEditText.setText("1007")
        targetSipEditText.setText("1006")

        // Initially disable certain buttons
        updateButtonStates(false, false)
    }

    private fun updateButtonStates(registered: Boolean, inCall: Boolean) {
        registerButton.isEnabled = !registered
        unregisterButton.isEnabled = registered
        callButton.isEnabled = registered && !inCall
        answerButton.isEnabled = false // Will be enabled when incoming call
        hangupButton.isEnabled = inCall
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: NetworkInfo? = cm.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )

        if (!hasPermissions(permissions)) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        }
    }

    private fun hasPermissions(permissions: Array<String>): Boolean {
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        @NonNull permissions: Array<String>,
        @NonNull grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "All permissions are required for SIP calls", Toast.LENGTH_LONG)
                        .show()
                    finish()
                    return
                }
            }
        }
    }

    private fun registerSipUser() {
        if (!isNetworkAvailable()) {
            statusTextView.text = "No network connection"
            return
        }

        val username = sipUsernameEditText.text.toString().trim()
        val password = sipPasswordEditText.text.toString().trim()
        val server = sipServerEditText.text.toString().trim()
        val displayName = sipDisplayNameEditText.text.toString().trim()

        if (username.isEmpty() || password.isEmpty() || server.isEmpty()) {
            statusTextView.text = "Please fill in all SIP configuration fields"
            return
        }

        currentSipUser = username
        Thread {
            try {
                val serverUri = URI("wss://janus.arafinahmed.com/")
                val httpHeaders = mapOf("Sec-WebSocket-Protocol" to "janus-protocol")

                runOnUiThread { statusTextView.text = "Connecting to Janus SIP server..." }


                webSocketClient = JanusSipWebSocketClient(serverUri, lifecycleOwner = this, this@MainActivity, httpHeaders)
                webSocketClient?.setSipConfig(username, password, server, displayName)
                webSocketClient?.connectWithTimeout()


            } catch (e: Exception) {
                runOnUiThread { statusTextView.text = "Connection failed: ${e.message}" }
                Log.e(TAG, "WebSocket connection error", e)
            }
        }.start()
    }

    private fun unregisterSipUser() {
        isRegistered = false
        currentSipUser = null
        statusTextView.text = "Unregistered from SIP server"
        updateButtonStates(false, false)
    }

    private fun callPeer() {
        val targetSip = targetSipEditText.text.toString().trim()
        val server = sipServerEditText.text.toString().trim()
        if (targetSip.isEmpty()) {
            callStatusTextView.text = "Please enter target SIP address"
            return
        }
        val fullTargetUri = "sip:$targetSip@$server"

        if (!isRegistered) {
            callStatusTextView.text = "Please register first"
            return
        }

        if (peerConnectionClient == null) {
            peerConnectionClient = SipPeerConnectionClient(
                this,
                webSocketClient!!,
                this
            )
            peerConnectionClient?.createPeerConnection()
            peerConnectionClient?.startLocalAudio()
        }

        peerConnectionClient?.createOffer(fullTargetUri)
        callStatusTextView.text = "Calling $targetSip..."
        updateButtonStates(true, true)
    }

    private fun answerIncomingCall() {
        if (peerConnectionClient == null) {
            peerConnectionClient = SipPeerConnectionClient(
                this,
                webSocketClient!!,
                this
            )
            peerConnectionClient?.createPeerConnection()
        }


        callStatusTextView.text = "Answering call..."
        updateButtonStates(true, true)
        answerButton.isEnabled = false
    }

    private fun hangupCall() {
        webSocketClient?.hangup()

        peerConnectionClient?.close()
        peerConnectionClient = null

        runOnUiThread {
            callStatusTextView.text = "Call ended"
            updateButtonStates(isRegistered, false)
        }
    }

    // Janus WebSocket Listener Methods
    override fun onJanusConnected() {
        runOnUiThread {
            statusTextView.text = "Connected to Janus SIP server"
            // Automatically register SIP after connection
        }
    }

    override fun onJanusDisconnected() {
        runOnUiThread {
            statusTextView.text = "Disconnected from Janus SIP server"
            isRegistered = false
            peerConnectionClient?.close()
            peerConnectionClient = null
            updateButtonStates(false, false)
        }
    }

    override fun onJanusError(error: String) {
        runOnUiThread {
            statusTextView.text = "Error: $error"
            updateButtonStates(false, false)
        }
    }

    override fun onJanusEvent(event: JSONObject) {
        try {
            if (event.has("janus")) {
                when (event.getString("janus")) {
                    "event" -> {
                        if (event.has("plugindata")) {
                            val plugindata = event.getJSONObject("plugindata")
                            val data = plugindata.getJSONObject("data")

                            if (data.has("result")) {
                                val result = data.getJSONObject("result")
                                if (result.has("event")) {
                                    handlePluginEvent(event, result.getString("event"), result)
                                }
                            }
                        }
                    }
                    "webrtcup" -> runOnUiThread {
                        callStatusTextView.text = "Call established"
                        updateButtonStates(true, true)
                    }
                    "hangup" -> handleHangup()
                    "trickle" -> handleTrickleEvent(event)
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing Janus event", e)
        }
    }

    @Throws(JSONException::class)
    private fun handlePluginEvent(event: JSONObject, eventType: String, result: JSONObject) {
        when (eventType) {
            "registered" -> handleSipRegistered()
            "registration_failed" -> handleSipRegistrationFailed(result)
            "incomingcall" -> handleIncomingCall(event, result)
            "calling" -> handleCalling()
            "accepted" -> handleCallAccepted(event)
            "declined" -> handleCallDeclined()
            "hangup" -> handleHangup()
            else -> Log.d(TAG, "Unhandled SIP event type: $eventType")
        }
    }

    private fun handleSipRegistered() {
        runOnUiThread {
            statusTextView.text = "Successfully registered to SIP server"
            isRegistered = true
            updateButtonStates(true, false)
        }
    }

    private fun handleSipRegistrationFailed(result: JSONObject) {
        val reason = try {
            result.getString("reason")
        } catch (e: JSONException) {
            "Unknown error"
        }
        runOnUiThread {
            statusTextView.text = "SIP registration failed: $reason"
            isRegistered = false
            updateButtonStates(false, false)
        }
    }

    @Throws(JSONException::class)
    private fun handleIncomingCall(event: JSONObject, result: JSONObject) {
        val caller = result.getString("username")
        runOnUiThread {
            callStatusTextView.text = "Incoming call from $caller"
            answerButton.isEnabled = true
            updateButtonStates(true, false)

            if (event.has("jsep")) {
                try {
                    val jsep = event.getJSONObject("jsep")
                    if (peerConnectionClient == null) {
                        peerConnectionClient = SipPeerConnectionClient(
                            this@MainActivity,
                            webSocketClient!!,
                            this@MainActivity
                        )
                        peerConnectionClient?.createPeerConnection()
                    }
                    peerConnectionClient?.setRemoteDescription(jsep)
                } catch (e: JSONException) {
                    Log.e(TAG, "Error parsing JSEP", e)
                    runOnUiThread { callStatusTextView.text = "Error parsing call data" }
                }
            }
        }
    }

    private fun handleCalling() {
        runOnUiThread {
            callStatusTextView.text = "Calling..."
        }
    }

    @Throws(JSONException::class)
    private fun handleCallAccepted(event: JSONObject) {
        runOnUiThread {
            callStatusTextView.text = "Call accepted"
            updateButtonStates(true, true)
        }
        if (event.has("jsep")) {
            val jsep = event.getJSONObject("jsep")
            peerConnectionClient?.setRemoteDescription(jsep)
        }
    }

    private fun handleCallDeclined() {
        runOnUiThread {
            callStatusTextView.text = "Call declined"
            updateButtonStates(isRegistered, false)
        }
    }

    private fun handleHangup() {
        runOnUiThread {
            callStatusTextView.text = "Call ended by remote peer"
            hangupCall()
        }
    }

    @Throws(JSONException::class)
    private fun handleTrickleEvent(event: JSONObject) {
        if (event.has("candidate")) {
            val candidate = event.getJSONObject("candidate")
            peerConnectionClient?.addIceCandidate(candidate)
        }
    }

    // Peer Connection Listener Methods
    override fun onLocalStream(stream: MediaStream) {
        runOnUiThread {
            Log.d(TAG, "Local audio stream added")
        }
    }

    override fun onRemoteStream(stream: MediaStream) {
        runOnUiThread {
            Log.d(TAG, "Remote audio stream added")
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        // Handled by SipPeerConnectionClient
    }

    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
        runOnUiThread {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    callStatusTextView.text = "Connected"
                    updateButtonStates(true, true)
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    callStatusTextView.text = "Disconnected"
                    updateButtonStates(isRegistered, false)
                }
                PeerConnection.PeerConnectionState.FAILED -> {
                    callStatusTextView.text = "Connection failed"
                    hangupCall()
                }
                PeerConnection.PeerConnectionState.CLOSED -> {
                    callStatusTextView.text = "Connection closed"
                    updateButtonStates(isRegistered, false)
                }
                else -> {}
            }
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            callStatusTextView.text = "Error: $error"
            updateButtonStates(isRegistered, false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient?.close()
        peerConnectionClient?.close()
    }
}