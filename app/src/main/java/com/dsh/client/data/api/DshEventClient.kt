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
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
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
            // connectOnce 挂起直到连接建立或失败；连接成功后继续挂起直到断线
            com.dsh.client.data.debug.DebugLog.d("WS", "connecting $url")
            val connected = connectOnce(request)
            if (!connected && !shouldReconnect.get()) break
            if (connected) {
                attempt = 0
                com.dsh.client.data.debug.DebugLog.i("WS", "connected (attempt=$attempt)")
                isConnected.set(true)
                // 等待断线（connectOnce 在 onClosed/onFailure 时返回）
                com.dsh.client.data.debug.DebugLog.d("WS", "disconnected, scheduling reconnect")
                if (!shouldReconnect.get()) break
                delay(calculateBackoff(attempt))
            } else {
                com.dsh.client.data.debug.DebugLog.w("WS", "connect failed, retry in ${calculateBackoff(attempt)}ms")
                if (shouldReconnect.get()) delay(calculateBackoff(attempt))
            }
        }
    }

    private suspend fun connectOnce(request: Request): Boolean =
        suspendCancellableCoroutine { continuation ->
            // opened=true 表示连接已建立；established 防止重复 resume(true)（断线后的首次 resume）
            val opened = AtomicBoolean(false)
            val established = AtomicBoolean(false)
            val wsListener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    currentWebSocket.set(webSocket)
                    isConnected.set(true)
                    opened.set(true)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val frame = parseFrame(text)
                        if (frame == null) {
                            com.dsh.client.data.debug.DebugLog.w("WS", "unparsed frame: ${text.take(200)}")
                        } else {
                            _frames.tryEmit(frame)
                        }
                    } catch (e: Exception) {
                        com.dsh.client.data.debug.DebugLog.e("WS", "frame parse error: ${e.message}: ${text.take(200)}")
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, "client closing")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected.set(false)
                    currentWebSocket.compareAndSet(webSocket, null)
                    // 连接已建立过则此处代表正常断线 → resume(true) 触发重连
                    if (opened.get() && established.compareAndSet(false, true)) continuation.resume(true)
                    // 连接从未建立（onOpen 未发生）但 closed 到达 — 视为失败
                    else if (!opened.get() && established.compareAndSet(false, true)) continuation.resume(false)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected.set(false)
                    currentWebSocket.compareAndSet(webSocket, null)
                    // 连接建立过 → 断线重连；未建立 → 初始失败
                    if (established.compareAndSet(false, true)) continuation.resume(opened.get())
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
        if ((root["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull != "server-request") return null
        val payload = root["payload"]?.jsonObject ?: return null
        return parseMuxFrame(payload)
    }

    private fun parseMuxFrame(payload: JsonObject): RpcModels.MuxFrame? {
        val frameType = (payload["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return null
        val sessionId = (payload["sessionId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
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
                lastSeq = (payload["lastSeq"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0
            )
            "approval/requested" -> RpcModels.MuxFrame.ApprovalRequested(
                sessionId = sessionId ?: return null,
                approvalId = (payload["approvalId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
                toolName = (payload["toolName"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
                callId = (payload["callId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
                reason = (payload["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            )
            "approval/resolved" -> RpcModels.MuxFrame.ApprovalResolved(
                sessionId = sessionId ?: return null,
                approvalId = (payload["approvalId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
                outcome = (payload["outcome"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
            )
            "question/requested" -> RpcModels.MuxFrame.QuestionRequested(
                sessionId = sessionId ?: return null,
                questions = payload["questions"]?.jsonArray?.mapNotNull { it.jsonObject } ?: emptyList()
            )
            "question/resolved" -> RpcModels.MuxFrame.QuestionResolved(
                sessionId = sessionId ?: return null,
                questionRpcId = (payload["questionRpcId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
                outcome = (payload["outcome"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
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
                key = (payload["key"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
                value = payload["value"] ?: JsonNull,
                seq = (payload["seq"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0
            )
            "stream/error" -> {
                val err = payload["error"]?.jsonObject
                RpcModels.MuxFrame.StreamError(
                    error = RpcModels.RpcError(
                        code = (err?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "internal",
                        message = (err?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
                        details = err?.get("details")?.jsonObject
                    )
                )
            }
            else -> null
        }
    }
}
