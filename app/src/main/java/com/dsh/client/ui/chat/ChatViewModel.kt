package com.dsh.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.client.data.api.DshApi
import com.dsh.client.data.api.MuxFrame
import com.dsh.client.data.api.SessionEventWire
import com.dsh.client.domain.model.Message
import com.dsh.client.domain.model.MessageRole
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

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
            api?.events?.events?.collect { frame ->
                if (frame is MuxFrame.SessionEvent && frame.sessionId == sessionId) {
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

    private fun eventToMessage(event: SessionEventWire): Message? {
        return when (event.type) {
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
                event.title?.let { _uiState.update { it.copy(sessionTitle = event.title) } }
                null
            }
            else -> null
        }
    }

    private fun extractText(event: SessionEventWire): String {
        val content = event.content ?: return ""
        return try {
            if (content is JsonArray) {
                content.joinToString("") { block ->
                    val obj = block.jsonObject
                    if (obj["type"]?.jsonPrimitive?.content == "text") {
                        obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    } else ""
                }
            } else if (content is JsonPrimitive && content.isString) {
                content.content
            } else {
                // Try as array of objects
                val arr = json.decodeFromJsonElement<JsonArray>(content)
                arr.joinToString("") { block ->
                    val obj = block.jsonObject
                    if (obj["type"]?.jsonPrimitive?.content == "text") {
                        obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    } else ""
                }
            }
        } catch (_: Exception) { "" }
    }

    private val json = Json { ignoreUnknownKeys = true }
}
