package com.dsh.client

import android.app.Application
import com.dsh.client.data.api.DshApi
import com.dsh.client.data.api.DshEventClient
import com.dsh.client.data.api.DshRpcClient

class DshApp : Application() {
    companion object {
        var serverUrl: String = "https://dsh.113096.xyz:4443"
            set(value) {
                field = value
                _api = null // Force re-create on next access
            }

        private var _api: DshApi? = null

        val api: DshApi?
            get() {
                if (_api == null) {
                    try {
                        val rpcClient = DshRpcClient { "$serverUrl/api" }
                        val eventClient = DshEventClient { serverUrl }
                        _api = DshApi(rpcClient, eventClient)
                    } catch (e: Exception) {
                        return null
                    }
                }
                return _api
            }
    }

    override fun onCreate() {
        super.onCreate()
        // Load saved server URL
        val prefs = getSharedPreferences("dsh_settings", MODE_PRIVATE)
        serverUrl = prefs.getString("server_url", "https://dsh.113096.xyz:4443") ?: "https://dsh.113096.xyz:4443"
    }
}
