package com.dsh.client.domain.model

data class SessionSummary(
    val sessionId: String,
    val title: String = "",
    val updatedAt: Long = 0,
    val running: Boolean = false,
    val blank: Boolean = true,
    val agentPreset: String? = null,
    val lastMessagePreview: String? = null
)

data class Message(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = 0,
    val isStreaming: Boolean = false,
    val toolCalls: List<ToolCall> = emptyList()
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String = "",
    val result: String? = null,
    val status: ToolCallStatus = ToolCallStatus.Running
)

enum class MessageRole { User, Assistant, System, Tool }
enum class ToolCallStatus { Running, Success, Failed }

data class AgentPreset(
    val id: String,
    val name: String
)
