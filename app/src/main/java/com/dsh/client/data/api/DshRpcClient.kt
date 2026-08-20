package com.dsh.client.data.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class DshRpcClient(
    private val apiBaseProvider: () -> String
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json".toMediaType()

    /** Generic RPC call. `payload` must be a JsonElement (use buildJsonObject etc.). */
    private suspend fun rpc(method: String, payload: JsonObject?): JsonElement {
        val body = RpcRequest(
            rpcId = UUID.randomUUID().toString(),
            method = method,
            payload = payload
        )
        val jsonBody = json.encodeToString(RpcRequest.serializer(), body)
        val request = Request.Builder()
            .url("${apiBaseProvider().trimEnd('/')}/$method")
            .post(jsonBody.toRequestBody(mediaType))
            .build()

        val response = awaitCall(client, request)
        val text = response.body?.string() ?: ""
        response.close()

        val parsed = try {
            json.decodeFromString(RpcResponse.serializer(), text)
        } catch (e: SerializationException) {
            throw Exception("协议解析失败: ${e.message}")
        }

        if (parsed.result.ok) {
            return parsed.result.value ?: JsonNull
        }
        val err = parsed.result.error
        throw Exception(err?.message ?: "RPC 错误: ${err?.code ?: "unknown"}")
    }

    suspend fun listSessions(): List<SessionSummaryWire> {
        val value = rpc("session.list", null)
        val wire = json.decodeFromJsonElement(SessionListWire.serializer(), value)
        return wire.items
    }

    suspend fun createSession(workspaceId: String?): CreateSessionWire {
        val payload = buildJsonObject {
            workspaceId?.let { put("workspaceId", it) }
        }
        val value = rpc("session.create", payload)
        return json.decodeFromJsonElement(CreateSessionWire.serializer(), value)
    }

    suspend fun history(sessionId: String, beforeSeq: Int?, maxMessages: Int?): HistoryWire {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            beforeSeq?.let { put("beforeSeq", it) }
            maxMessages?.let { put("maxMessages", it) }
        }
        val value = rpc("session.history", payload)
        return json.decodeFromJsonElement(HistoryWire.serializer(), value)
    }

    suspend fun prompt(sessionId: String, content: String): PromptWire {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("mode", "queue")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content)
                })
            })
        }
        val value = rpc("session.prompt", payload)
        return json.decodeFromJsonElement(PromptWire.serializer(), value)
    }

    suspend fun cancel(sessionId: String) {
        val payload = buildJsonObject { put("sessionId", sessionId) }
        rpc("session.cancel", payload)
    }

    suspend fun rename(sessionId: String, title: String) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("title", title)
        }
        rpc("session.rename", payload)
    }
}

private suspend fun awaitCall(client: OkHttpClient, request: Request): Response =
    suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                if (!cont.isCancelled) cont.resume(response)
            }
        })
    }
