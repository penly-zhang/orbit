package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*

class ParsingTest {
    @Test fun `saved connection switches preserve enabled state across upgrades`() {
        val disabled = HostProfile(id = "off", autoConnect = false)
        assertFalse(json.decodeFromString<HostProfile>(json.encodeToString(HostProfile.serializer(), disabled)).autoConnect)
        assertTrue(json.decodeFromString<HostProfile>("""{"id":"legacy"}""").autoConnect)
    }
    @Test fun `reconnection backs off and remains bounded on extended outages`() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 15L, 30L), (1..6).map(::reconnectDelaySeconds))
        assertEquals(30L, reconnectDelaySeconds(1000))
    }
    @Test fun `existing saved profiles keep direct connections after proxy upgrade`() {
        val profile = json.decodeFromString<HostProfile>("""{"id":"old","host":"192.0.2.10","username":"fixture"}""")
        assertFalse(profile.proxyEnabled)
        assertEquals(1055, profile.proxyPort)
        val proxy = profile.copy(proxyEnabled = true, proxyHost = "127.0.0.1", proxyPort = 1080)
        assertEquals(proxy, json.decodeFromString<HostProfile>(json.encodeToString(HostProfile.serializer(), proxy)))
    }
    @Test fun `conversation file links resolve locally with citations and encoded names`() {
        assertEquals("/project/src/中文 file.kt", remoteFileLink("src/中文%20file.kt:12:3", "/project"))
        assertEquals("/tmp/name with space.md", remoteFileLink("file:///tmp/name%20with%20space.md#L2", "/project"))
        assertEquals("/tmp/result.txt", remoteFileLink("sandbox:/tmp/result.txt", "/project"))
        assertEquals("/project/README.md", remoteFileLink("README.md:4", "/project"))
        assertFalse(isRemoteFileLink("https://example.com/file.kt"))
        assertFalse(isRemoteFileLink("mailto:someone@example.com"))
        assertFalse(isRemoteFileLink("#section"))
        assertFailsWith<IllegalArgumentException> { remoteFileLink("file://other-computer/tmp/a", "/project") }
    }
    @Test fun `git porcelain handles renamed paths and filenames containing whitespace`() {
        val changes = parseGitStatus(" M file with space.kt\u0000R  新名字.kt\u0000old name.kt\u0000?? -new-file\u0000")
        assertEquals(3, changes.size)
        assertEquals("file with space.kt", changes[0].path)
        assertEquals("新名字.kt", changes[1].path)
        assertEquals("old name.kt", changes[1].oldPath)
        assertEquals("??", changes[2].status)
    }
    @Test fun `remote paths cannot inject shell commands`() {
        val malicious = "/tmp/project'; touch /tmp/pocket-injected; echo '"
        val result = ProcessBuilder("sh", "-c", "printf '%s' ${shellQuote(malicious)}").start()
        assertEquals(malicious, result.inputStream.bufferedReader().readText())
        assertEquals(0, result.waitFor())
        assertFailsWith<IllegalArgumentException> { proxyCommand("/tmp/a\ncommand") }
    }
}
