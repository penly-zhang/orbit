package dev.codexpocket.core

import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class GitCommandsTest {
    @Test fun `broken system git falls back without changing developer configuration`() {
        val dir = Files.createTempDirectory("pocket git ' 中文 ").toFile()
        try {
            dir.resolve("git").apply { writeText("#!/bin/sh\necho 'xcrun: invalid active developer path' >&2\nexit 72\n"); setExecutable(true) }
            val working = dir.resolve("working git").apply { writeText("#!/bin/sh\nif [ \"\$1\" = --version ]; then echo 'git version fixture'; else printf '%s\\n' \"\$@\"; fi\n"); setExecutable(true) }
            val process = ProcessBuilder("/bin/sh", "-c", gitReadCommand(listOf("-C", "a ' 中文", "status"), listOf(working.path))).apply { environment()["PATH"] = dir.path }.start()
            assertEquals(0, process.waitFor())
            assertEquals(listOf("-C", "a ' 中文", "status"), process.inputStream.bufferedReader().readLines())
            val missing = ProcessBuilder("/bin/sh", "-c", gitReadCommand(listOf("status"), emptyList())).apply { environment()["PATH"] = dir.path }.start()
            assertEquals(127, missing.waitFor())
            assertContains(missing.errorStream.bufferedReader().readText(), "电脑上没有可运行的 Git")
        } finally { dir.deleteRecursively() }
    }
    @Test fun `git errors distinguish missing tools and a non repository`() {
        assertContains(gitFailureMessage("fatal: not a git repository"), "当前目录不是 Git 仓库")
        assertContains(gitFailureMessage("xcrun: invalid active developer path"), "开发工具路径无效")
    }
}
