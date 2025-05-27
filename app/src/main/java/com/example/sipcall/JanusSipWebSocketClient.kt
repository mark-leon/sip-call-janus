package com.example.sipcall

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

class JanusSipWebSocketClient(
    serverUri: URI,
    private val listener: JanusSipListener,
    httpHeaders: Map<String, String> = emptyMap()
) : WebSocketClient(serverUri, httpHeaders) {

    interface JanusSipListener {
        fun onJanusConnected()
        fun onJanusDisconnected()
        fun onJanusError(error: String)
        fun onJanusEvent(event: JSONObject)
        fun onSipRegistered()
        fun onSipRegistrationFailed(error: String)
        fun onIncomingCall(caller: String)
        fun onCallRinging()
        fun onCallProceeding(code: Int)
        fun onCallAccepted(username: String)
        fun onCallHangup(code: Int, reason: String)
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

    // SIP Configuration
    private var sipUsername: String = ""
    private var sipAuthUser: String = ""
    private var sipDisplayName: String = ""
    private var sipSecret: String = ""
    private var sipProxy: String = ""

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
            "event" -> handleEventResponse(json)
            "webrtcup" -> listener.onJanusEvent(json)
            "media" -> listener.onJanusEvent(json)
            "hangup" -> handleHangupResponse(json)
            "trickle" -> handleTrickleEvent(json)
        }
    }

    @Throws(JSONException::class)
    private fun handleSuccessResponse(json: JSONObject) {
        if (json.has("data") && json.getJSONObject("data").has("id")) {
            if (!json.has("session_id")) {
                // Session creation success
                sessionId = json.getJSONObject("data").getLong("id")
                isSessionCreated = true
                Log.d(TAG, "Session created: $sessionId")
                attachSipPlugin()
            } else {
                // Plugin attachment success
                handleId = json.getJSONObject("data").getLong("id")
                isPluginAttached = true
                Log.d(TAG, "SIP Plugin attached, handle ID: $handleId")
                registerSipUser()
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

    @Throws(JSONException::class)
    private fun handleEventResponse(json: JSONObject) {
        if (json.has("plugindata")) {
            val plugindata = json.getJSONObject("plugindata")
            val plugin = plugindata.getString("plugin")

            if (plugin == "janus.plugin.sip") {
                val data = plugindata.getJSONObject("data")

                if (data.has("sip") && data.getString("sip") == "event") {
                    val result = data.getJSONObject("result")
                    val event = result.getString("event")

                    if (data.has("call_id")) {
                        currentCallId = data.getString("call_id")
                    }

                    handleSipEvent(event, result, json)
                }
            }
        }
    }

    @Throws(JSONException::class)
    private fun handleSipEvent(event: String, result: JSONObject, fullJson: JSONObject) {
        when (event) {
            "registered" -> {
                Log.d(TAG, "SIP Registration successful")
                listener.onSipRegistered()
            }
            "registration_failed" -> {
                val code = result.optInt("code", 0)
                val reason = result.optString("reason", "Unknown error")
                Log.e(TAG, "SIP Registration failed: $code - $reason")
                listener.onSipRegistrationFailed("$code - $reason")
            }
            "incomingcall" -> {
                val caller = result.optString("username", "Unknown caller")
                Log.d(TAG, "Incoming call from: $caller")
                listener.onIncomingCall(caller)

                // Handle incoming call JSEP if present
                if (fullJson.has("jsep")) {
                    // This will trigger the peer connection to handle the offer
                }
            }
            "calling" -> {
                currentCallId = result.optString("call_id", "")
                Log.d(TAG, "Call initiated, call_id: $currentCallId")
            }
            "ringing" -> {
                Log.d(TAG, "Call is ringing")
                listener.onCallRinging()
            }
            "proceeding" -> {
                val code = result.optInt("code", 0)
                Log.d(TAG, "Call proceeding with code: $code")
                listener.onCallProceeding(code)
            }
            "accepted" -> {
                val username = result.optString("username", "Unknown")
                Log.d(TAG, "Call accepted by: $username")
                listener.onCallAccepted(username)
            }
            "hangup" -> {
                val code = result.optInt("code", 0)
                val reason = result.optString("reason", "Unknown reason")
                Log.d(TAG, "Call hangup: $code - $reason")
                listener.onCallHangup(code, reason)
            }
            else -> {
                Log.d(TAG, "Unhandled SIP event: $event")
            }
        }
    }

    @Throws(JSONException::class)
    private fun handleHangupResponse(json: JSONObject) {
        val reason = json.optString("reason", "Unknown reason")
        Log.d(TAG, "Janus hangup: $reason")
        // This will be handled by the hangup event from plugin data
    }

    @Throws(JSONException::class)
    private fun handleTrickleEvent(json: JSONObject) {
        // Handle ICE candidates if needed
        Log.d(TAG, "Trickle event received")
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
        currentCallId = null
    }

    fun generateTransactionId(): String {
        return "txn-" + UUID.randomUUID().toString().substring(0, 8)
    }

    fun getSessionId(): Long = sessionId
    fun getHandleId(): Long = handleId
    fun getCurrentCallId(): String? = currentCallId

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
        this.sipUsername = "sip:$username@$server"
        this.sipAuthUser = username
        this.sipDisplayName = displayName
        this.sipSecret = password
        this.sipProxy = "sip:$server:5060"
    }

    private fun registerSipUser() {
        try {
            val register = JSONObject()
            register.put("janus", "message")
            register.put("session_id", sessionId)
            register.put("handle_id", handleId)
            register.put("transaction", generateTransactionId())

            val body = JSONObject()
            body.put("request", "register")
            body.put("username", sipUsername)
            body.put("authuser", sipAuthUser)
            body.put("display_name", sipDisplayName)
            body.put("secret", sipSecret)
            body.put("proxy", sipProxy)

            register.put("body", body)
            send(register.toString())
            Log.d(TAG, "Sent SIP register request for: $sipUsername")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating register request", e)
            listener.onJanusError("Error registering: ${e.message}")
        }
    }

    fun unregister() {
        try {
            val unregister = JSONObject()
            unregister.put("janus", "message")
            unregister.put("session_id", sessionId)
            unregister.put("handle_id", handleId)
            unregister.put("transaction", generateTransactionId())

            val body = JSONObject()
            body.put("request", "unregister")

            unregister.put("body", body)
            send(unregister.toString())
            Log.d(TAG, "Sent SIP unregister request")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating unregister request", e)
            listener.onJanusError("Error unregistering: ${e.message}")
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

    fun accept(jsep: JSONObject) {
        try {
            val accept = JSONObject()
            accept.put("janus", "message")
            accept.put("session_id", sessionId)
            accept.put("handle_id", handleId)
            accept.put("transaction", generateTransactionId())

            val body = JSONObject()
            body.put("request", "accept")

            accept.put("body", body)
            accept.put("jsep", jsep)

            send(accept.toString())
            Log.d(TAG, "Sent SIP accept request")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating accept request", e)
            listener.onJanusError("Error accepting call: ${e.message}")
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
            Log.d(TAG, "Sent SIP hangup request")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating hangup request", e)
            listener.onJanusError("Error hanging up: ${e.message}")
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

    fun sendMessage(message: String) {
        if (isOpen) {
            Log.d(TAG, "Sending message: $message")
            send(message)
        } else {
            Log.w(TAG, "WebSocket is not connected, cannot send message: $message")
            listener.onJanusError("WebSocket is not connected, cannot send message.")
        }
    }
}