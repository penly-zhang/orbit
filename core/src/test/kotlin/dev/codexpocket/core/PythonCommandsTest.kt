package dev.codexpocket.core

import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class PythonCommandsTest {
    @Test fun `an executable python stub cannot hide a working interpreter and arguments stay quoted`() {
        val dir = Files.createTempDirectory("orbit python ' 中文 ").toFile()
        try {
            dir.resolve("python3").apply {
                writeText("#!/bin/sh\nif [ \"\$1\" = --version ]; then echo 'Python 3.12'; else echo 'xcrun: invalid active developer path' >&2; exit 72; fi\n")
                setExecutable(true)
            }
            val working = dir.resolve("working python").apply {
                writeText("#!/bin/sh\nif [ \"\$1\" = -c ]; then exit 0; fi\nprintf '%s\\n' \"\$@\"\n")
                setExecutable(true)
            }
            val values = listOf("-u", "-c", "print('中文')", "a ' b; literal")
            val command = withWorkingPython(values.joinToString(" ", transform = ::shellQuote), listOf(working.path))
            val process = ProcessBuilder("/bin/sh", "-c", command).apply { environment()["PATH"] = dir.path }.start()
            val output = process.inputStream.bufferedReader().readLines()
            assertEquals(0, process.waitFor())
            assertEquals(values, output)
            val missing = ProcessBuilder("/bin/sh", "-c", withWorkingPython("-c pass", emptyList())).apply { environment()["PATH"] = dir.path }.start()
            assertEquals(127, missing.waitFor())
            assertEquals("", missing.inputStream.bufferedReader().readText())
            assertContains(missing.errorStream.bufferedReader().readText(), "没有可运行的 Python")
        } finally { dir.deleteRecursively() }
    }
}
