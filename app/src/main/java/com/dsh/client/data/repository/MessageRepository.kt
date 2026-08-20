package com.dsh.client.data.repository

import com.dsh.client.data.api.DshApi
import com.dsh.client.data.api.RpcModels
import com.dsh.client.domain.model.Message
import com.dsh.client.domain.model.MessageRole
import com.dsh.client.domain.model.ToolCall
import com.dsh.client.domain.model.ToolCallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Repository for messages within a session.
 *
 * Maintains a per-session message list backed by [StateFlow]. Messages are
 * loaded from DSH history on demand and appended/replaced as events arrive
 * via the WebSocket mux stream.
 *
 * @param api  The underlying DSH API client.
 */
class MessageRepository(
    private val api: DshApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Per-session message lists. */
    private val _messages = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val loadedSessions = mutableSetOf<String>()
    private val hasMoreData = mutableMapOf<String, Boolean>()
    private var lastLoadedSeq = mutableMapOf<String, Int>()

    private var eventJob: Job? = null

    init {
        eventJob = scope.launch {
            api.events().collect { frame ->
                handleFrame(frame)
            }
        }
    }

    /**
     * Get the message flow for a specific session.
     *
     * @param sessionId  The session to get messages for.
     */
    fun messages(sessionId: String): StateFlow<List<Message>> {
        return _messages.getOrPut(sessionId) {
            MutableStateFlow(emptyList())
        }.asStateFlow()
    }

    /**
     * Load history for a session.
     *
     * @param sessionId  The session to load.
     * @param loadMore   If true, loads older history (pagination);
     *   if false, loads the tail page (most recent messages).
     */
    suspend fun loadHistory(sessionId: String, loadMore: Boolean = false) {
        val beforeSeq = if (loadMore && loadedSessions.contains(sessionId)) {
            lastLoadedSeq[sessionId]
        } else {
            null
        }
        val maxMessages = 50

        try {
            val result = api.getHistory(sessionId, beforeSeq, maxMessages)
            val messages = result.events.mapNotNull { eventData ->
                eventDataToMessage(eventData)
            }

            val flow = _messages.getOrPut(sessionId) {
                MutableStateFlow(emptyList())
            }

            if (loadMore) {
                flow.value = messages + flow.value
            } else {
                flow.value = messages
            }

            if (result.events.isNotEmpty()) {
                val minSeq = result.events.minOf { it.seq }
                lastLoadedSeq[sessionId] = minSeq
            }
            hasMoreData[sessionId] = result.hasMore
            loadedSessions.add(sessionId)
        } catch (_: Exception) {
            // Leave existing list as-is on error
        }
    }

    /**
     * Send a message to a session.
     *
     * Optimistically appends a provisional user message, then lets the real
     * event stream replace it.
     *
     * @param sessionId  The target session.
     * @param content    The message text.
     */
    suspend fun sendMessage(sessionId: String, content: String) {
        val provisional = Message(
            id = "pending-${System.currentTimeMillis()}",
            role = MessageRole.User,
            content = content,
            timestamp = System.currentTimeMillis(),
            isStreaming = false
        )
        val flow = _messages.getOrPut(sessionId) {
            MutableStateFlow(emptyList())
        }
        flow.value = flow.value + provisional

        try {
            api.sendMessage(sessionId, content)
        } catch (e: Exception) {
            flow.value = flow.value.filterNot { it.id == provisional.id }
        }
    }

    /**
     * Handle a single mux frame — keeps the message list live.
     */
    private fun handleFrame(frame: RpcModels.MuxFrame) {
        when (frame) {
            is RpcModels.MuxFrame.SessionEvent -> {
                val sessionId = frame.sessionId
                val flow = _messages[sessionId] ?: return
                val message = eventDataToMessage(frame.event) ?: return

                if (loadedSessions.contains(sessionId)) {
                    val current = flow.value
                    val existingIndex = current.indexOfLast { it.id == message.id }
                    flow.value = if (existingIndex >= 0) {
                        current.toMutableList().apply {
                            set(existingIndex, message)
                        }
                    } else {
                        current + message
                    }
                }
            }
            is RpcModels.MuxFrame.SessionSubscribed,
            is RpcModels.MuxFrame.SessionQueue,
            is RpcModels.MuxFrame.SessionJobs,
            is RpcModels.MuxFrame.SessionProjection,
            is RpcModels.MuxFrame.StreamError,
            is RpcModels.MuxFrame.ApprovalRequested,
            is RpcModels.MuxFrame.ApprovalResolved,
            is RpcModels.MuxFrame.QuestionRequested,
            is RpcModels.MuxFrame.QuestionResolved -> Unit
        }
    }

    /**
     * Convert a flattened [RpcModels.SessionEventData] into a domain [Message].
     * Returns null for non-message event types (e.g. turn/start, turn/end).
     */
    private fun eventDataToMessage(eventData: RpcModels.SessionEventData): Message? {
        val role = when (eventData.eventType) {
            "user/message" -> MessageRole.User
            "assistant/message" -> MessageRole.Assistant
            "system/prompt" -> MessageRole.System
            "tool/call", "tool/result" -> MessageRole.Tool
            else -> return null
        }

        val content = when (val raw = eventData.content) {
            is String -> raw
            is List<*> -> raw.joinToString("") { block ->
                when (block) {
                    is Map<*, *> -> {
                        when (block["type"] as? String) {
                            "text" -> (block["text"] as? String) ?: ""
                            else -> ""
                        }
                    }
                    else -> ""
                }
            }
            else -> ""
        }

        return Message(
            id = eventData.id ?: "${eventData.seq}",
            role = role,
            content = content,
            timestamp = eventData.timestamp,
            isStreaming = eventData.isPartial ?: false,
            toolCalls = emptyList()
        )
    }
}
