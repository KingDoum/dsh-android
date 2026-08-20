package com.dsh.client.data.api

import com.dsh.client.domain.model.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * High-level typed API for DSH backend operations.
 * Wraps [DshRpcClient] for unary RPC methods and [DshEventClient] for the
 * real-time mux event stream.
 */
class DshApi(
    private val rpcClient: DshRpcClient,
    private val eventClient: DshEventClient
) {
    private val json get() = rpcClient.json

    // ── Session operations ──────────────────────────────────────────────────

    /**
     * List all persisted sessions.
     * RPC: session.list
     */
    suspend fun listSessions(): List<SessionSummary> {
        val response = rpcClient.call<SessionListWire>("session.list")
        return response.items.map { wire ->
            SessionSummary(
                sessionId = wire.sessionId,
                title = extractTitle(wire.projections),
                updatedAt = wire.updatedAt,
                running = wire.running,
                blank = wire.blank,
                agentPreset = wire.agentPreset,
                lastMessagePreview = null
            )
        }
    }

    /**
     * Create a new session.
     * RPC: session.create
     */
    suspend fun createSession(workspaceId: String? = null): CreateSessionResult {
        val payload = buildJsonObject {
            workspaceId?.let { put("workspaceId", it) }
        }
        return rpcClient.call<CreateSessionResult>("session.create", payload)
    }

    /**
     * Read a window of history events.
     * RPC: session.history
     */
    suspend fun getHistory(
        sessionId: String,
        beforeSeq: Int? = null,
        maxMessages: Int? = null
    ): HistoryResult {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            beforeSeq?.let { put("beforeSeq", it) }
            maxMessages?.let { put("maxMessages", it) }
        }
        val response = rpcClient.call<HistoryWire>("session.history", payload)
        return HistoryResult(
            events = response.events.map { entry ->
                SessionEventParser.parse(entry.event.toJsonObject())
            },
            hasMore = response.hasMore,
            projections = response.projections
        )
    }

    /**
     * Send a text message to a session.
     * RPC: session.prompt with mode: "queue"
     */
    suspend fun sendMessage(sessionId: String, content: String): SendMessageResult {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("mode", "queue")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", content)
                }
            }
        }
        return rpcClient.call<SendMessageResult>("session.prompt", payload)
    }

    /**
     * Cancel a session's active turn.
     * RPC: session.cancel
     */
    suspend fun cancelSession(sessionId: String) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
        }
        rpcClient.callUnit("session.cancel", payload)
    }

    /**
     * Rename a session.
     * RPC: session.rename
     */
    /** Extract session title from projections.values.title (null-safe). */
    private fun extractTitle(projections: JsonElement?): String {
        return try {
            if (projections == null || projections !is kotlinx.serialization.json.JsonObject) return ""
            val values = projections["values"]?.jsonObject ?: return ""
            values["title"]?.jsonPrimitive?.contentOrNull ?: ""
        } catch (_: Exception) { "" }
    }

    suspend fun renameSession(sessionId: String, title: String) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("title", title)
        }
        rpcClient.callUnit("session.rename", payload)
    }

    // ── Event stream ────────────────────────────────────────────────────────

    /**
     * Observable stream of mux frames from the WebSocket connection.
     * Call [DshEventClient.connect] first to establish the connection.
     */
    fun events(): Flow<RpcModels.MuxFrame> = eventClient.frames
}

// ── Wire DTOs (private to this file) ────────────────────────────────────────

/** session.list response wire shape. */
@Serializable
private data class SessionListWire(
    @SerialName("items") val items: List<SessionSummaryWire>
)

/** One session.list item wire shape. */
@Serializable
private data class SessionSummaryWire(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("updatedAt") val updatedAt: Long,
    @SerialName("running") val running: Boolean,
    @SerialName("blank") val blank: Boolean,
    @SerialName("parentSessionId") val parentSessionId: String? = null,
    @SerialName("origin") val origin: String? = null,
    @SerialName("cwd") val cwd: String? = null,
    @SerialName("agentPreset") val agentPreset: String? = null,
    @SerialName("projections") val projections: JsonElement? = null
)

/** session.history response wire shape. */
@Serializable
private data class HistoryWire(
    @SerialName("events") val events: List<HistoryEntryWire>,
    @SerialName("hasMore") val hasMore: Boolean,
    @SerialName("projections") val projections: JsonElement? = null
)

/** One history entry wire shape. */
@Serializable
private data class HistoryEntryWire(
    @SerialName("event") val event: SessionEventWire,
    @SerialName("view") val view: JsonObject? = null
)

/** Session event envelope wire shape. */
@Serializable
data class SessionEventWire(
    @SerialName("type") val type: String,
    @SerialName("data") val data: JsonElement? = null,
    @SerialName("seq") val seq: Int,
    @SerialName("time") val time: Long
) {
    /** Convert this envelope into a JsonObject for SessionEventParser. */
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("type", type)
        data?.let { put("data", it) }
        put("seq", seq)
        put("time", time)
    }
}

// ── Public response DTOs ────────────────────────────────────────────────────

/** Result of a session.create RPC call. */
@Serializable
data class CreateSessionResult(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("agentPreset") val agentPreset: String? = null
)

/** Result of a session.prompt RPC call. */
@Serializable
data class SendMessageResult(
    @SerialName("accepted") val accepted: Boolean,
    @SerialName("command") val command: JsonElement? = null
)
