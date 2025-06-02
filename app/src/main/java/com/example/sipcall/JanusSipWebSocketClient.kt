package com.example.sipcall


import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class JanusSipWebSocketClient(
    serverUri: URI,
    private val lifecycleOwner: LifecycleOwner,
    private val listener: JanusListener,

    httpHeaders: Map<String, String> = emptyMap()
) : WebSocketClient(serverUri, httpHeaders) {

    interface JanusListener {
        fun onJanusConnected()
        fun onJanusDisconnected()
        fun onJanusError(error: String)
        fun onJanusEvent(event: JSONObject)
    }

    companion object {
        private const val TAG = "JanusSipWebSocketClient"
        private const val CONNECTION_TIMEOUT = 10000 // 10 seconds timeout
    }

    private var sessionId: Long = 0
    private var handleId: Long = 0
    private var isSessionCreated = false
    private var isPluginAttached = false
    private var currentCallId: String? = null


    // SIP Configuration - Updated to match working payload format
    private var sipUsername: String = ""      // This will be "sip:1007@103.209.42.30"
    private var sipAuthUser: String = ""      // This will be "1007"
    private var sipDisplayName: String = ""   // This will be "1007"
    private var sipSecret: String = ""        // This will be "1234"
    private var sipProxy: String = ""         // This will be "sip:103.209.42.30:5060"

    init {
        connectionLostTimeout = 30
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.d(TAG, "WebSocket connected, handshake: ${handshakedata?.httpStatus}")
        listener.onJanusConnected()
        createSession()
    }

    override fun onMessage(message: String?) {
        Log.d(TAG, "Received: $message")
        message?.let {
            try {
                val json = JSONObject(it)
                processJanusMessage(json)
                listener.onJanusEvent(json)
            } catch (e: JSONException) {
                listener.onJanusError("JSON parsing error: ${e.message}")
            }
        }
    }

    @Throws(JSONException::class)
    private fun processJanusMessage(json: JSONObject) {
        if (!json.has("janus")) return

        when (json.getString("janus")) {
            "success" -> handleSuccessResponse(json)
            "error" -> handleErrorResponse(json)
            "event" -> {}
        }
    }

    private fun handleSuccessResponse(json: JSONObject) {
        if (json.has("data") && json.getJSONObject("data").has("id")) {
            if (!json.has("session_id")) {
                // Session creation success
                sessionId = json.getJSONObject("data").getLong("id")
                isSessionCreated = true
                Log.d(TAG, "Session created: $sessionId")
                startKeepAlive()
                attachSipPlugin()

            } else {
                // Plugin attachment success
                handleId = json.getJSONObject("data").getLong("id")
                isPluginAttached = true
                Log.d(TAG, "SIP Plugin attached, handle ID: $handleId")
                register()
            }
        }
    }

    @Throws(JSONException::class)
    private fun handleErrorResponse(json: JSONObject) {
        val error = json.optString("error", "Unknown error")
        val reason = json.getJSONObject("error").optString("reason", "No reason provided")
        val errorMsg = "Janus error ($error): $reason"
        Log.e(TAG, errorMsg)
        listener.onJanusError(errorMsg)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "WebSocket closed. Code: $code, Reason: $reason")
        resetState()
        listener.onJanusDisconnected()
    }

    override fun onError(ex: Exception?) {
        val errorMsg = "WebSocket error: ${ex?.message}"
        Log.e(TAG, errorMsg, ex)
        listener.onJanusError(errorMsg)
    }

    @Throws(Exception::class)
    fun connectWithTimeout() {
        connectBlocking(CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
    }

    private fun resetState() {
        sessionId = 0
        handleId = 0
        isSessionCreated = false
        isPluginAttached = false
    }

    fun generateTransactionId(): String {
        return "txn-" + UUID.randomUUID().toString().substring(0, 8)
    }

    fun getSessionId(): Long {
        return sessionId
    }

    fun getHandleId(): Long {
        return handleId
    }

    private fun createSession() {
        try {
            val create = JSONObject()
            create.put("janus", "create")
            create.put("transaction", generateTransactionId())
            send(create.toString())
            Log.d(TAG, "Sent create session request")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating session request", e)
            listener.onJanusError("Error creating session: ${e.message}")
        }
    }

    private fun attachSipPlugin() {
        try {
            val attach = JSONObject()
            attach.put("janus", "attach")
            attach.put("plugin", "janus.plugin.sip")
            attach.put("opaque_id", "siptest-" + UUID.randomUUID().toString().substring(0, 12))
            attach.put("session_id", sessionId)
            attach.put("transaction", generateTransactionId())
            send(attach.toString())
            Log.d(TAG, "Sent attach SIP plugin request")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating attach request", e)
            listener.onJanusError("Error attaching SIP plugin: ${e.message}")
        }
    }

    fun setSipConfig(username: String, password: String, server: String, displayName: String) {
        // Format exactly as shown in working payload:
        // username = "1007", server = "103.209.42.30" -> sipUsername = "sip:1007@103.209.42.30"
        this.sipUsername = "sip:$username@$server"
        this.sipAuthUser = username  // Just the username part: "1007"
        this.sipDisplayName = displayName  // Display name: "1007"
        this.sipSecret = password  // Password: "1234"
        this.sipProxy = "sip:$server:5060"  // Proxy: "sip:103.209.42.30:5060"

        Log.d(TAG, "SIP Config set:")
        Log.d(TAG, "  sipUsername: $sipUsername")
        Log.d(TAG, "  sipAuthUser: $sipAuthUser")
        Log.d(TAG, "  sipDisplayName: $sipDisplayName")
        Log.d(TAG, "  sipSecret: $sipSecret")
        Log.d(TAG, "  sipProxy: $sipProxy")
    }

    fun register() {
        try {
            val register = JSONObject()
            register.put("janus", "message")
            register.put("session_id", sessionId)
            register.put("handle_id", handleId)
            register.put("transaction", generateTransactionId())

            val body = JSONObject()
            body.put("request", "register")
            body.put("username", sipUsername)        // "sip:1007@103.209.42.30"
            body.put("authuser", sipAuthUser)        // "1007"
            body.put("display_name", sipDisplayName) // "1007"
            body.put("secret", sipSecret)            // "1234"
            body.put("proxy", sipProxy)              // "sip:103.209.42.30:5060"

            register.put("body", body)

            Log.d(TAG, "Sending SIP register request:")
            Log.d(TAG, "  Request payload: ${register.toString()}")
            Log.d(TAG, "  Body: ${body.toString()}")

            send(register.toString())
            Log.d(TAG, "Sent SIP register request for: $sipUsername")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating register request", e)
            listener.onJanusError("Error registering: ${e.message}")
        }
    }

    fun call(targetUri: String, jsep: JSONObject?) {
        try {
            val call = JSONObject()
            call.put("janus", "message")
            call.put("session_id", sessionId)
            call.put("handle_id", handleId)
            call.put("transaction", generateTransactionId())

            val body = JSONObject()
            body.put("request", "call")
            body.put("uri", targetUri)
            body.put("autoaccept_reinvites", false)

            call.put("body", body)
            jsep?.let { call.put("jsep", it) }

            send(call.toString())
            Log.d(TAG, "Sent SIP call request to: $targetUri")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating call request", e)
            listener.onJanusError("Error calling: ${e.message}")
        }
    }

    fun hangup() {
        try {
            val hangup = JSONObject()
            hangup.put("janus", "message")
            hangup.put("session_id", sessionId)
            hangup.put("handle_id", handleId)
            hangup.put("transaction", generateTransactionId())

            val body = JSONObject()
            body.put("request", "hangup")

            hangup.put("body", body)
            send(hangup.toString())
            Log.d(TAG, "Sent hangup request")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating hangup request", e)
            listener.onJanusError("Error hanging up: ${e.message}")
        }
    }

    fun sendMessage(message: String) {
        if ( isOpen) {
            Log.d(TAG, "Sending message: $message")
            send(message)
        } else {
            Log.w(TAG, "WebSocket is not connected, cannot send message: $message")
            listener.onJanusError("WebSocket is not connected, cannot send message.")
        }
    }

    fun trickle(candidate: JSONObject) {
        try {
            val trickle = JSONObject()
            trickle.put("janus", "trickle")
            trickle.put("session_id", sessionId)
            trickle.put("handle_id", handleId)
            trickle.put("transaction", generateTransactionId())
            trickle.put("candidate", candidate)
            send(trickle.toString())
            Log.d(TAG, "Sent trickle candidate")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating trickle request", e)
            listener.onJanusError("Error sending ICE candidate: ${e.message}")
        }
    }

    private fun sendKeepAlive() {
        try {
            val keepAlive = JSONObject()
            keepAlive.put("janus", "keepalive")
            keepAlive.put("session_id", sessionId)
            keepAlive.put("transaction", generateTransactionId())
            send(keepAlive.toString())
            Log.d(TAG, "Sent keepalive")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating keepalive", e)
            listener.onJanusError("Error sending keepalive: ${e.message}")
        }
    }


    private fun startKeepAlive() {
        // Use the lifecycleScope from the provided lifecycleOwner
        lifecycleOwner.lifecycleScope.launch {
            while (sessionId != 0L) {
                sendKeepAlive()
                delay(20000)
            }
        }
    }




}