package dev.codexpocket.core

import kotlinx.serialization.decodeFromString
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class GitReviewTest {
    @Test fun `review skips broken SSH python while retaining the selected Git`() {
        val root = Files.createTempDirectory("orbit-python-review").toFile()
        val broken = Files.createTempDirectory("orbit-broken-python").toFile()
        try {
            git(root, "init", "-q")
            root.resolve("hello.txt").writeText("hello\n")
            broken.resolve("python3").apply {
                writeText("#!/bin/sh\necho 'xcrun: invalid active developer path' >&2\nexit 72\n")
                setExecutable(true)
            }
            val process = ProcessBuilder("/bin/sh", "-c", gitReviewCommand(root.path, "overview")).apply {
                environment()["PATH"] = broken.path + ":" + environment()["PATH"].orEmpty()
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output + error)
            assertEquals(listOf("hello.txt"), json.decodeFromString<ReviewOverview>(output).changes.map { it.path })
        } finally { root.deleteRecursively(); broken.deleteRecursively() }
    }
    private fun git(root: File, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), output)
    }
    private fun review(root: File, home: File, action: String, token: String = "", paths: List<String> = emptyList()): String {
        val p = ProcessBuilder("/bin/sh", "-c", gitReviewCommand(root.path, action, token, paths)).apply { environment()["CODEX_HOME"] = home.path }.start()
        val output = p.inputStream.bufferedReader().readText()
        val error = p.errorStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), output + error)
        return output
    }
    @Test fun `overview separates staged worktree and untracked and turn snapshot excludes preexisting edits`() {
        val root = Files.createTempDirectory("pocket review ' 中文 ").toFile()
        val home = Files.createTempDirectory("pocket-review-home").toFile()
        try {
            git(root, "init", "-q")
            root.resolve("a.kt").writeText("one\n")
            root.resolve("b.kt").writeText("stable\n")
            git(root, "add", "."); git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "base")
            root.resolve("a.kt").writeText("one\nbefore staged\n"); git(root, "add", "a.kt")
            root.resolve("a.kt").appendText("before working\n")
            root.resolve("large.bin").writeBytes(ByteArray(270000) { 1 })
            val overview = json.decodeFromString<ReviewOverview>(review(root, home, "overview"))
            val a = overview.changes.first { it.path == "a.kt" }
            assertTrue(a.staged); assertTrue(a.working)
            assertEquals(1, a.stagedAdded); assertEquals(1, a.workingAdded)
            val baseline = json.decodeFromString<ReviewBaseline>(review(root, home, "begin"))
            root.resolve("a.kt").appendText("+++during")
            root.resolve("new 文件.kt").writeText("new\n")
            val report = json.decodeFromString<TaskReviewReport>(review(root, home, "finish", baseline.token))
            assertEquals(setOf("a.kt", "new 文件.kt"), report.changes.map { it.path }.toSet())
            assertEquals(1, report.changes.first { it.path == "a.kt" }.added, "Preexisting staged and working edits must not be counted as turn edits")
            assertContains(report.diffs.getValue("a.kt"), "+++during")
            assertTrue(report.partial, "Large omitted files are disclosed")
            root.resolve("a.kt").appendText("later external edit")
            val frozen = json.decodeFromString<TaskReviewReport>(review(root, home, "read", baseline.token))
            assertEquals(report, frozen, "Completed turn review must not drift with later edits")
        } finally { root.deleteRecursively(); home.deleteRecursively() }
    }
    @Test fun `comment fingerprints reject changed files and viewed flags invalidate on edits`() {
        val c = ReviewComment(root = "/p", path = "a.kt", section = "工作区", newLine = 3, body = "fix", fingerprint = "original")
        assertTrue(commentLocationValid(c, "original"))
        assertFalse(commentLocationValid(c, "modified"))
        assertFalse(commentLocationValid(c.copy(stale = true), "original"))
        val change = GitChange("a.kt", status = "MM", fingerprint = "original")
        val staged = reviewViewKey("workspace", null, "a.kt", "staged")
        val working = reviewViewKey("workspace", null, "a.kt", "working")
        assertFalse(changeViewed(change, "workspace", null, mapOf(staged to "original")))
        val viewed = mapOf(staged to "original", working to "original")
        assertTrue(changeViewed(change, "workspace", null, viewed))
        assertFalse(changeViewed(change.copy(fingerprint = "modified"), "workspace", null, viewed))
    }
}
