package com.dsh.client.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * HTTP RPC client for DSH's JSON-RPC-style API.
 */
class DshRpcClient(
    @PublishedApi internal val apiBaseProvider: () -> String
) {
    @PublishedApi internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @PublishedApi internal val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @PublishedApi internal val jsonMediaType = "application/json".toMediaType()

    @PublishedApi internal fun apiUrl(method: String): String {
        val base = apiBaseProvider().trimEnd('/')
        return "$base/$method"
    }

    suspend inline fun <reified T> call(
        method: String,
        payload: JsonElement? = null
    ): T = withContext(Dispatchers.IO) {
        val rpcId = UUID.randomUUID().toString()
        val requestBody = RpcModels.RpcRequest(
            rpcId = rpcId,
            method = method,
            payload = payload
        )
        val bodyJson = httpClient.executeRequest(
            Request.Builder()
                .url(apiUrl(method))
                .post(json.encodeToJsonElement(requestBody).toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .build()
        )
        val serverResponse = json.decodeFromString<RpcModels.RpcResponse>(bodyJson)
        val result = serverResponse.result

        if (result.ok) {
            val value = result.value
            if (value != null) {
                json.decodeFromJsonElement(value)
            } else {
                error("Unexpected null value in successful RPC response for $method")
            }
        } else {
            val err = result.error ?: RpcModels.RpcError("unknown", "No error details", null)
            throw RpcException(err)
        }
    }

    suspend fun callUnit(method: String, payload: JsonElement? = null) {
        call<JsonElement>(method, payload)
    }
}

