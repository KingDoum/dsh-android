package com.dsh.client.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ---- Wire protocol ----

@Serializable
data class RpcRequest(
    val type: String = "client-request",
    val rpcId: String = "",
    val method: String = "",
    val payload: JsonElement? = null
)

@Serializable
data class RpcResponse(
    val type: String = "",
    val rpcId: String = "",
    val result: RpcResult = RpcResult()
)

@Serializable
data class RpcResult(
    val ok: Boolean = false,
    val value: JsonElement? = null,
    val error: RpcError? = null
)

@Serializable
data class RpcError(
    val code: String = "",
    val message: String = "",
    val details: JsonElement? = null
)

// ---- Session domain ----

@Serializable
data class SessionSummaryWire(
    val sessionId: String = "",
    val updatedAt: Long = 0,
    val running: Boolean = false,
    val blank: Boolean = true,
    val agentPreset: String? = null,
    val projections: ProjectionsWire? = null
)

@Serializable
data class SessionListWire(
    val items: List<SessionSummaryWire> = emptyList()
)

@Serializable
data class ProjectionsWire(
    val asOfSeq: Int = 0,
    val values: ProjectionValuesWire? = null
)

@Serializable
data class ProjectionValuesWire(
    val sessionListMetadata: SessionListMetaWire? = null
)

@Serializable
data class SessionListMetaWire(
    val blank: Boolean = true,
    val lastPromptAt: Long? = null
)

// ---- Session ops ----

@Serializable
data class CreateSessionWire(
    val sessionId: String = "",
    val agentPreset: String? = null
)

@Serializable
data class HistoryEntryWire(
    val event: SessionEventWire = SessionEventWire(),
    val view: JsonElement? = null
)

@Serializable
data class HistoryWire(
    val events: List<HistoryEntryWire> = emptyList(),
    val hasMore: Boolean = false
)

@Serializable
data class SessionEventWire(
    val type: String = "",
    val seq: Int = 0,
    val id: String? = null,
    val timestamp: Long = 0,
    val content: JsonElement? = null,
    val source: JsonElement? = null,
    val isPartial: Boolean? = null,
    val title: String? = null
)

@Serializable
data class PromptWire(
    val accepted: Boolean = false,
    val command: JsonElement? = null
)

// ---- Event stream frames (WebSocket) ----

sealed class MuxFrame {
    data class SessionEvent(
        val sessionId: String,
        val event: SessionEventWire
    ) : MuxFrame()

    data class SessionSubscribed(
        val sessionId: String,
        val lastSeq: Int
    ) : MuxFrame()

    data class SessionQueue(
        val sessionId: String
    ) : MuxFrame()

    data class StreamError(
        val message: String
    ) : MuxFrame()

    object Unknown : MuxFrame()
}

// ---- Simple results ----

data class HistoryResult(
    val events: List<SessionEventWire>,
    val hasMore: Boolean = false
)

data class CreateSessionResult(
    val sessionId: String,
    val agentPreset: String? = null
)

data class SendMessageResult(
    val accepted: Boolean = false
)
