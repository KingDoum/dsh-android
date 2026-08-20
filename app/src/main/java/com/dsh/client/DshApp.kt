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
                _api = null
            }

        private var _api: DshApi? = null

        val api: DshApi?
            get() {
                if (_api == null) {
                    try {
                        val rpc = DshRpcClient { "$serverUrl/api" }
                        val events = DshEventClient { serverUrl }
                        val api = DshApi(rpc, events)
                        _api = api
                    } catch (e: Exception) {
                        return null
                    }
                }
                return _api
            }
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("dsh_settings", MODE_PRIVATE)
        serverUrl = prefs.getString("server_url", "https://dsh.113096.xyz:4443") ?: "https://dsh.113096.xyz:4443"
    }
}
