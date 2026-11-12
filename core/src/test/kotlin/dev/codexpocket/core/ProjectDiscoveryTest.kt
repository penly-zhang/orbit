package dev.codexpocket.core

import org.junit.Test
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
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

    @Test fun `subdirectory sessions share their Git root while keeping exact query directories`() {
        val root = "/work/repo"
        val rows = listOf(obj("cwd" to "$root/app"), obj("cwd" to "$root/core"), obj("cwd" to root), obj("cwd" to "/notes"))
        val result = combineProjectSources(emptyList(), rows, mapOf("$root/app" to root, "$root/core" to root))
        assertEquals(listOf("cwd:$root", "cwd:/notes"), result.map { it.id })
        assertEquals("repo", result.first().name)
        assertEquals(listOf(root), result.first().roots)
        assertEquals(listOf(root, "$root/app", "$root/core"), result.first().threadCwds)
    }

    @Test fun `cached subdirectory groups migrate without hiding history or nested repositories`() {
        val old = ProjectInfo("cwd:/work/repo/app", "app", listOf("/work/repo/app"))
        val nested = ProjectInfo("cwd:/work/repo/vendor", "vendor", listOf("/work/repo/vendor"))
        val discovered = combineProjectSources(emptyList(), emptyList(), mapOf("/work/repo/app" to "/work/repo"), listOf(old, nested))
        val retained = retainDirectoryProjects(discovered, listOf(old, nested))
        assertEquals(listOf("cwd:/work/repo", nested.id), retained.map { it.id })
        assertEquals(retained.first(), canonicalProject(old, retained))
        assertEquals(nested, canonicalProject(nested, retained))
        val unknown = ProjectInfo("cwd:/work/repo/unknown", "unknown", listOf("/work/repo/unknown"))
        assertEquals(unknown, canonicalProject(unknown, retained))
    }

    @Test fun `unassigned subdirectory history stays separate from native project assignments`() {
        val rows = listOf(obj("cwd" to "/home/project/Orbit/app"), obj("cwd" to "/home/project/Orbit/core", "projectId" to orbit.id))
        val result = combineProjectSources(listOf(orbit), rows, mapOf("/home/project/Orbit/app" to orbit.roots.single()))
        assertEquals(listOf(orbit.id, "cwd:/home/project/Orbit"), result.map { it.id })
        assertEquals("Orbit · 历史会话", result.last().name)
        assertEquals(listOf(orbit.roots.single(), "/home/project/Orbit/app"), result.last().threadCwds)
    }

    @Test fun `old cached projects decode with their original query directories`() {
        val old = json.decodeFromString<ProjectInfo>("""{"id":"cwd:/work/repo","name":"repo","roots":["/work/repo"]}""")
        assertEquals(old.roots, old.threadCwds)
    }

    @Test fun `known Git grouping survives temporary failure to resolve remote roots`() {
        val cached = ProjectInfo("cwd:/work/repo", "repo", listOf("/work/repo"), listOf("/work/repo", "/work/repo/app"))
        assertEquals(listOf(cached), combineProjectSources(emptyList(), listOf(obj("cwd" to "/work/repo/app")), cached = listOf(cached)))
    }

    @Test fun `root resolution uses real Git including nested repositories and linked worktrees`() {
        val temp = Files.createTempDirectory("orbit projects ' 中文 ").toRealPath().toFile()
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        }
        try {
            val repo = temp.resolve("repo").apply { mkdirs() }
            git("init", repo.path)
            git("-C", repo.path, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.com", "-c", "commit.gpgSign=false", "commit", "--allow-empty", "-m", "fixture")
            val child = repo.resolve("app/中文 ' \$(touch unexpected)").apply { mkdirs() }
            val nested = repo.resolve("vendor").apply { mkdirs() }
            git("init", nested.path)
            val worktree = temp.resolve("worktree")
            git("-C", repo.path, "worktree", "add", "--detach", worktree.path)
            val workChild = worktree.resolve("src").apply { mkdirs() }
            val notes = temp.resolve("notes").apply { mkdirs() }
            val missing = temp.resolve("missing")
            val dirs = listOf(child, nested, workChild, notes, missing).map { it.path }
            val process = ProcessBuilder("/bin/sh", "-c", projectRootsCommand(dirs)).directory(temp).start()
            val result = parseProjectRoots(process.inputStream.bufferedReader().readText())
            assertEquals(0, process.waitFor())
            assertEquals(mapOf(child.path to repo.path, nested.path to nested.path, workChild.path to worktree.path, notes.path to notes.path, missing.path to missing.path), result)
            assertFalse(temp.resolve("unexpected").exists())
        } finally { temp.deleteRecursively() }
    }
}
