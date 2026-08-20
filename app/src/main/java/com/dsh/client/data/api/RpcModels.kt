package com.dsh.client.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// ═══════════════════════════════════════════════════════════════════════════════
// Wire-protocol models, stream frame types, shared event parser, and exceptions
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Container for DSH wire-protocol models and stream frame types.
 *
 * - [RpcRequest] / [RpcResponse] / [RpcResult] / [RpcError] are serializable
 *   DTOs used by the HTTP RPC client.
 * - [MuxFrame] / [HostFrame] are sealed class hierarchies for WebSocket stream
 *   frames, constructed manually from parsed JSON.
 * - [SessionEventData] is a UI-friendly flattening of a session event envelope.
 */
object RpcModels {

    // ── HTTP RPC wire types ─────────────────────────────────────────────────

    /** RPC request envelope — wire carrier: POST /api/<method> body. */
    @Serializable
    data class RpcRequest(
        @SerialName("type") val type: String = "client-request",
        @SerialName("rpcId") val rpcId: String,
        @SerialName("method") val method: String,
        @SerialName("payload") val payload: JsonElement? = null
    )

    /** RPC response envelope — wire carrier: the HTTP response body. */
    @Serializable
    data class RpcResponse(
        @SerialName("type") val type: String = "server-response",
        @SerialName("rpcId") val rpcId: String,
        @SerialName("result") val result: RpcResult
    )

    /** Business success/failure result. */
    @Serializable
    data class RpcResult(
        @SerialName("ok") val ok: Boolean,
        @SerialName("value") val value: JsonElement? = null,
        @SerialName("error") val error: RpcError? = null
    )

    /** Structured RPC error with closed error-code union. */
    @Serializable
    data class RpcError(
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
        @SerialName("details") val details: JsonObject? = null
    )

    // ── Mux frame types (events.mux WebSocket stream) ───────────────────────

    /**
     * Sealed frame hierarchy for the /api/events.mux downlink stream.
     * Each subtype represents one server-to-client push event.
     */
    sealed class MuxFrame {

        /** Raw session event pushed to the client. */
        data class SessionEvent(
            val sessionId: String,
            val event: SessionEventData,
            val view: JsonObject? = null
        ) : MuxFrame()

        /** Subscription baseline emitted on open for every attached session. */
        data class SessionSubscribed(
            val sessionId: String,
            val lastSeq: Int
        ) : MuxFrame()

        /** A tool approval was requested. */
        data class ApprovalRequested(
            val sessionId: String,
            val approvalId: String,
            val toolName: String,
            val callId: String? = null,
            val reason: String? = null
        ) : MuxFrame()

        /** A tool approval was resolved. */
        data class ApprovalResolved(
            val sessionId: String,
            val approvalId: String,
            val outcome: String
        ) : MuxFrame()

        /** A question was posed to the user. */
        data class QuestionRequested(
            val sessionId: String,
            val questions: List<JsonObject>
        ) : MuxFrame()

        /** A question was resolved. */
        data class QuestionResolved(
            val sessionId: String,
            val questionRpcId: String,
            val outcome: String
        ) : MuxFrame()

        /** Complete transient inbox state snapshot. */
        data class SessionQueue(
            val sessionId: String,
            val items: List<JsonObject>
        ) : MuxFrame()

        /** Background jobs snapshot for this session. */
        data class SessionJobs(
            val sessionId: String,
            val jobs: List<JsonObject>
        ) : MuxFrame()

        /** One projection unit's value changed. */
        data class SessionProjection(
            val sessionId: String,
            val key: String,
            val value: JsonElement,
            val seq: Int
        ) : MuxFrame()

        /** Stream error. */
        data class StreamError(
            val error: RpcError
        ) : MuxFrame()
    }

    // ── Host frame types (events.host WebSocket stream) ─────────────────────

    /** Sealed frame hierarchy for the /api/events.host downlink stream. */
    sealed class HostFrame {

        /** A new session was added. */
        data class SessionAdded(
            val sessionId: String,
            val blank: Boolean,
            val parentSessionId: String? = null,
            val origin: String? = null,
            val cwd: String? = null,
            val agentPreset: String? = null
        ) : HostFrame()

        /** A session was removed. */
        data class SessionRemoved(
            val sessionId: String
        ) : HostFrame()

        /** Running status changed. */
        data class SessionStatus(
            val sessionId: String,
            val running: Boolean
        ) : HostFrame()

        /** Agent error with no turn position. */
        data class AgentError(
            val sessionId: String,
            val message: String
        ) : HostFrame()

        /** Workspace state changed. */
        data class WorkspaceChanged(
            val workspace: JsonObject
        ) : HostFrame()

        /** Workspace removed. */
        data class WorkspaceRemoved(
            val workspaceId: String
        ) : HostFrame()

        /** Stream error. */
        data class StreamError(
            val error: RpcError
        ) : HostFrame()
    }

    // ── Session event data (UI-friendly view) ───────────────────────────────

