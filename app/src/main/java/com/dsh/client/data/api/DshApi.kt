package com.dsh.client.data.api

import com.dsh.client.domain.model.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DshApi(
    private val rpcClient: DshRpcClient,
    private val eventClient: DshEventClient
) {
    suspend fun listSessions(): List<SessionSummary> {
        return rpcClient.listSessions().map { json ->
            // Extract title from projections
            val title = json.projections?.values?.sessionListMetadata?.let { meta ->
                if (meta.blank) "" else "会话"
            } ?: ""

            SessionSummary(
                sessionId = json.sessionId,
                title = title,
                updatedAt = json.updatedAt,
                running = json.running,
                blank = json.blank,
                agentPreset = json.agentPreset
            )
        }
    }

    suspend fun createSession(workspaceId: String? = null): CreateSessionResult? {
        val result = rpcClient.createSession(workspaceId)
        return CreateSessionResult(
            sessionId = result.sessionId,
            agentPreset = result.agentPreset
        )
    }

    suspend fun getHistory(sessionId: String, beforeSeq: Int? = null, maxMessages: Int? = 50): HistoryResult {
        val result = rpcClient.getHistory(sessionId, beforeSeq, maxMessages)
        return HistoryResult(
            events = result.events.map { it.event },
            hasMore = result.hasMore
        )
    }

    suspend fun sendMessage(sessionId: String, content: String): SendMessageResult {
        val result = rpcClient.sendMessage(sessionId, content)
        return SendMessageResult(accepted = result.accepted)
    }

    suspend fun cancelSession(sessionId: String) {
        rpcClient.cancelSession(sessionId)
    }

    suspend fun renameSession(sessionId: String, title: String) {
        rpcClient.renameSession(sessionId, title)
    }

    fun events(): Flow<MuxFrame> = eventClient.events
}
