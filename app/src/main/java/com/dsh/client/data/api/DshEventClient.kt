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
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _events = MutableSharedFlow<MuxFrame>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<MuxFrame> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var connectingJob: Job? = null
    private var scope: CoroutineScope? = null

    fun connect(scope: CoroutineScope) {
        this.scope = scope
        connectingJob = scope.launch {
            connectWithRetry()
        }
    }

    private suspend fun connectWithRetry() {
        var retryCount = 0
        while (isActive) {
            try {
                connectOnce()
                // If connection drops, reset retry
                retryCount = 0
            } catch (e: Exception) {
                if (!isActive) break
                retryCount++
                val delay = minOf(1000L * (1 shl minOf(retryCount, 5)), 30_000L)
                delay(delay)
            }
        }
    }

    private fun connectOnce() {
        val url = "${serverUrlProvider().trimEnd('/')}/events.mux"
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .build()

        val latch = CompletableDeferred<Unit>()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                latch.complete(Unit)
                // Send subscribe request
                val subscribeJson = buildJsonObject {
                    put("type", "client-request")
                    put("rpcId", java.util.UUID.randomUUID().toString())
                    put("method", "events.mux")
                    put("payload", buildJsonObject {
                        put("since", buildJsonObject {})
                    })
                }
                webSocket.send(json.encodeToString(JsonElement.serializer(), subscribeJson))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val element = json.parseToJsonElement(text)
                    val obj = element.jsonObject
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return

                    when (type) {
                        "server-response" -> {
                            // Response to our subscription request
                        }
                        "server-request" -> {
                            val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
                            val payload = obj["payload"]?.jsonObject ?: return
                            val rpcId = obj["rpcId"]?.jsonPrimitive?.contentOrNull ?: return

                            when (method) {
                                "events.mux" -> {
                                    val frameType = payload["type"]?.jsonPrimitive?.contentOrNull ?: return
                                    val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull
                                    val eventData = payload["event"]

                                    when (frameType) {
                                        "session/event" -> {
                                            if (sessionId != null && eventData != null) {
                                                val event = json.decodeFromJsonElement(
                                                    SessionEventData.serializer(), eventData
                                                )
                                                _events.tryEmit(MuxFrame.SessionEvent(
                                                    type = "session/event",
                                                    sessionId = sessionId,
                                                    event = event
                                                ))
                                            }
                                        }
                                        "session/subscribed" -> {
                                            val lastSeq = payload["lastSeq"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                                            if (sessionId != null) {
                                                _events.tryEmit(MuxFrame.SessionSubscribed(
                                                    sessionId = sessionId,
                                                    lastSeq = lastSeq
                                                ))
                                            }
                                        }
                                        "session/queue" -> {
                                            val items = payload["items"]?.jsonArray ?: JsonArray(emptyList())
                                            if (sessionId != null) {
                                                _events.tryEmit(MuxFrame.SessionQueue(
                                                    sessionId = sessionId,
                                                    items = items.toList()
                                                ))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Parse error, skip frame
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!latch.isCompleted) {
                    latch.completeExceptionally(t ?: Exception("WebSocket connection failed"))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Will reconnect via retry loop
            }
        })

        // Wait for connection or failure
        runBlocking { latch.await() }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
        connectingJob?.cancel()
        connectingJob = null
    }
}
