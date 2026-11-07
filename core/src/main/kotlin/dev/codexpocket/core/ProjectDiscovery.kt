package dev.codexpocket.core

import kotlinx.serialization.json.JsonObject

/** Registered projects and legacy directory groups coexist; native registration never deletes history. */
fun combineProjectSources(registered: List<ProjectInfo>, recentThreads: List<JsonObject>): List<ProjectInfo> {
    val ids = registered.map { it.id }.toSet()
    val directories = recentThreads.filter { it.text("projectId") !in ids }
        .mapNotNull { it.text("cwd").takeIf(String::isNotBlank) }.distinct()
    val legacy = directories.map { cwd ->
        val native = registered.firstOrNull { project -> project.roots.any { it.trimEnd('/') == cwd.trimEnd('/') } }
        // Unassigned history may share a root with a native project. Keep both
        // groups: filtering by projectId cannot retrieve those older threads.
        val name = native?.let { "${it.name} · 历史会话" }
            ?: cwd.trimEnd('/').substringAfterLast('/').ifBlank { cwd }
        ProjectInfo("cwd:$cwd", name, listOf(cwd))
    }
    return (registered + legacy).distinctBy { it.id }
}

/** Only legacy directory groups persist across bounded discovery; native deletions remain effective. */
fun retainDirectoryProjects(discovered: List<ProjectInfo>, cached: List<ProjectInfo>): List<ProjectInfo> =
    (discovered + cached.filter { it.id.startsWith("cwd:") }).distinctBy { it.id }
