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

class MainActivity : AppCompatActivity(), JanusSipWebSocketClient.JanusSipListener,
    SipPeerConnectionClient.SipPeerConnectionListener {

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
        callButton.setOnClickListener { makeCall() }
        answerButton.setOnClickListener { answerCall() }
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
            Manifest.permission.ACCESS_NETWORK_STATE
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
                    Toast.makeText(this, "Audio permission is required for SIP calls", Toast.LENGTH_LONG)
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
            statusTextView.text = "Please fill all SIP configuration fields"
            return
        }

        currentSipUser = username
        Thread {
            try {
                val serverUri = URI("wss://janus.arafinahmed.com/")
                val httpHeaders = mapOf("Sec-WebSocket-Protocol" to "janus-protocol")

                runOnUiThread { statusTextView.text = "Connecting to Janus server..." }

                webSocketClient = JanusSipWebSocketClient(serverUri, this@MainActivity, httpHeaders)
                webSocketClient?.connectWithTimeout()
            } catch (e: Exception) {
                runOnUiThread { statusTextView.text = "Connection failed: ${e.message}" }
                Log.e(TAG, "WebSocket connection error", e)
            }
        }.start()
    }

    private fun unregisterSipUser() {
        webSocketClient?.unregister()
        isRegistered = false
        updateButtonStates(false, false)
        statusTextView.text = "Unregistered"
    }

    private fun makeCall() {
        val targetSip = targetSipEditText.text.toString().trim()
        val server = sipServerEditText.text.toString().trim()

        if (targetSip.isEmpty()) {
            callStatusTextView.text = "Please enter target SIP address"
            return
        }

        val fullTargetUri = "sip:$targetSip@$server"

        if (peerConnectionClient == null) {
            peerConnectionClient = SipPeerConnectionClient(
                this,
                webSocketClient!!,
                this
            )
            peerConnectionClient?.createPeerConnection()
        }

        peerConnectionClient?.createOffer(fullTargetUri)
        callStatusTextView.text = "Calling $fullTargetUri..."
        updateButtonStates(true, true)
    }

    private fun answerCall() {
        peerConnectionClient?.createAnswer()
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

    // JanusSipListener implementations
    override fun onJanusConnected() {
        runOnUiThread { statusTextView.text = "Connected to Janus server" }
    }

    override fun onJanusDisconnected() {
        runOnUiThread {
            statusTextView.text = "Disconnected from Janus server"
            isRegistered = false
            updateButtonStates(false, false)
            peerConnectionClient?.close()
            peerConnectionClient = null
        }
    }

    override fun onJanusError(error: String) {
        runOnUiThread { statusTextView.text = "Error: $error" }
    }

    override fun onSipRegistered() {
        runOnUiThread {
            statusTextView.text = "SIP Registration successful"
            isRegistered = true
            updateButtonStates(true, false)
        }
    }

    override fun onSipRegistrationFailed(error: String) {
        runOnUiThread {
            statusTextView.text = "SIP Registration failed: $error"
            isRegistered = false
            updateButtonStates(false, false)
        }
    }

    override fun onIncomingCall(caller: String) {
        runOnUiThread {
            callStatusTextView.text = "Incoming call from $caller"
            answerButton.isEnabled = true

            if (peerConnectionClient == null) {
                peerConnectionClient = SipPeerConnectionClient(
                    this@MainActivity,
                    webSocketClient!!,
                    this@MainActivity
                )
                peerConnectionClient?.createPeerConnection()
            }
        }
    }

    override fun onCallRinging() {
        runOnUiThread { callStatusTextView.text = "Ringing..." }
    }

    override fun onCallProceeding(code: Int) {
        runOnUiThread { callStatusTextView.text = "Call proceeding (Code: $code)" }
    }

    override fun onCallAccepted(username: String) {
        runOnUiThread {
            callStatusTextView.text = "Call accepted by $username"
            updateButtonStates(true, true)
            answerButton.isEnabled = false
        }
    }

    override fun onCallHangup(code: Int, reason: String) {
        runOnUiThread {
            callStatusTextView.text = "Call ended: $reason (Code: $code)"
            hangupCall()
        }
    }

    override fun onJanusEvent(event: JSONObject) {
        // Additional event handling if needed
        Log.d(TAG, "Janus event: $event")
    }

    // SipPeerConnectionListener implementations
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
                PeerConnection.PeerConnectionState.CONNECTED -> callStatusTextView.text = "Call Connected"
                PeerConnection.PeerConnectionState.DISCONNECTED -> callStatusTextView.text = "Call Disconnected"
                PeerConnection.PeerConnectionState.FAILED -> {
                    callStatusTextView.text = "Call Connection failed"
                    hangupCall()
                }
                PeerConnection.PeerConnectionState.CLOSED -> callStatusTextView.text = "Call Connection closed"
                else -> {}
            }
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            callStatusTextView.text = "Error: $error"
            // Reset states on error
            updateButtonStates(isRegistered, false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient?.close()
        peerConnectionClient?.close()
    }
}