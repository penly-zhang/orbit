package dev.codexpocket.core

import kotlinx.serialization.json.JsonObject

/** Registered projects and legacy directory groups coexist; native registration never deletes history. */
fun combineProjectSources(
    registered: List<ProjectInfo>, recentThreads: List<JsonObject>,
    directoryRoots: Map<String, String> = emptyMap(), cached: List<ProjectInfo> = emptyList(),
): List<ProjectInfo> {
    val knownRoots = cached.filter { it.id.startsWith("cwd:") }
        .flatMap { project -> project.threadCwds.map { it to project.id.removePrefix("cwd:") } }.toMap() + directoryRoots
    val ids = registered.map { it.id }.toSet()
    val directories = recentThreads.filter { it.text("projectId") !in ids }
        .mapNotNull { it.text("cwd").takeIf(String::isNotBlank) } +
        cached.filter { it.id.startsWith("cwd:") }.flatMap { it.threadCwds }
    val legacy = directories.distinct().groupBy { knownRoots[it] ?: it }.map { (root, cwds) ->
        val native = registered.firstOrNull { project -> project.roots.any { (knownRoots[it] ?: it).trimEnd('/') == root.trimEnd('/') } }
        // Unassigned history may share a root with a native project. Keep both
        // groups: filtering by projectId cannot retrieve those older threads.
        val name = native?.let { "${it.name} · 历史会话" }
            ?: root.trimEnd('/').substringAfterLast('/').ifBlank { root }
        ProjectInfo("cwd:$root", name, listOf(root), (listOf(root) + cwds).distinct())
    }
    return (registered + legacy).distinctBy { it.id }
}

/** Only legacy directory groups persist across bounded discovery; native deletions remain effective. */
fun retainDirectoryProjects(discovered: List<ProjectInfo>, cached: List<ProjectInfo>): List<ProjectInfo> {
    val covered = discovered.filter { it.id.startsWith("cwd:") }.flatMap { it.threadCwds }.toSet()
    return (discovered + cached.filter { it.id.startsWith("cwd:") && !covered.containsAll(it.threadCwds) }).distinctBy { it.id }
}

/** Match only verified directory membership, never a path prefix (nested repositories stay separate). */
fun canonicalProject(project: ProjectInfo, discovered: List<ProjectInfo>): ProjectInfo =
    discovered.find { it.id == project.id }
        ?: discovered.find { candidate -> project.id.startsWith("cwd:") && candidate.id.startsWith("cwd:") &&
            candidate.threadCwds.containsAll(project.threadCwds) }
        ?: project

/** Resolve all directories in one SSH exec; missing Git or inaccessible paths keep their cwd groups. */
internal fun projectRootsCommand(directories: List<String>): String = withWorkingGit(
    "for pocket_cwd in ${directories.joinToString(" ", transform = ::shellQuote)}; do " +
        "pocket_root=\$(\"\$pocket_candidate\" -C \"\$pocket_cwd\" rev-parse --show-toplevel 2>/dev/null) || pocket_root=\"\$pocket_cwd\"; " +
        "printf '%s\\000%s\\000' \"\$pocket_cwd\" \"\$pocket_root\"; done; exit 0",
)

internal fun parseProjectRoots(output: String): Map<String, String> = output.split('\u0000').dropLast(1)
    .chunked(2).filter { it.size == 2 && it[0].isNotBlank() && it[1].startsWith('/') && it[1].none(Char::isISOControl) }
    .associate { it[0] to it[1] }
