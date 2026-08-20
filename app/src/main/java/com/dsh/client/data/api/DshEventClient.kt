package com.dsh.client.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * WebSocket event stream client for DSH's /api/events.mux downlink.
 *
 * Maintains a persistent WebSocket connection that receives mux frames from
 * the server and emits them through the [frames] SharedFlow as
 * [RpcModels.MuxFrame] subtypes. Automatically reconnects with exponential
 * backoff on disconnection (1s, 2s, 4s, ... up to 30s max).
 *
 * @param serverUrlProvider  A lambda that returns the current server base URL.
 */
class DshEventClient(
    private val serverUrlProvider: () -> String
) {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val _frames = MutableSharedFlow<RpcModels.MuxFrame>(
        replay = 0,
        extraBufferCapacity = 64
    )

    /**
     * Observable stream of [RpcModels.MuxFrame] events from the server.
     */
    val frames: SharedFlow<RpcModels.MuxFrame> = _frames.asSharedFlow()

    private val isConnected = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(true)
    private val currentWebSocket = AtomicReference<WebSocket?>(null)
    private var connectJob: Job? = null

    /**
     * Open the WebSocket connection to /api/events.mux and start emitting
     * frames through the [frames] flow. Reconnection happens automatically;
     * call [disconnect] to stop permanently.
     */
    suspend fun connect() {
        shouldReconnect.set(true)
        connectJob?.cancel()
        connectJob = scope.launch {
            reconnectLoop()
        }
    }

    /**
     * Permanently disconnect the WebSocket and stop reconnecting.
     */
    fun disconnect() {
        shouldReconnect.set(false)
        connectJob?.cancel()
        connectJob = null
        closeCurrentSocket()
        isConnected.set(false)
    }

    private suspend fun reconnectLoop() {
        var attempt = 0
        while (shouldReconnect.get()) {
            attempt++
            val base = serverUrlProvider().trimEnd('/')
            val effectiveBase = if (base.endsWith("/api")) base else "$base/api"
            val url = "$effectiveBase/events.mux"
            val request = Request.Builder().url(url).build()
            val connected = connectOnce(request)
            if (connected) {
                attempt = 0
                isConnected.set(true)
            }
            if (shouldReconnect.get()) {
                delay(calculateBackoff(attempt))
            }
        }
    }

    private suspend fun connectOnce(request: Request): Boolean =
        suspendCancellableCoroutine { continuation ->
            val connected = AtomicBoolean(false)
            val wsListener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    currentWebSocket.set(webSocket)
                    isConnected.set(true)
                    if (connected.compareAndSet(false, true)) continuation.resume(true)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try { parseFrame(text)?.let { _frames.tryEmit(it) } } catch (_: Exception) {}
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, "client closing")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected.set(false)
                    currentWebSocket.compareAndSet(webSocket, null)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected.set(false)
                    currentWebSocket.compareAndSet(webSocket, null)
                    if (connected.compareAndSet(false, true)) continuation.resume(false)
                }
            }
            continuation.invokeOnCancellation { closeCurrentSocket() }
            httpClient.newWebSocket(request, wsListener)
        }

    private fun closeCurrentSocket() {
        currentWebSocket.getAndSet(null)?.close(1000, "client disconnect")
    }

    private fun calculateBackoff(attempt: Int): Long {
        val delay = 1_000L shl (attempt - 1).coerceAtMost(4)
        return delay.coerceAtMost(30_000L)
    }

    private fun parseFrame(text: String): RpcModels.MuxFrame? {
        val root = json.parseToJsonElement(text).jsonObject
        if (root["type"]?.jsonPrimitive?.contentOrNull != "server-request") return null
        val payload = root["payload"]?.jsonObject ?: return null
        return parseMuxFrame(payload)
    }

    private fun parseMuxFrame(payload: JsonObject): RpcModels.MuxFrame? {
        val frameType = payload["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull
        return when (frameType) {
            "session/event" -> {
                val eventObj = payload["event"]?.jsonObject ?: return null
                RpcModels.MuxFrame.SessionEvent(
                    sessionId = sessionId ?: return null,
                    event = SessionEventParser.parse(eventObj),
                    view = payload["view"]?.jsonObject
                )
            }
            "session/subscribed" -> RpcModels.MuxFrame.SessionSubscribed(
                sessionId = sessionId ?: return null,
                lastSeq = payload["lastSeq"]?.jsonPrimitive?.intOrNull ?: 0
            )
            "approval/requested" -> RpcModels.MuxFrame.ApprovalRequested(
                sessionId = sessionId ?: return null,
                approvalId = payload["approvalId"]?.jsonPrimitive?.contentOrNull ?: "",
                toolName = payload["toolName"]?.jsonPrimitive?.contentOrNull ?: "",
                callId = payload["callId"]?.jsonPrimitive?.contentOrNull,
                reason = payload["reason"]?.jsonPrimitive?.contentOrNull
            )
            "approval/resolved" -> RpcModels.MuxFrame.ApprovalResolved(
                sessionId = sessionId ?: return null,
                approvalId = payload["approvalId"]?.jsonPrimitive?.contentOrNull ?: "",
                outcome = payload["outcome"]?.jsonPrimitive?.contentOrNull ?: ""
            )
            "question/requested" -> RpcModels.MuxFrame.QuestionRequested(
                sessionId = sessionId ?: return null,
                questions = payload["questions"]?.jsonArray?.mapNotNull { it.jsonObject } ?: emptyList()
            )
            "question/resolved" -> RpcModels.MuxFrame.QuestionResolved(
                sessionId = sessionId ?: return null,
                questionRpcId = payload["questionRpcId"]?.jsonPrimitive?.contentOrNull ?: "",
                outcome = payload["outcome"]?.jsonPrimitive?.contentOrNull ?: ""
            )
            "session/queue" -> RpcModels.MuxFrame.SessionQueue(
                sessionId = sessionId ?: return null,
                items = payload["items"]?.jsonArray?.mapNotNull { it.jsonObject } ?: emptyList()
            )
            "session/jobs" -> RpcModels.MuxFrame.SessionJobs(
                sessionId = sessionId ?: return null,
                jobs = payload["jobs"]?.jsonArray?.mapNotNull { it.jsonObject } ?: emptyList()
            )
            "session/projection" -> RpcModels.MuxFrame.SessionProjection(
                sessionId = sessionId ?: return null,
                key = payload["key"]?.jsonPrimitive?.contentOrNull ?: "",
                value = payload["value"] ?: JsonNull,
                seq = payload["seq"]?.jsonPrimitive?.intOrNull ?: 0
            )
            "stream/error" -> {
                val err = payload["error"]?.jsonObject
                RpcModels.MuxFrame.StreamError(
                    error = RpcModels.RpcError(
                        code = err?.get("code")?.jsonPrimitive?.contentOrNull ?: "internal",
                        message = err?.get("message")?.jsonPrimitive?.contentOrNull ?: "",
                        details = err?.get("details")?.jsonObject
                    )
                )
            }
            else -> null
        }
    }
}
