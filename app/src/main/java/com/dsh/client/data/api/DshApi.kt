package com.dsh.client.data.api

import com.dsh.client.domain.model.SessionSummary

class DshApi(
    private val rpc: DshRpcClient,
    val events: DshEventClient
) {
    suspend fun listSessions(): List<SessionSummary> {
        return rpc.listSessions().map { w ->
            SessionSummary(
                sessionId = w.sessionId,
                title = "", // extracted from event stream
                updatedAt = w.updatedAt,
                running = w.running,
                blank = w.blank,
                agentPreset = w.agentPreset
            )
        }
    }

    suspend fun createSession(workspaceId: String? = null): CreateSessionResult {
        val w = rpc.createSession(workspaceId)
        return CreateSessionResult(w.sessionId, w.agentPreset)
    }

    suspend fun getHistory(sessionId: String, beforeSeq: Int? = null, maxMessages: Int? = 50): HistoryResult {
        val w = rpc.history(sessionId, beforeSeq, maxMessages)
        return HistoryResult(w.events.map { it.event }, w.hasMore)
    }

    suspend fun sendMessage(sessionId: String, content: String): SendMessageResult {
        val w = rpc.prompt(sessionId, content)
        return SendMessageResult(w.accepted)
    }

    suspend fun cancelSession(sessionId: String) {
        rpc.cancel(sessionId)
    }

    suspend fun renameSession(sessionId: String, title: String) {
        rpc.rename(sessionId, title)
    }
}
