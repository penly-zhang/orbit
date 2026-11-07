package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*
import java.io.ByteArrayInputStream
import java.io.IOException

class SshRoutingTest {
    @Test fun `SSH aliases inherit defaults and resolve multiple ProxyJump hops in order`() {
        val config = SshConfigImport("""
            Host gpu !gpu-test
                HostName = "10.0.0.30" # target
                User trainer
                Port 2200
                ProxyJump alice@entry:2222,inner
                IdentityFile ~/.ssh/gpu_key
            Host entry
                HostName bastion.example
                User ignored
            Host inner
                HostName 10.0.0.20
            Host *
                User shared
                Port 22
        """.trimIndent())
        val route = config.resolve("gpu")
        assertEquals("10.0.0.30", route.host); assertEquals(2200, route.port); assertEquals("trainer", route.username)
        assertEquals(listOf("bastion.example", "10.0.0.20"), route.jumps.map { it.host })
        assertEquals(listOf("alice", "shared"), route.jumps.map { it.username })
        assertEquals(2222, route.jumps.first().port)
        assertEquals("~/.ssh/gpu_key", route.keyFile)
        assertEquals("gpu-test", config.resolve("gpu-test").host)
    }
    @Test fun `SSH config rejects cycles unsupported includes commands and malformed routes`() {
        assertFailsWith<IllegalArgumentException> { SshConfigImport("Include secrets/*") }
        assertFailsWith<IllegalArgumentException> { SshConfigImport("Match exec command") }
        assertFailsWith<IllegalArgumentException> { SshConfigImport("Host \"broken") }
        assertFails { SshConfigImport("Host a\n ProxyJump b\nHost b\n ProxyJump a").resolve("a") }
        assertFails { SshConfigImport("Host a\n ProxyCommand dangerous-command").resolve("a") }
        assertFails { SshConfigImport("Host a\n Port nope").resolve("a") }
        val route = SshConfigImport("Host a\n ProxyJump user@[::1]:2201\n User target").resolve("a")
        assertEquals("::1", route.jumps.single().host); assertEquals(2201, route.jumps.single().port)
    }
    @Test fun `remote SSH commands preserve arguments without local expansion`() {
        val cmd = "printf '%s' '\$(touch /tmp/never); x'"
        val remote = routedCommand("remoteSsh", "user@host-b", cmd)
        assertTrue(remote.contains("BatchMode=yes")); assertTrue(remote.contains("StrictHostKeyChecking=yes"))
        assertTrue(remote.endsWith(shellQuote(cmd)))
        assertFailsWith<IllegalArgumentException> { routedCommand("remoteSsh", "-oProxyCommand=evil", cmd) }
        assertFailsWith<IllegalArgumentException> { routedCommand("remoteSsh", "host b", cmd) }
    }
    @Test fun `login shell skips startup banners without consuming binary protocol bytes`() {
        val marker = "POCKET_READY_abc:"
        val data = ByteArrayInputStream(("Welcome to A\r\nWelcome to B\n$marker" + "HTTP/1.1").toByteArray())
        skipShellPreamble(data, marker.toByteArray())
        assertEquals("HTTP/1.1", data.readBytes().toString(Charsets.UTF_8))
        assertFailsWith<IOException> { skipShellPreamble(ByteArrayInputStream("password?".toByteArray()), marker.toByteArray()) }
        assertFailsWith<IOException> { skipShellPreamble(ByteArrayInputStream(ByteArray(100)), marker.toByteArray(), 50) }
    }
}
