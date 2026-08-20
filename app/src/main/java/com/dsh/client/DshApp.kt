package com.dsh.client

import android.app.Application
import com.dsh.client.data.api.DshApi
import com.dsh.client.data.cache.LocalCache
import com.dsh.client.data.api.DshEventClient
import com.dsh.client.data.api.DshRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DshApp : Application() {
    companion object {
        var serverUrl: String = "https://dsh.113096.xyz:4443"
            set(value) {
                if (field == value) return
                field = value
                // 断开旧连接，避免泄漏
                _api?.let { old ->
                    old.disconnect()
                }
                _api = null
            }

        @Volatile private var _api: DshApi? = null
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val api: DshApi?
            get() {
                _api?.let { return it }
                synchronized(this) {
                    if (_api == null) {
                        try {
                            val rpc = DshRpcClient { "$serverUrl/api" }
                            val events = DshEventClient { serverUrl }
                            // Start event stream in background
                            appScope.launch { events.connect() }
                            _api = DshApi(rpc, events)
                        } catch (e: Exception) {
                            return null
                        }
                    }
                }
                return _api
            }
    }

    override fun onCreate() {
        super.onCreate()
        LocalCache.init(this)
        val prefs = getSharedPreferences("dsh_settings", MODE_PRIVATE)
        serverUrl = prefs.getString("server_url", "https://dsh.113096.xyz:4443") ?: "https://dsh.113096.xyz:4443"
    }
}
