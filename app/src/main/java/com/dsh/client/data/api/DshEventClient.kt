package com.dsh.client.data.api

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class DshEventClient(
    private val serverUrlProvider: () -> String
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _events = MutableSharedFlow<MuxFrame>(replay = 0, extraBufferCapacity = 128)
    val events: SharedFlow<MuxFrame> = _events.asSharedFlow()

    private var ws: WebSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            var retries = 0
            while (isActive) {
                try {
                    connect()
                    retries = 0
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    retries++
                    val delay = minOf(1000L * (1 shl minOf(retries, 5)), 30_000L)
                    delay(delay)
                }
            }
        }
    }

    fun stop() {
        ws?.close(1000, "client closing")
        ws = null
        job?.cancel()
        job = null
    }

    private fun connect() {
        val request = Request.Builder()
            .url("${serverUrlProvider().trimEnd('/')}/events.mux")
            .build()

        val connected = CompletableDeferred<Unit>()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected.complete(Unit)
                // Subscribe
                val sub = buildJsonObject {
                    put("type", "client-request")
                    put("rpcId", java.util.UUID.randomUUID().toString())
                    put("method", "events.mux")
                    put("payload", buildJsonObject {})
                }
                ws.send(json.encodeToString(JsonElement.serializer(), sub))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val obj = json.parseToJsonElement(text).jsonObject
                    val msgType = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
                    if (msgType != "server-request") return

                    val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
                    if (method != "events.mux") return

                    val payload = obj["payload"]?.jsonObject ?: return
                    val frameType = payload["type"]?.jsonPrimitive?.contentOrNull ?: return
                    val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull

                    when (frameType) {
                        "session/event" -> {
                            val eventData = payload["event"] ?: return
                            val event = json.decodeFromJsonElement(SessionEventWire.serializer(), eventData)
                            if (sessionId != null) {
                                _events.tryEmit(MuxFrame.SessionEvent(sessionId, event))
                            }
                        }
                        "session/subscribed" -> {
                            val lastSeq = payload["lastSeq"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                            if (sessionId != null) {
                                _events.tryEmit(MuxFrame.SessionSubscribed(sessionId, lastSeq))
                            }
                        }
                        "session/queue" -> {
                            if (sessionId != null) {
                                _events.tryEmit(MuxFrame.SessionQueue(sessionId))
                            }
                        }
                        "stream/error" -> {
                            val msg = payload["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "unknown"
                            _events.tryEmit(MuxFrame.StreamError(msg))
                        }
                    }
                } catch (_: Exception) { /* skip unparseable frame */ }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(t)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                // Will reconnect via retry loop
            }
        })

        // Block until connected or failed
        runBlocking { connected.await() }
    }
}
