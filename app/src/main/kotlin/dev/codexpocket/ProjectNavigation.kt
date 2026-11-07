package dev.codexpocket

import dev.codexpocket.core.*

internal data class ProjectNavigationRow(val project: ProjectInfo, val threads: List<ThreadInfo>, val expanded: Boolean)

internal fun loadedProjectThreads(s: PocketState, project: ProjectInfo): List<ThreadInfo> {
    val loaded = if (s.project?.id == project.id) s.threads else s.navigationThreads[project.id].orEmpty()
    val opened = if (s.showArchived) emptyList() else s.sessionTabs.filter { it.profileId == s.profile?.id && it.project.id == project.id }.map { it.thread }
    return (loaded + opened).distinctBy { it.id }
}

internal fun projectNavigationRows(s: PocketState, query: String, filter: String = "all"): List<ProjectNavigationRow> {
    val text = query.trim()
    return s.projects.mapNotNull { project ->
        val projectMatches = (project.name + " " + project.roots.joinToString(" ")).contains(text, true)
        val filtered = loadedProjectThreads(s, project).filter { thread ->
            val tab = s.sessionTabs.find { it.profileId == s.profile?.id && it.thread.id == thread.id }
            val statusMatches = when (filter) {
                "opened" -> tab != null
                "running" -> tab?.running == true || thread.status in setOf("active", "inProgress")
                "unread" -> tab?.unread == true
                "pending" -> s.pending.any { it.profileId == s.profile?.id && it.params["threadId"]?.toString()?.trim('"') == thread.id }
                else -> true
            }
            statusMatches && (text.isEmpty() || projectMatches || thread.title.contains(text, true))
        }
        if ((text.isNotEmpty() && !projectMatches && filtered.isEmpty()) || (filter != "all" && filtered.isEmpty())) null
        else ProjectNavigationRow(project, filtered, s.project?.id == project.id || text.isNotEmpty() || filter != "all")
    }
}

internal fun projectNavigationSummary(s: PocketState, project: ProjectInfo): String {
    val opened = s.sessionTabs.count { it.profileId == s.profile?.id && it.project.id == project.id }
    val loaded = loadedProjectThreads(s, project)
    val known = loaded.isNotEmpty() || s.navigationThreads.containsKey(project.id) ||
        (s.project?.id == project.id && !s.threadsLoading && s.threadsError == null)
    val count = loaded.size.takeIf { known }
    return "${count?.let { "$it 条已加载" } ?: "会话待加载"} · $opened 个已打开"
}
