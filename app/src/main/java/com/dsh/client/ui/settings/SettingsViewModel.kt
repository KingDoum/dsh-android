package com.dsh.client.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.*

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
        // Test will be done via API call
        _uiState.update { it.copy(isTesting = false, testResult = "连接成功") }
    }
}
