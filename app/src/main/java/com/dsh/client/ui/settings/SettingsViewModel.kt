package com.dsh.client.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "https://dsh.113096.xyz:4443",
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val isDarkTheme: Boolean = true,
)

class SettingsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE)
        val url = prefs.getString("server_url", "https://dsh.113096.xyz:4443") ?: "https://dsh.113096.xyz:4443"
        val dark = prefs.getBoolean("dark_theme", true)
        _uiState.update { it.copy(serverUrl = url, isDarkTheme = dark) }
    }

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url) }
    }

    fun saveServerUrl(context: Context) {
        val prefs = context.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("server_url", _uiState.value.serverUrl).apply()
        // Update the global server URL
        com.dsh.client.DshApp.serverUrl = _uiState.value.serverUrl
    }

    fun testConnection(context: Context) {
        _uiState.update { it.copy(isTesting = true, testResult = null) }
        viewModelScope.launch {
            try {
                val url = _uiState.value.serverUrl
                // 用真实 RPC 请求验证连接（session.list 轻量且无需参数）
                val rpc = com.dsh.client.data.api.DshRpcClient { "$url/api" }
                rpc.call<kotlinx.serialization.json.JsonElement>("session.list", kotlinx.serialization.json.buildJsonObject { })
                _uiState.update { it.copy(isTesting = false, testResult = "连接成功") }
            } catch (e: Exception) {
                val msg = (e as? com.dsh.client.data.api.RpcException)?.error?.message ?: (e.message ?: "未知错误")
                _uiState.update { it.copy(isTesting = false, testResult = "连接失败: $msg") }
            }
        }
    }
}
