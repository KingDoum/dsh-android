package com.dsh.client.domain.model

/**
 * Domain models for the DSH client application.
 * These are UI-layer-friendly representations, decoupled from the wire format.
 */

// ── Session summary ─────────────────────────────────────────────────────────

/**
 * Summary of a session as displayed in the session list.
 *
 * @param sessionId          Unique session identifier.
 * @param title              Human-readable session title (may be empty for blank sessions).
 * @param updatedAt          Epoch millis of the latest activity.
 * @param running            Whether the session's agent is currently active.
 * @param blank              True if no turn has ever run in this session.
 * @param agentPreset        The agent preset this session was composed from, if any.
 * @param lastMessagePreview Optional short preview of the last message content.
 */
data class SessionSummary(
    val sessionId: String,
    val title: String,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val agentPreset: String? = null,
    val lastMessagePreview: String? = null
)

// ── Message ─────────────────────────────────────────────────────────────────

/**
 * A single message in a conversation.
 *
 * @param id           Unique message identifier.
 * @param role         Who sent the message (User, Assistant, System, Tool).
 * @param content      The text content of the message.
 * @param timestamp    Epoch millis when the message was created.
 * @param isStreaming  Whether this message is still being streamed (chunks pending).
 * @param toolCalls    Tool calls associated with this message, if any.
 */
data class Message(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val isStreaming: Boolean = false,
    val toolCalls: List<ToolCall> = emptyList()
)

// ── Message role ────────────────────────────────────────────────────────────

/**
 * Who authored a message.
 */
sealed class MessageRole {
    /** A human user message. */
    data object User : MessageRole()

    /** The model assistant response. */
    data object Assistant : MessageRole()

    /** A system prompt message. */
    data object System : MessageRole()

    /** A tool invocation result. */
    data object Tool : MessageRole()
}

// ── Tool call ───────────────────────────────────────────────────────────────

/**
 * One tool invocation within a message.
 *
 * @param id         Tool call identifier.
 * @param name       The tool's name.
 * @param arguments  JSON arguments passed to the tool.
 * @param result     The tool's result, if available.
 * @param status     Current status of the tool call.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String? = null,
    val status: ToolCallStatus = ToolCallStatus.Pending
)

/**
 * Lifecycle status of a tool call.
 */
enum class ToolCallStatus {
    /** Tool call has been issued but not yet executed. */
    Pending,

    /** Tool is currently executing. */
    Running,

    /** Tool execution completed successfully. */
    Success,

    /** Tool execution failed. */
    Error
}

// ── Agent preset ────────────────────────────────────────────────────────────

/**
 * Describes an agent preset (composition of tools, prompts, and model config).
 *
 * @param id    The preset identifier.
 * @param name  Human-readable display name.
 */
data class AgentPreset(
    val id: String,
    val name: String
)
