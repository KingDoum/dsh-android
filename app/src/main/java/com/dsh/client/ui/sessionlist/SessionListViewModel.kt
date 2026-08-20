package com.dsh.client.ui.sessionlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.client.data.api.DshApi
import com.dsh.client.data.api.RpcModels
import com.dsh.client.domain.model.SessionSummary
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SessionListUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isConnected: Boolean = false,
)

class SessionListViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SessionListUiState())
    val uiState: StateFlow<SessionListUiState> = _uiState.asStateFlow()

    private var api: DshApi? = null

    /** Local cache of last message previews per session. Updated from event stream. Key = sessionId. */
    private val _previews = mutableMapOf<String, String>()

    fun setApi(api: DshApi) {
        if (this.api === api) return
        this.api = api
        loadSessions()
        observeEvents()
    }

    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val sessions = api?.listSessions() ?: emptyList()
                val merged = mergePreviews(sessions)
                _uiState.update { it.copy(sessions = merged, isLoading = false, isConnected = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "连接失败", isConnected = false) }
            }
        }
    }

    fun createSession(callback: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val result = api?.createSession(null)
                if (result != null) {
                    callback(result.sessionId)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            api?.events()?.collect { frame ->
                when (frame) {
                    is RpcModels.MuxFrame.SessionEvent -> {
                        updatePreview(frame.sessionId, frame.event)
                        scheduleRefresh()
                    }
                    else -> {}
                }
            }
        }
    }

    /** Merge locally cached previews into the session summaries. */
    private fun mergePreviews(sessions: List<SessionSummary>): List<SessionSummary> {
        return sessions.map { session ->
            val preview = _previews[session.sessionId] ?: session.lastMessagePreview
            if (preview != null) session.copy(lastMessagePreview = preview) else session
        }
    }

    /** Update preview text for a session from a user/assistant message event. */
    private fun updatePreview(sessionId: String, event: RpcModels.SessionEventData) {
        val text = when (event.eventType) {
            "user/message" -> extractPreviewText(event)
            "assistant/message" -> extractPreviewText(event)
            else -> return
        }
        if (text.isNotBlank()) {
            val preview = if (text.length > 80) text.take(80) + "..." else text
            _previews[sessionId] = preview
        }
    }

    private fun extractPreviewText(event: RpcModels.SessionEventData): String {
        val content = event.content ?: return ""
        return when (content) {
            is String -> content.trim()
            is List<*> -> content.joinToString("") { item ->
                if (item is Map<*, *>) {
                    val type = item["type"] as? String ?: ""
                    if (type == "text") (item["text"] as? String) ?: "" else ""
                } else ""
            }
            else -> ""
        }
    }

    // Throttle list refreshes: cooldown 1s between reloads triggered by events
    private var lastRefreshAt = 0L
    private fun scheduleRefresh() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (now - lastRefreshAt < 1000) return@launch
            lastRefreshAt = now
            try {
                val sessions = api?.listSessions() ?: emptyList()
                _uiState.update { it.copy(sessions = mergePreviews(sessions), isConnected = true) }
            } catch (_: Exception) { }
        }
    }
}
