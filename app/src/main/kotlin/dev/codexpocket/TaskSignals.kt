package dev.codexpocket

import dev.codexpocket.core.text

internal data class TaskSignal(val key: String, val title: String, val tab: SessionTab?)
internal fun taskSignals(before: PocketState, after: PocketState): List<TaskSignal> = buildList {
    val old = allTaskSessions(before).associateBy { it.key }
    followedTasks(after).filter { tab ->
        val previous = old[tab.key]
        tab.taskReady && !tab.progress.seen && !tab.progress.dismissed && tab.progress.turnId.isNotBlank() &&
            tab.progress.status in setOf("completed", "failed", "interrupted") &&
            (previous == null || previous.progress.turnId != tab.progress.turnId || previous.progress.status != tab.progress.status ||
                !previous.taskReady || before.connections[tab.profileId]?.connected != true) &&
            after.connections[tab.profileId]?.connected == true
    }.forEach { add(TaskSignal("done:${it.key}:${it.progress.turnId}:${it.progress.status}", when (it.progress.status) { "failed" -> "执行失败，请检查"; "interrupted" -> "本轮已停止"; else -> "本轮已结束" }, it)) }
    val previous = before.pending.map { it.profileId to it.id }.toSet()
    after.pending.filter { request ->
        after.connections[request.profileId]?.connected == true &&
            ((request.profileId to request.id) !in previous || before.connections[request.profileId]?.connected != true)
    }.forEach { request ->
        val tab = allTaskSessions(after).find { it.profileId == request.profileId && it.thread.id == request.params.text("threadId") }
        val turn = request.params.text("turnId").ifBlank { tab?.progress?.turnId.orEmpty() }
        add(TaskSignal("approval:${request.profileId}:${request.params.text("threadId")}:$turn:${request.id}",
            if (request.kind == dev.codexpocket.core.RequestKind.QUESTIONS) "Agent 需要你的回答" else "Agent 需要你的确认", tab))
    }
}
