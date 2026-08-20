package com.dsh.client.data.cache

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.listSerializer
import kotlinx.serialization.json.Json
import java.io.File

object LocalCache {

    private const val CACHE_DIR = "dsh_cache"
    private const val SESSIONS_FILE = "sessions.json"
    private const val MESSAGES_PREFIX = "messages_"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private var cacheDir: File? = null

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, CACHE_DIR).also { it.mkdirs() }
    }

    @Serializable
    data class SessionCache(
        @SerialName("sessionId") val sessionId: String,
        @SerialName("title") val title: String = "",
        @SerialName("updatedAt") val updatedAt: Long = 0,
        @SerialName("agentPreset") val agentPreset: String? = null,
        @SerialName("lastMessagePreview") val lastMessagePreview: String? = null,
    )

    @Serializable
    data class MessageCache(
        @SerialName("id") val id: String,
        @SerialName("role") val role: String,
        @SerialName("content") val content: String,
        @SerialName("timestamp") val timestamp: Long,
        @SerialName("isStreaming") val isStreaming: Boolean = false,
    )

    fun loadSessions(): List<SessionCache> {
        val file = sessionsFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(listSerializer(SessionCache.serializer()), file.readText())
        } catch (_: Exception) { emptyList() }
    }

    fun saveSessions(sessions: List<SessionCache>) {
        val file = sessionsFile() ?: return
        try { file.writeText(json.encodeToString(listSerializer(SessionCache.serializer()), sessions)) } catch (_: Exception) { }
    }

    fun loadMessages(sessionId: String): List<MessageCache> {
        val file = messagesFile(sessionId) ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(listSerializer(MessageCache.serializer()), file.readText())
        } catch (_: Exception) { emptyList() }
    }

    fun saveMessages(sessionId: String, messages: List<MessageCache>) {
        val file = messagesFile(sessionId) ?: return
        try { file.writeText(json.encodeToString(listSerializer(MessageCache.serializer()), messages)) } catch (_: Exception) { }
    }

    fun clearAll() {
        cacheDir?.let { dir -> dir.listFiles()?.forEach { it.delete() } }
    }

    private fun sessionsFile(): File? = cacheDir?.let { File(it, SESSIONS_FILE) }
    private fun messagesFile(sessionId: String): File? =
        cacheDir?.let { File(it, "$MESSAGES_PREFIX$sessionId.json") }
}
