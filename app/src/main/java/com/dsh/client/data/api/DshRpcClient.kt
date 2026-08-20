package com.dsh.client.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP RPC client for DSH's /api/<method> protocol.
 *
 * Uses OkHttp to POST JSON-serialized [RpcModels.RpcRequest] frames and parse
 * [RpcModels.RpcResponse] frames. Each call gets a fresh rpcId (UUID).
 *
 * @param serverUrlProvider  A lambda that returns the current server base URL
 *   (e.g. "http://10.0.2.2:3080"). Evaluated on every call.
 */
class DshRpcClient(
    private val serverUrlProvider: () -> String
) {
    /** Shared JSON instance: lenient, ignore unknown keys for forward compatibility. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Build the full URL for one RPC method.
     *
     * The [serverUrlProvider] may return either the bare server URL
     * ("http://host:3080") or the /api base ("http://host:3080/api");
     * both conventions are normalized.
     */
    private fun apiUrl(method: String): String {
        val base = serverUrlProvider().trimEnd('/')
        val effectiveBase = if (base.endsWith("/api")) base else "$base/api"
        return "$effectiveBase/$method"
    }

    /**
     * Execute an RPC call and return the decoded result.
     *
     * @param T       The expected result type (must be @Serializable).
     * @param method  The RPC method name, e.g. "session.list", "session.create".
     * @param payload Optional JSON payload.
     * @return The decoded [T] value from the successful response.
     * @throws RpcException      If the server returned a non-ok result.
     * @throws TransportException On network/IO errors.
     */
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
        val bodyJson = json.encodeToJsonElement(requestBody).toString()
            .toRequestBody(jsonMediaType)

        val httpRequest = Request.Builder()
            .url(apiUrl(method))
            .post(bodyJson)
            .header("Content-Type", "application/json")
            .build()

        val responseBody = httpClient.executeRequest(httpRequest)
        val serverResponse = json.decodeFromString<RpcModels.RpcResponse>(responseBody)
        val result = serverResponse.result

        if (result.ok) {
            if (result.value != null) {
                json.decodeFromJsonElement<T>(result.value)
            } else {
                error("Unexpected null value in successful RPC response for $method")
            }
        } else {
            val error = result.error
                ?: RpcModels.RpcError("unknown", "No error details in response", null)
            throw RpcException(error)
        }
    }

    /**
     * Execute an RPC call that returns no meaningful result (Unit).
     */
    suspend fun callUnit(
        method: String,
        payload: JsonElement? = null
    ) = withContext(Dispatchers.IO) {
        val rpcId = UUID.randomUUID().toString()
        val requestBody = RpcModels.RpcRequest(
            rpcId = rpcId,
            method = method,
            payload = payload
        )
        val bodyJson = json.encodeToJsonElement(requestBody).toString()
            .toRequestBody(jsonMediaType)

        val httpRequest = Request.Builder()
            .url(apiUrl(method))
            .post(bodyJson)
            .header("Content-Type", "application/json")
            .build()

        val responseBody = httpClient.executeRequest(httpRequest)
        val serverResponse = json.decodeFromString<RpcModels.RpcResponse>(responseBody)
        val result = serverResponse.result

        if (!result.ok) {
            val error = result.error
                ?: RpcModels.RpcError("unknown", "No error details in response", null)
            throw RpcException(error)
        }
    }

    /**
     * Execute an OkHttp request as a suspend function.
     */
    private suspend fun OkHttpClient.executeRequest(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        continuation.resume(body)
                    } else {
                        val status = response.code
                        val detail = body ?: "no body"
                        continuation.resumeWithException(
                            TransportException(
                                "HTTP $status for ${request.url}: $detail"
                            )
                        )
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            TransportException("Network error: ${e.message}", e)
                        )
                    }
                }
            })
        }
}
