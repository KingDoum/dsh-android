package com.dsh.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.client.data.api.DshApi
import com.dsh.client.data.api.RpcModels
import com.dsh.client.domain.model.Message
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

    fun setApiAndSession(api: DshApi, sessionId: String) {
        this.api = api
        this.currentSessionId = sessionId
        loadHistory()
        observeEvents()
    }

    fun loadHistory(loadMore: Boolean = false) {
        val sessionId = currentSessionId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val history = api?.getHistory(sessionId) ?: com.dsh.client.data.api.HistoryResult(emptyList(), false)
                val messages = history.events.mapNotNull { event ->
                    messageFromEvent(event)
                }
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
            // Optimistic message
            val tempMessage = Message(
                id = "temp-${System.currentTimeMillis()}",
                role = com.dsh.client.domain.model.MessageRole.User,
                content = content,
                timestamp = System.currentTimeMillis(),
                isStreaming = false
            )
            _uiState.update { it.copy(isSending = true, messages = it.messages + tempMessage) }
            try {
                api?.sendMessage(sessionId, content)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "发送失败", isSending = false) }
            }
        }
    }

    fun cancelGeneration() {
        val sessionId = currentSessionId ?: return
        viewModelScope.launch {
            try {
                api?.cancelSession(sessionId)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun observeEvents() {
        val sessionId = currentSessionId ?: return
        viewModelScope.launch {
            api?.events()?.collect { frame ->
                when (frame) {
                    is RpcModels.MuxFrame.SessionEvent -> {
                        if (frame.sessionId == sessionId) {
                            messageFromEvent(frame.event)?.let { msg ->
                                _uiState.update { state ->
                                    val existing = state.messages
                                    if (existing.any { it.id == msg.id }) {
                                        // Replace streaming message
                                        state.copy(messages = existing.map { if (it.id == msg.id) msg else it })
                                    } else {
                                        state.copy(messages = existing + msg)
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun messageFromEvent(event: RpcModels.SessionEventData): Message? {
        return when (event.eventType) {
            "user/message" -> Message(
                id = event.id ?: "u-${event.seq}",
                role = com.dsh.client.domain.model.MessageRole.User,
                content = extractText(event),
                timestamp = event.timestamp,
                isStreaming = false
            )
            "assistant/message" -> Message(
                id = event.id ?: "a-${event.seq}",
                role = com.dsh.client.domain.model.MessageRole.Assistant,
                content = extractText(event),
                timestamp = event.timestamp,
                isStreaming = event.isPartial ?: false
            )
            "session/title" -> {
                if (event.title != null) {
                    _uiState.update { it.copy(sessionTitle = event.title) }
                }
                null
            }
            else -> null
        }
    }

    private fun extractText(event: RpcModels.SessionEventData): String {
        // Extract text from content blocks
        val content = event.content
        if (content == null) return ""
        return when (content) {
            is String -> content
            is List<*> -> content.joinToString("") { block ->
                when (block) {
                    is Map<*, *> -> {
                        val type = block["type"] as? String ?: ""
                        when (type) {
                            "text" -> (block["text"] as? String) ?: ""
                            else -> ""
                        }
                    }
                    else -> ""
                }
            }
            else -> ""
        }
    }
}
