package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.serialization.Serializable

@Serializable data class TaskProgress(
    val turnId: String = "", val status: String = "", val error: String? = null,
    val syncedAt: Long = 0, val accepted: Boolean = false,
    // accepted is retained only to read older saved workspaces.
    val seen: Boolean = accepted, val dismissed: Boolean = false,
)

internal fun TaskProgress.viewed(): TaskProgress =
    if (status in setOf("completed", "failed", "interrupted")) copy(seen = true) else this

internal fun taskProgress(previous: TaskProgress, update: TimelineUpdate?, now: Long): TaskProgress = when (update) {
    is TimelineUpdate.Started -> TaskProgress(update.turnId, "running", syncedAt = now)
    is TimelineUpdate.Completed -> if (previous.turnId.isNotBlank() && previous.turnId != update.turnId) previous.copy(syncedAt = now)
        else TaskProgress(update.turnId, update.status, update.error, now,
            seen = previous.turnId == update.turnId && previous.status == update.status && previous.seen,
            dismissed = previous.turnId == update.turnId && previous.status == update.status && previous.dismissed)
    else -> previous.copy(syncedAt = now)
}

internal data class TaskPresentation(val label: String, val detail: String, val priority: Int)
internal fun taskPresentation(s: PocketState, tab: SessionTab): TaskPresentation {
    val connected = s.connections[tab.profileId]?.connected == true
    val pending = s.pending.any { it.profileId == tab.profileId && it.params.text("threadId") == tab.thread.id }
    return when {
        pending -> TaskPresentation("需要你处理", if (connected && tab.taskReady) "Agent 正在等待你的回答或确认" else "有待确认请求，连接恢复后核对是否仍有效", 0)
        !connected || !tab.taskReady -> TaskPresentation("状态待核实", tab.taskIssue ?: if (!connected) "手机未连接；不能据此判断电脑任务是否继续" else "正在核对电脑上的会话状态", 2)
        tab.taskIssue != null -> TaskPresentation("需要检查", tab.taskIssue, 1)
        tab.taskSending -> TaskPresentation("正在发送", "正在等待电脑确认收到消息，请勿重复发送", 4)
        tab.running -> TaskPresentation("执行中", "电脑正在执行，手机保持同步", 4)
        tab.progress.status == "failed" -> TaskPresentation("执行失败", tab.progress.error ?: "查看执行记录后再决定如何继续", if (tab.progress.dismissed) 6 else 1)
        tab.progress.status == "interrupted" -> TaskPresentation("已停止", "本轮执行已中断，可查看已有结果或继续任务", 6)
        tab.progress.status == "completed" -> TaskPresentation("本轮已结束", if (tab.progress.seen) "可查看结果或继续对话" else "有新的执行结果可查看", 6)
        tab.unread -> TaskPresentation("有新消息", "查看会话中的最新进展", 6)
        else -> TaskPresentation("可继续", "会话已同步，可以发送新消息", 6)
    }
}
internal fun allTaskSessions(s: PocketState): List<SessionTab> = (s.taskRecords + s.sessionTabs).distinctBy { it.key }
internal fun followedTasks(s: PocketState) = allTaskSessions(s).filter { it.tracked }
internal fun needsTaskAttention(s: PocketState, tab: SessionTab): Boolean =
    s.pending.any { it.profileId == tab.profileId && it.params.text("threadId") == tab.thread.id } ||
        tab.taskIssue != null || (tab.progress.status == "failed" && !tab.progress.dismissed && !tab.running && !tab.taskSending)
internal fun attentionTasks(s: PocketState): List<SessionTab> = followedTasks(s)
    .filter { needsTaskAttention(s, it) }
    .sortedWith(compareBy<SessionTab> { taskPresentation(s, it).priority }.thenByDescending { it.progress.syncedAt })

internal fun taskResult(s: PocketState, tab: SessionTab?): ChatItem? {
    val turn = tab?.progress?.turnId.orEmpty()
    return s.items.lastOrNull { it.kind == "agentMessage" && it.phase != "commentary" && (turn.isBlank() || it.turnId == turn) && (it.text.isNotBlank() || it.images.isNotEmpty()) }
}

internal fun taskGroup(s: PocketState, tab: SessionTab): String {
    if (s.pending.any { it.profileId == tab.profileId && it.params.text("threadId") == tab.thread.id }) return "request"
    return when {
        needsTaskAttention(s, tab) -> "action"
        tab.running || tab.taskSending -> "running"
        else -> "result"
    }
}
