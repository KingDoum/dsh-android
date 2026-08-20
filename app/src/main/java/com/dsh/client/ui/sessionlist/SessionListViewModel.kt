package com.dsh.client.ui.sessionlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.client.data.api.DshApi
import com.dsh.client.data.cache.LocalCache
import com.dsh.client.data.api.RpcModels
import com.dsh.client.domain.model.SessionSummary
import kotlinx.coroutines.delay
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

    /** Locally cached last message previews per session. Updated from event stream. */
    private val _previews = mutableMapOf<String, String>()

    /** Session IDs hidden by the user (local-only deletion). */
    private val hiddenSessions = mutableSetOf<String>()

    /** Session IDs pinned to the top (local-only pinning). */
    private val pinnedSessions = mutableSetOf<String>()

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
                // Cache: save to local
                LocalCache.saveSessions(sessions.map { it.toCache() })
                _uiState.update { it.copy(sessions = applyLocalModifiers(sessions), isLoading = false, isConnected = true) }
            } catch (e: Exception) {
                // Try cache
                val cached = LocalCache.loadSessions()
                if (cached.isNotEmpty()) {
                    val restored = cached.map { it.toSummary() }
                    _uiState.update { it.copy(sessions = applyLocalModifiers(restored), isLoading = false, isConnected = false, error = "离线模式") }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "连接失败", isConnected = false) }
                }
            }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    private val _searchQuery = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<SessionSummary>>(emptyList())
    val searchResults: StateFlow<List<SessionSummary>> = _searchResults.asStateFlow()
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private var searchJob: kotlinx.coroutines.Job? = null

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300) // debounce
            try {
                val results = api?.searchSessions(query) ?: emptyList()
                // Map results to SessionSummary (enrich with local data)
                val allSessions = _uiState.value.sessions
                _searchResults.value = results.mapNotNull { r ->
                    val existing = allSessions.find { it.sessionId == r.sessionId }
                    existing?.copy(lastMessagePreview = r.snippet)
                        ?: SessionSummary(
                            sessionId = r.sessionId,
                            title = "搜索结果",
                            updatedAt = 0,
                            running = false,
                            blank = false,
                            lastMessagePreview = r.snippet.take(80)
                        )
                }
            } catch (_: Exception) { }
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        searchJob?.cancel()
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

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            try {
                api?.renameSession(sessionId, newTitle)
            } catch (_: Exception) { }
        }
    }

    /** Hide a session from the list (local-only deletion). */
    fun hideSession(sessionId: String) {
        hiddenSessions.add(sessionId)
        _uiState.update { state ->
            state.copy(sessions = state.sessions.filter { it.sessionId != sessionId })
        }
    }

    /** Toggle pin status for a session. */
    fun togglePinSession(sessionId: String) {
        if (pinnedSessions.contains(sessionId)) {
            pinnedSessions.remove(sessionId)
        } else {
            pinnedSessions.add(sessionId)
        }
        _uiState.update { state ->
            state.copy(sessions = applyLocalModifiers(state.sessions.toList()))
        }
    }

    /** Check if a session is pinned. */
    fun isPinned(sessionId: String): Boolean = pinnedSessions.contains(sessionId)

    private fun applyLocalModifiers(sessions: List<SessionSummary>): List<SessionSummary> {
        val merged = mergePreviews(sessions)
        val filtered = merged.filter { it.sessionId !in hiddenSessions }
        val (pinned, normal) = filtered.partition { it.sessionId in pinnedSessions }
        return pinned + normal
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

    private fun mergePreviews(sessions: List<SessionSummary>): List<SessionSummary> {
        return sessions.map { session ->
            val preview = _previews[session.sessionId] ?: session.lastMessagePreview
            if (preview != null) session.copy(lastMessagePreview = preview) else session
        }
    }

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

    private var lastRefreshAt = 0L
    private fun scheduleRefresh() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (now - lastRefreshAt < 1000) return@launch
            lastRefreshAt = now
            try {
                val sessions = api?.listSessions() ?: emptyList()
                _uiState.update { it.copy(sessions = applyLocalModifiers(sessions), isConnected = true) }
            } catch (_: Exception) { }
        }
    }
}


// ── Cache serialization helpers ─────────────────────────────────────────────

private fun SessionSummary.toCache() = LocalCache.SessionCache(
    sessionId = sessionId, title = title, updatedAt = updatedAt,
    agentPreset = agentPreset, lastMessagePreview = lastMessagePreview,
)

private fun LocalCache.SessionCache.toSummary() = SessionSummary(
    sessionId = sessionId, title = title, updatedAt = updatedAt,
    running = false, blank = false, agentPreset = agentPreset,
    lastMessagePreview = lastMessagePreview,
)
