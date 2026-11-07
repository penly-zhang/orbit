package dev.codexpocket

import dev.codexpocket.core.text

internal data class TaskSignal(val key: String, val title: String, val tab: SessionTab?)
internal fun taskSignals(before: PocketState, after: PocketState): List<TaskSignal> = buildList {
    val old = before.sessionTabs.associateBy { it.key }
    after.sessionTabs.filter { !it.running && old[it.key]?.running == true && after.connections[it.profileId]?.connected == true }
        .forEach { add(TaskSignal("done:${it.key}", "任务已结束", it)) }
    val previous = before.pending.map { it.profileId to it.id }.toSet()
    after.pending.filter { (it.profileId to it.id) !in previous }.forEach { request ->
        add(TaskSignal("approval:${request.profileId}:${request.id}", "Agent 需要你的确认", after.sessionTabs.find { it.profileId == request.profileId && it.thread.id == request.params.text("threadId") }))
    }
}
