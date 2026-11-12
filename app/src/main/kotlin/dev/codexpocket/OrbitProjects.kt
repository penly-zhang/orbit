package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
internal data class OrbitProjectPreferences(
    val order: List<String> = emptyList(),
    val added: List<ProjectInfo> = emptyList(),
    val removed: Set<String> = emptySet(),
    val removedProjects: List<ProjectInfo> = emptyList(),
) {
    fun apply(projects: List<ProjectInfo>): List<ProjectInfo> {
        val positions = order.withIndex().associate { it.value to it.index }
        return (added + projects).distinctBy { it.id }.filterNot { it.id in removed }
            .sortedBy { positions[it.id] ?: Int.MAX_VALUE }
    }
    fun move(projects: List<ProjectInfo>, projectId: String, offset: Int): OrbitProjectPreferences {
        val ids = apply(projects).map { it.id }.toMutableList()
        val from = ids.indexOf(projectId)
        val to = from + offset
        if (offset !in listOf(-1, 1) || from < 0 || to !in ids.indices) return this
        ids.add(to, ids.removeAt(from))
        return copy(order = ids)
    }
    fun add(project: ProjectInfo) = copy(added = added.filterNot { it.id == project.id } + project, removed = removed - project.id, removedProjects = removedProjects.filterNot { it.id == project.id })
    fun remove(project: ProjectInfo) = copy(added = added.filterNot { it.id == project.id }, removed = removed + project.id, removedProjects = removedProjects.filterNot { it.id == project.id } + project)
}

internal class OrbitProjects(private val vault: PocketStorage, private val host: String) {
    private val key = "orbitProjects:$host"
    private var preferences = runCatching { vault.get(key)?.let { json.decodeFromString<OrbitProjectPreferences>(it) } }.getOrNull() ?: OrbitProjectPreferences()
    fun apply(projects: List<ProjectInfo>) = preferences.apply(projects)
    fun move(projects: List<ProjectInfo>, projectId: String, offset: Int) { save(preferences.move(projects, projectId, offset)) }
    fun removedAt(root: String) = preferences.removedProjects.firstOrNull { root in it.roots }
    fun add(project: ProjectInfo) { save(preferences.add(project)) }
    fun remove(project: ProjectInfo) { save(preferences.remove(project)) }
    private fun save(value: OrbitProjectPreferences) { vault.put(key, json.encodeToString(value)); preferences = value }
}
