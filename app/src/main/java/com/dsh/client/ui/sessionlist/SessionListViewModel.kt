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
                _uiState.update { it.copy(sessions = sessions, isLoading = false, isConnected = true) }
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
                    is RpcModels.MuxFrame.SessionEvent -> scheduleRefresh()
                    else -> {}
                }
            }
        }
    }

    // Throttle list refreshes: cooldown 1s between reloads triggered by events
    private var lastRefreshAt = 0L
    private fun scheduleRefresh() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (now - lastRefreshAt < 1000) return@launch
            lastRefreshAt = now
            loadSessions()
        }
    }
}
