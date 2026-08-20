package com.dsh.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.client.data.api.DshApi
import com.dsh.client.data.api.RpcModels
import com.dsh.client.domain.model.Message
import com.dsh.client.domain.model.MessageRole
import com.dsh.client.domain.model.ToolCall
import com.dsh.client.domain.model.ToolCallStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val error: String? = null,
    val sessionTitle: String = "",
)

class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var api: DshApi? = null
    private var currentSessionId: String? = null
    private val _pendingReplacements = mutableMapOf<String, String>()

    // Track tool calls by callId for the current session
    private val _toolCalls = mutableMapOf<String, ToolCall>()

    // Streaming buffer for the in-flight assistant message
    private val streamingBuffer = StreamingMarkdownBuffer()
    private var streamingMsgId: String? = null

    fun setApiAndSession(api: DshApi, sessionId: String) {
        if (this.api === api && this.currentSessionId == sessionId) return
        this.api = api
        this.currentSessionId = sessionId
        _toolCalls.clear()
        streamingBuffer.reset()
        streamingMsgId = null
        loadHistory()
        observeEvents()
    }

    fun loadHistory() {
        val sessionId = currentSessionId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val history = api?.getHistory(sessionId) ?: com.dsh.client.data.api.HistoryResult(emptyList(), false)
                val messages = historyToMessages(history.events)
                _uiState.update { it.copy(messages = messages, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank() || _uiState.value.isSending) return
        val sessionId = currentSessionId ?: return
        viewModelScope.launch {
            val tempId = "temp-${System.currentTimeMillis()}"
            val tempMsg = Message(
                id = tempId,
                role = MessageRole.User,
                content = content,
                timestamp = System.currentTimeMillis(),
                isStreaming = false
            )
            _uiState.update { it.copy(isSending = true, messages = it.messages + tempMsg) }
            // Record pending replacement BEFORE network call (WS may echo before send returns)
            val sendToken = tempId
            _pendingReplacements[content] = sendToken
            try {
                api?.sendMessage(sessionId, content)
            } catch (e: Exception) {
                _pendingReplacements.remove(content)
                _uiState.update { it.copy(error = e.message ?: "发送失败", isSending = false) }
            }
        }
    }

    fun cancelGeneration() {
        val sessionId = currentSessionId ?: return
        viewModelScope.launch {
            try { api?.cancelSession(sessionId) } catch (_: Exception) {}
        }
    }

    private fun observeEvents() {
        val sessionId = currentSessionId ?: return
        viewModelScope.launch {
            api?.events()?.collect { frame ->
                if (frame is RpcModels.MuxFrame.SessionEvent && frame.sessionId == sessionId) {
                    handleEvent(frame.event)
                }
            }
        }
    }

    private fun handleEvent(event: RpcModels.SessionEventData) {
        when (event.eventType) {
            "assistant/chunk" -> handleChunk(event)
            "tool/call" -> handleToolCall(event)
            "tool/result" -> handleToolResult(event)
            else -> {
                eventToMessage(event)?.let { msg ->
                    _uiState.update { state ->
                        val existing = state.messages
                        if (existing.any { it.id == msg.id }) {
                            state.copy(messages = existing.map { if (it.id == msg.id) msg else it })
                        } else {
                            state.copy(messages = existing + msg)
                        }
                    }
                }
            }
        }
    }

    private fun handleChunk(event: RpcModels.SessionEventData) {
        when (event.chunkType) {
            "block-start" -> {
                // New text block starting — create/refresh streaming message
                if (event.chunkBlockType == "text" || event.chunkBlockType == null) {
                    if (streamingMsgId == null) {
                        streamingMsgId = "stream-${event.seq}-${event.chunkIndex ?: 0}"
                        streamingBuffer.reset()
                        val msg = Message(
                            id = streamingMsgId!!,
                            role = MessageRole.Assistant,
                            content = "",
                            timestamp = event.timestamp,
                            isStreaming = true
                        )
                        _uiState.update { it.copy(messages = it.messages + msg) }
                    }
                }
            }
            "text-delta" -> {
                val text = event.chunkText ?: return
                val safeFull = streamingBuffer.append(text)
                val msgId = streamingMsgId ?: "stream-${event.seq}-${event.chunkIndex ?: 0}"
                if (streamingMsgId == null) {
                    streamingMsgId = msgId
                    _uiState.update { state ->
                        val exists = state.messages.any { it.id == msgId }
                        if (exists) state
                        else state.copy(messages = state.messages + Message(
                            id = msgId,
                            role = MessageRole.Assistant,
                            content = safeFull,
                            timestamp = event.timestamp,
                            isStreaming = true
                        ))
                    }
                } else {
                    _uiState.update { state ->
                        state.copy(messages = state.messages.map {
                            if (it.id == msgId) it.copy(content = safeFull, isStreaming = true, timestamp = event.timestamp)
                            else it
                        })
                    }
                }
            }
            "block-end" -> {
                // Final content for this block: replace partial with complete text
                val finalText = event.content as? String ?: streamingBuffer.raw()
                val msgId = streamingMsgId ?: "stream-${event.seq}-${event.chunkIndex ?: 0}"
                _uiState.update { state ->
                    val existingIndex = state.messages.indexOfFirst { it.id == msgId }
                    if (existingIndex != -1) {
                        val updated = state.messages.mapIndexed { idx, m ->
                            if (idx == existingIndex && m.role == MessageRole.Assistant) {
                                m.copy(content = finalText, isStreaming = false)
                            } else m
                        }
                        state.copy(messages = updated)
                    } else {
                        state.copy(messages = state.messages + Message(
                            id = msgId,
                            role = MessageRole.Assistant,
                            content = finalText,
                            timestamp = event.timestamp,
                            isStreaming = false
                        ))
                    }
                }
                // Keep the id as the canonical one until assistant/message arrives
                streamingMsgId?.let { streamingBuffer.reset() }
                // Don't clear streamingMsgId here — the assistant/message will override/supersede
            }
            else -> {
                // usage / finish / reasoning-delta / tool-call-delta — no visible text change for now
                // tool-call-delta will be followed by a tool/call event
            }
        }
    }

    private fun handleToolCall(event: RpcModels.SessionEventData) {
        val callId = event.toolCallId ?: return
        val name = event.toolName ?: "unknown"
        val args = event.toolArguments ?: "{}"
        val call = ToolCall(
            id = callId,
            name = name,
            arguments = args,
            status = ToolCallStatus.Pending
        )
        _toolCalls[callId] = call

        // Attach the tool call to the last message (if it belongs to an assistant message) or show standalone
        _uiState.update { state ->
            val messages = state.messages
            val lastAssistantIdx = messages.indexOfLast { it.role == MessageRole.Assistant && !it.isStreaming }
            if (lastAssistantIdx >= 0) {
                state.copy(
                    messages = messages.mapIndexed { idx, m ->
                        if (idx == lastAssistantIdx) {
                            m.copy(toolCalls = m.toolCalls.filter { it.id != callId } + call)
                        } else m
                    }
                )
            } else {
                // Standalone tool call — attach to a synthetic assistant message
                val msg = Message(
                    id = "tool-${callId}",
                    role = MessageRole.Assistant,
                    content = "",
                    timestamp = event.timestamp,
                    toolCalls = listOf(call)
                )
                state.copy(messages = messages + msg)
            }
        }
    }

    private fun handleToolResult(event: RpcModels.SessionEventData) {
        val callId = event.toolCallId ?: return
        val status = if (event.toolIsError) ToolCallStatus.Error else ToolCallStatus.Success
        val result = event.toolResult ?: ""

        // Update the tracked tool call
        val existing = _toolCalls[callId]
        val updatedCall = (existing ?: ToolCall(callId, "unknown", "{}"))
            .copy(result = result, status = status)
        _toolCalls[callId] = updatedCall

        // Update the UI message that carries this tool call
        _uiState.update { state ->
            state.copy(messages = state.messages.map { m ->
                if (m.toolCalls.any { it.id == callId }) {
                    m.copy(toolCalls = m.toolCalls.map {
                        if (it.id == callId) updatedCall else it
                    })
                } else m
            })
        }
    }

    /**
     * Reconstruct messages from raw history events, attaching tool calls to
     * the assistant message that follows them in the turn.
     */
    private fun historyToMessages(events: List<RpcModels.SessionEventData>): List<Message> {
        val messages = mutableListOf<Message>()
        // Calls not yet followed by their assistant/message: attach at the end
        val pendingToolCalls = mutableListOf<ToolCall>()

        for (event in events) {
            when (event.eventType) {
                "tool/call" -> {
                    val call = ToolCall(
                        id = event.toolCallId ?: "tc-${event.seq}",
                        name = event.toolName ?: "unknown",
                        arguments = event.toolArguments ?: "{}",
                        status = ToolCallStatus.Pending
                    )
                    // Replace if an earlier event with same id exists (call→result)
                    val idx = pendingToolCalls.indexOfFirst { it.id == call.id }
                    if (idx >= 0) pendingToolCalls[idx] = call else pendingToolCalls.add(call)
                }
                "tool/result" -> {
                    val callId = event.toolCallId ?: continue
                    val idx = pendingToolCalls.indexOfFirst { it.id == callId }
                    val updated = if (idx >= 0) {
                        pendingToolCalls[idx].copy(
                            result = event.toolResult ?: "",
                            status = if (event.toolIsError) ToolCallStatus.Error else ToolCallStatus.Success
                        )
                    } else {
                        ToolCall(
                            id = callId,
                            name = "unknown",
                            arguments = "{}",
                            result = event.toolResult ?: "",
                            status = if (event.toolIsError) ToolCallStatus.Error else ToolCallStatus.Success
                        )
                    }
                    if (idx >= 0) pendingToolCalls[idx] = updated else pendingToolCalls.add(updated)
                }
                "assistant/message" -> {
                    val msg = Message(
                        id = event.id ?: "a-${event.seq}",
                        role = MessageRole.Assistant,
                        content = extractText(event),
                        timestamp = event.timestamp,
                        isStreaming = event.isPartial ?: false,
                        toolCalls = pendingToolCalls.toList()
                    )
                    messages.add(msg)
                    pendingToolCalls.clear()
                }
                else -> {
                    eventToMessage(event)?.let { messages.add(it) }
                }
            }
        }
        // Any calls not followed by an assistant message attach to the last message
        if (pendingToolCalls.isNotEmpty() && messages.isNotEmpty()) {
            val last = messages.last()
            messages[messages.size - 1] = last.copy(
                toolCalls = last.toolCalls + pendingToolCalls
            )
        }
        return messages
    }

    private fun eventToMessage(event: RpcModels.SessionEventData): Message? {
        return when (event.eventType) {
            "user/message" -> {
                val text = extractText(event)
                // Check if this replaces a pending optimistic message
                val tempId = _pendingReplacements.remove(text)
                if (tempId != null) {
                    _uiState.update { state ->
                        state.copy(messages = state.messages.filter { it.id != tempId })
                    }
                }
                Message(
                    id = event.id ?: "u-${event.seq}",
                    role = MessageRole.User,
                    content = text,
                    timestamp = event.timestamp,
                    isStreaming = false
                )
            }
            "assistant/message" -> {
                // Supersede any in-flight streaming message for this turn
                streamingMsgId?.let {
                    _uiState.update { state ->
                        state.copy(messages = state.messages.filter { m -> m.id != it || m.role != MessageRole.Assistant })
                    }
                    streamingMsgId = null
                    streamingBuffer.reset()
                }
                Message(
                    id = event.id ?: "a-${event.seq}",
                    role = MessageRole.Assistant,
                    content = extractText(event),
                    timestamp = event.timestamp,
                    isStreaming = event.isPartial ?: false
                )
            }
            "session/title" -> {
                event.title?.let { _uiState.update { state -> state.copy(sessionTitle = it) } }
                null
            }
            else -> null
        }
    }

    private fun extractText(event: RpcModels.SessionEventData): String {
        // For streamed chunk events, the content is already the partial text
        val content = event.content ?: return ""
        return when (content) {
            is String -> content
            is List<*> -> content.joinToString("") { item ->
                if (item is Map<*, *>) {
                    val type = item["type"] as? String ?: ""
                    if (type == "text") (item["text"] as? String) ?: "" else ""
                } else ""
            }
            else -> ""
        }
    }
}
