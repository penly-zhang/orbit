package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*

class ProjectDiscoveryTest {
    private val orbit = ProjectInfo("orbit-id", "Orbit", listOf("/home/project/Orbit"))

    @Test fun `adding the first native project keeps legacy directories and their stable ids`() {
        val rows = listOf(obj("cwd" to "/home/Projects"), obj("cwd" to "/home"), obj("cwd" to "/notes/ai_infra"), obj("cwd" to "/home/Projects"))
        val before = combineProjectSources(emptyList(), rows)
        val after = combineProjectSources(listOf(orbit), rows)
        assertEquals(orbit, after.first())
        assertEquals(before, after.drop(1))
        assertEquals(listOf("Projects", "home", "ai_infra"), after.drop(1).map { it.name })
        assertEquals(listOf("cwd:/home/Projects", "cwd:/home", "cwd:/notes/ai_infra"), after.drop(1).map { it.id })
    }

    @Test fun `assigned native threads do not create duplicate directory groups`() {
        val rows = listOf(obj("cwd" to "/home/project/Orbit", "projectId" to orbit.id), obj("cwd" to "/worktree/Orbit", "projectId" to orbit.id))
        assertEquals(listOf(orbit), combineProjectSources(listOf(orbit), rows))
    }

    @Test fun `unassigned history sharing a native root stays accessible by cwd`() {
        val result = combineProjectSources(listOf(orbit), listOf(obj("cwd" to orbit.roots.single(), "projectId" to null)))
        assertEquals(2, result.size)
        assertEquals("cwd:/home/project/Orbit", result.last().id)
        assertEquals("Orbit · 历史会话", result.last().name)
        assertEquals(orbit.roots, result.last().roots)
    }

    @Test fun `recent history changes retain cached directory groups without resurrecting native deletions`() {
        val legacy = ProjectInfo("cwd:/old/work", "work", listOf("/old/work"))
        val removedNative = ProjectInfo("removed-native", "Removed", listOf("/removed"))
        assertEquals(listOf(orbit, legacy), retainDirectoryProjects(listOf(orbit), listOf(legacy, removedNative)))
        assertEquals(listOf(orbit, legacy.copy(name = "Updated")), retainDirectoryProjects(listOf(orbit, legacy.copy(name = "Updated")), listOf(legacy)))
    }
}
