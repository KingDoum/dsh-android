package com.dsh.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.client.data.api.DshApi
import com.dsh.client.data.api.RpcModels
import com.dsh.client.domain.model.Message
import com.dsh.client.domain.model.MessageRole
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

    fun loadHistory() {
        val sessionId = currentSessionId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val history = api?.getHistory(sessionId) ?: com.dsh.client.data.api.HistoryResult(emptyList(), false)
                val messages = history.events.mapNotNull { event -> eventToMessage(event) }
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
            val tempMsg = Message(
                id = "temp-${System.currentTimeMillis()}",
                role = MessageRole.User,
                content = content,
                timestamp = System.currentTimeMillis(),
                isStreaming = false
            )
            _uiState.update { it.copy(isSending = true, messages = it.messages + tempMsg) }
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
            try { api?.cancelSession(sessionId) } catch (_: Exception) {}
        }
    }

    private fun observeEvents() {
        val sessionId = currentSessionId ?: return
        viewModelScope.launch {
            api?.events()?.collect { frame ->
                if (frame is RpcModels.MuxFrame.SessionEvent && frame.sessionId == sessionId) {
                    eventToMessage(frame.event)?.let { msg ->
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
    }

    private fun eventToMessage(event: RpcModels.SessionEventData): Message? {
        return when (event.eventType) {
            "user/message" -> Message(
                id = event.id ?: "u-${event.seq}",
                role = MessageRole.User,
                content = extractText(event),
                timestamp = event.timestamp,
                isStreaming = false
            )
            "assistant/message" -> Message(
                id = event.id ?: "a-${event.seq}",
                role = MessageRole.Assistant,
                content = extractText(event),
                timestamp = event.timestamp,
                isStreaming = event.isPartial ?: false
            )
            "session/title" -> {
                event.title?.let { _uiState.update { state -> state.copy(sessionTitle = it) } }
                null
            }
            else -> null
        }
    }

    private fun extractText(event: RpcModels.SessionEventData): String {
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
