package com.dsh.client.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ── RPC message envelope (four-quadrant model) ──────────────────────────────

/**
 * Call initiated by the client — wire carrier: POST /api/<method> body.
 */
@Serializable
data class ClientRequest(
    @SerialName("type") val type: String = "client-request",
    @SerialName("rpcId") val rpcId: String,
    @SerialName("method") val method: String,
    @SerialName("payload") val payload: JsonElement? = null
)

/**
 * Response to a ClientRequest — wire carrier: the HTTP response body of that POST.
 * rpcId echoes the matching request.
 */
@Serializable
data class ServerResponse(
    @SerialName("type") val type: String = "server-response",
    @SerialName("rpcId") val rpcId: String,
    @SerialName("result") val result: RpcResult
)

/**
 * Message initiated by the server — wire carrier: downstream stream frame (WebSocket).
 * Answerable interactions (approval/question requested — stable rpcId, reused on replay)
 * and pure pushes (session/event etc.) share this shape.
 */
@Serializable
data class ServerRequest(
    @SerialName("type") val type: String = "server-request",
    @SerialName("rpcId") val rpcId: String,
    @SerialName("method") val method: String,
    @SerialName("payload") val payload: JsonElement? = null
)

/**
 * Response to a ServerRequest — wire carrier: POST /api/respond body.
 * rpcId echoes the matching request, never minted anew.
 */
@Serializable
data class ClientResponse(
    @SerialName("type") val type: String = "client-response",
    @SerialName("rpcId") val rpcId: String,
    @SerialName("result") val result: RpcResult
)

// ── RPC result / error ──────────────────────────────────────────────────────

/**
 * Business success/failure result. Methods never throw business errors;
 * failures are encoded as [RpcResult.ok] = false with an [RpcError].
 */
@Serializable
data class RpcResult(
    @SerialName("ok") val ok: Boolean,
    @SerialName("value") val value: JsonElement? = null,
    @SerialName("error") val error: RpcError? = null
)

/**
 * Closed error-code union. The [code] is the discriminant; [details] carries
 * per-code typed data.
 */
@Serializable
data class RpcError(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String,
    @SerialName("details") val details: JsonObject? = null
)

// ── Mux frame types (events.mux WebSocket stream) ──────────────────────────

/**
 * Mux stream frames: raw session-event passthrough + control frames +
 * approval/question frames (requested = answerable server-request, the rest are pure pushes).
 */
@Serializable
sealed class MuxFrame {

    /** Raw session event pushed to the client. */
    @Serializable
    @SerialName("session/event")
    data class SessionEvent(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("event") val event: SessionEventEnvelope,
        @SerialName("view") val view: ToolEventView? = null
    ) : MuxFrame()

    /** Subscription baseline emitted on open for every attached session. */
    @Serializable
    @SerialName("session/subscribed")
    data class Subscribed(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("lastSeq") val lastSeq: Int
    ) : MuxFrame()

    /** A tool approval was requested (answerable server-request). */
    @Serializable
    @SerialName("approval/requested")
    data class ApprovalRequested(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("approvalId") val approvalId: String,
        @SerialName("toolName") val toolName: String,
        @SerialName("callId") val callId: String? = null,
        @SerialName("reason") val reason: String? = null
    ) : MuxFrame()

    /** A tool approval was resolved. */
    @Serializable
    @SerialName("approval/resolved")
    data class ApprovalResolved(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("approvalId") val approvalId: String,
        @SerialName("outcome") val outcome: String
    ) : MuxFrame()

    /** A question was posed to the user (answerable server-request). */
    @Serializable
    @SerialName("question/requested")
    data class QuestionRequested(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("questions") val questions: List<JsonObject>
    ) : MuxFrame()

    /** A question was resolved. */
    @Serializable
    @SerialName("question/resolved")
    data class QuestionResolved(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("questionRpcId") val questionRpcId: String,
        @SerialName("outcome") val outcome: String
    ) : MuxFrame()

    /** Complete transient inbox state after every enqueue/mutation/claim/discard. */
    @Serializable
    @SerialName("session/queue")
    data class SessionQueue(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("items") val items: List<JsonObject>
    ) : MuxFrame()

    /** Complete set of background jobs for this session. */
    @Serializable
    @SerialName("session/jobs")
    data class SessionJobs(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("jobs") val jobs: List<JsonObject>
    ) : MuxFrame()

    /** One projection unit's finished value changed. */
    @Serializable
    @SerialName("session/projection")
    data class SessionProjection(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("key") val key: String,
        @SerialName("value") val value: JsonElement,
        @SerialName("seq") val seq: Int
    ) : MuxFrame()

    /** Stream error. */
    @Serializable
    @SerialName("stream/error")
    data class StreamError(
        @SerialName("error") val error: RpcError
    ) : MuxFrame()
}

// ── Host frame types (events.host WebSocket stream) ─────────────────────────

/**
 * Host stream frames: session create/destroy, running-status flips, and
 * agent failures with no turn position.
 */
@Serializable
sealed class HostFrame {

    /** A new session was added. */
    @Serializable
    @SerialName("host/session-added")
    data class SessionAdded(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("blank") val blank: Boolean,
        @SerialName("parentSessionId") val parentSessionId: String? = null,
        @SerialName("origin") val origin: String? = null,
        @SerialName("cwd") val cwd: String? = null,
        @SerialName("agentPreset") val agentPreset: String? = null
    ) : HostFrame()

    /** A session was removed. */
    @Serializable
    @SerialName("host/session-removed")
    data class SessionRemoved(
        @SerialName("sessionId") val sessionId: String
    ) : HostFrame()

    /** Running status changed. */
    @Serializable
    @SerialName("host/session-status")
    data class SessionStatus(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("running") val running: Boolean
    ) : HostFrame()

    /** Agent error with no turn position. */
    @Serializable
    @SerialName("host/agent-error")
    data class AgentError(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("message") val message: String
    ) : HostFrame()

    /** Workspace state changed. */
    @Serializable
    @SerialName("host/workspace-changed")
    data class WorkspaceChanged(
        @SerialName("workspace") val workspace: JsonObject
    ) : HostFrame()

    /** Workspace removed. */
    @Serializable
    @SerialName("host/workspace-removed")
    data class WorkspaceRemoved(
        @SerialName("workspaceId") val workspaceId: String
    ) : HostFrame()

    /** Stream error. */
    @Serializable
    @SerialName("stream/error")
    data class StreamError(
        @SerialName("error") val error: RpcError
    ) : HostFrame()
}

// ── Session event envelope ──────────────────────────────────────────────────

/**
 * The envelope of a session event as it appears on the wire.
 * The [data] field contains the type-specific payload as a generic JsonObject.
 */
@Serializable
data class SessionEventEnvelope(
    @SerialName("type") val type: String,
    @SerialName("data") val data: JsonObject? = null,
    @SerialName("seq") val seq: Int,
    @SerialName("time") val time: Long
)

// ── Tool event view ─────────────────────────────────────────────────────────

/**
 * Host-computed render intent accompanying a tool/call or tool/result event.
 */
@Serializable
data class ToolEventView(
    @SerialName("for") val forType: String,
    @SerialName("view") val view: JsonObject
)

// ── Exceptions ──────────────────────────────────────────────────────────────

/**
 * Thrown when an RPC call returns a non-ok result.
 */
class RpcException(val error: RpcError) : Exception("RPC error [${error.code}]: ${error.message}")

/**
 * Thrown when a transport-level failure occurs (network, timeout, etc.).
 */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
