package com.dsh.client.data.repository

import com.dsh.client.data.api.DshApi
import com.dsh.client.data.api.RpcModels
import com.dsh.client.domain.model.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Repository for the session list.
 *
 * Exposes the current list of sessions as a [StateFlow], backed by the DSH
 * `session.list` RPC. WebSocket events automatically keep the list fresh:
 *
 * - `session/event` on a listed session bumps its `updatedAt` / preview.
 * - `host/session-status` flips the `running` bit (via [handleHostFrame]).
 * - periodic refresh re-baselines after any mutation.
 *
 * @param api  The underlying DSH API client.
 */
class SessionRepository(
    private val api: DshApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** Observable, newest-first list of sessions. */
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private var eventJob: Job? = null

    init {
        eventJob = scope.launch {
            api.events().collect { frame ->
                handleFrame(frame)
            }
        }
    }

    /**
     * Reload the full session list from the server.
     */
    suspend fun refresh() {
        val items = api.listSessions()
        _sessions.value = items.sortedByDescending { it.updatedAt }
    }

    /**
     * Create a new session via the API and refresh the list.
     *
     * @param workspaceId  Optional workspace to attach the session to.
     * @return The created session summary, or null on failure.
     */
    suspend fun createSession(workspaceId: String? = null): SessionSummary? {
        val result = api.createSession(workspaceId)
        return try {
            refresh()
            _sessions.value.firstOrNull { it.sessionId == result.sessionId }
        } catch (e: Exception) {
            null
        }
    }

    private fun handleFrame(frame: RpcModels.MuxFrame) {
        when (frame) {
            is RpcModels.MuxFrame.SessionEvent -> onSessionEvent(
                frame.sessionId, frame.event
            )
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
     * A session event arrived — bump updatedAt and refresh the preview.
     */
    private fun onSessionEvent(
        sessionId: String,
        event: RpcModels.SessionEventData
    ) {
        val type = event.eventType
        val touchesActivity = type == "user/message" || type == "assistant/message" ||
            type == "turn/start" || type == "turn/end"

        _sessions.value = _sessions.value.map { s ->
            if (s.sessionId == sessionId && touchesActivity) {
                s.copy(
                    title = event.title ?: s.title,
                    updatedAt = System.currentTimeMillis(),
                    lastMessagePreview = event.content?.let { extractPreview(it) }
                        ?: s.lastMessagePreview
                )
            } else s
        }.sortedByDescending { it.updatedAt }
    }

    /**
     * Handle a host-frame update, e.g. running-status flips.
     */
    fun handleHostFrame(frame: RpcModels.HostFrame) {
        when (frame) {
            is RpcModels.HostFrame.SessionAdded -> {
                val newRow = SessionSummary(
                    sessionId = frame.sessionId,
                    title = "",
                    updatedAt = System.currentTimeMillis(),
                    running = false,
                    blank = frame.blank,
                    agentPreset = frame.agentPreset
                )
                _sessions.value = (_sessions.value + newRow)
                    .sortedByDescending { it.updatedAt }
            }
            is RpcModels.HostFrame.SessionRemoved -> {
                _sessions.value = _sessions.value
                    .filterNot { it.sessionId == frame.sessionId }
            }
            is RpcModels.HostFrame.SessionStatus -> {
                _sessions.value = _sessions.value.map { s ->
                    if (s.sessionId == frame.sessionId) s.copy(running = frame.running) else s
                }
            }
            is RpcModels.HostFrame.AgentError,
            is RpcModels.HostFrame.WorkspaceChanged,
            is RpcModels.HostFrame.WorkspaceRemoved,
            is RpcModels.HostFrame.StreamError -> Unit
        }
    }

    /**
     * Extract a short preview from the flattened event content.
     */
    private fun extractPreview(content: Any?): String? {
        return when (content) {
            is String -> content.take(120)
            is List<*> -> content.joinToString("") { block ->
                when (block) {
                    is Map<*, *> -> {
                        val type = block["type"] as? String ?: ""
                        when (type) {
                            "text" -> (block["text"] as? String) ?: ""
                            else -> ""
                        }
                    }
                    else -> ""
                }
            }.take(120)
            else -> null
        }
    }
}