    /**
     * Flattened, UI-friendly view of a session event.
     *
     * Constructed from the raw JSON event envelope. The [content] field
     * carries the message body and can be a plain String or a List<Map>
     * (content blocks) depending on the event type.
     */
    data class SessionEventData(
        /** The event type discriminator (e.g. "user/message", "assistant/message", "session/title"). */
        val eventType: String,
        /** Optional message identifier (seq-based when absent). */
        val id: String? = null,
        /** Event sequence number in the session log. */
        val seq: Int,
        /** Event timestamp (epoch millis). */
        val timestamp: Long,
        /** Message content — plain text string or list of content blocks. */
        val content: Any? = null,
        /** Session title (only for session/title events). */
        val title: String? = null,
        /** Whether this is a partial/streaming message (only for assistant/chunk events). */
        val isPartial: Boolean? = null
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// Shared session event parser
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Shared parser: flattens a raw session event envelope JSON object into a
 * UI-friendly [RpcModels.SessionEventData].
 *
 * Usage:
 * ```kotlin
 * val eventData = SessionEventParser.parse(eventObj)
 * ```
 */
object SessionEventParser {

    /**
     * Parse a session event envelope into [RpcModels.SessionEventData].
     *
     * @param eventObj  The JSON object of the session event envelope containing
     *   `type`, `data`, `seq`, and `time` fields.
     * @return A flattened [RpcModels.SessionEventData] for UI consumption.
     */
    fun parse(eventObj: JsonObject): RpcModels.SessionEventData {
        val eventType = eventObj["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val seq = eventObj["seq"]?.jsonPrimitive?.intOrNull ?: 0
        val time = eventObj["time"]?.jsonPrimitive?.longOrNull
            ?: System.currentTimeMillis()
        val data = eventObj["data"]?.jsonObject ?: JsonObject(emptyMap())

        var id: String? = null
        var content: Any? = null
        var title: String? = null
        var isPartial: Boolean? = null

        when (eventType) {
            "user/message" -> {
                id = data["id"]?.jsonPrimitive?.contentOrNull
                content = data["content"]?.let { jsonContentToAny(it) }
            }

            "assistant/message" -> {
                val message = data["message"]?.jsonObject
                id = message?.get("id")?.jsonPrimitive?.contentOrNull
                content = message?.get("content")?.let { jsonContentToAny(it) }
                isPartial = data["interrupted"]?.jsonPrimitive?.booleanOrNull
                    ?: false
            }

            "assistant/chunk" -> {
                isPartial = true
            }

            "session/title" -> {
                title = data["title"]?.jsonPrimitive?.contentOrNull
            }

            "tool/call" -> {
                id = data["callId"]?.jsonPrimitive?.contentOrNull
            }

            "tool/result" -> {
                id = data["callId"]?.jsonPrimitive?.contentOrNull
            }
        }

        return RpcModels.SessionEventData(
            eventType = eventType,
            id = id,
            seq = seq,
            timestamp = time,
            content = content,
            title = title,
            isPartial = isPartial
        )
    }

    /**
     * Convert a JSON content field into the ViewModel-friendly representation:
     * - JsonPrimitive string  → String
     * - JsonArray of blocks   → List<Map<String, Any?>>
     * - JsonObject            → Map<String, Any?>
     * - anything else         → null
     */
    private fun jsonContentToAny(element: JsonElement): Any? {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.mapNotNull { block ->
                when (block) {
                    is JsonObject -> jsonObjectToMap(block)
                    is JsonPrimitive -> block.contentOrNull
                    else -> null
                }
            }
            is JsonObject -> jsonObjectToMap(element)
            JsonNull -> null
        }
    }

    /** Recursively convert a JsonObject into a Map<String, Any?>. */
    private fun jsonObjectToMap(obj: JsonObject): Map<String, Any?> {
        return obj.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> value.contentOrNull ?: ""
                is JsonArray -> value.map { v ->
                    when (v) {
                        is JsonObject -> jsonObjectToMap(v)
                        is JsonPrimitive -> v.contentOrNull ?: ""
                        else -> null
                    }
                }
                is JsonObject -> jsonObjectToMap(value)
                JsonNull -> null
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Top-level response DTOs and exceptions
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Result of a session.history RPC call.
 */
data class HistoryResult(
    val events: List<RpcModels.SessionEventData>,
    val hasMore: Boolean,
    val projections: JsonElement? = null
)

/**
 * One history page entry: raw event plus optional host-computed render intent.
 */
data class HistoryEntry(
    val event: RpcModels.SessionEventData,
    val view: JsonObject? = null
)

// ── Exceptions ──────────────────────────────────────────────────────────────

/**
 * Thrown when an RPC call returns a non-ok result.
 */
class RpcException(val error: RpcModels.RpcError) :
    Exception("RPC error [${error.code}]: ${error.message}")

/**
 * Thrown on transport-level failures (network, timeout, etc.).
 */
class TransportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
