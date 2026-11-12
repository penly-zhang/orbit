package dev.codexpocket

import dev.codexpocket.core.*
import org.junit.Test
import kotlin.test.*

class ConnectionFormTest {
    @Test fun `save draft permits missing credentials but connection requires each hop`() {
        val profile = HostProfile(host = "work.example", username = "user", jumpHosts = listOf(JumpHost("hop", "gateway.example", username = "user")))
        assertTrue(connectionFormErrors(profile, Credentials(), false).isEmpty())
        val errors = connectionFormErrors(profile, Credentials(), true)
        assertTrue("credentials" in errors)
        assertTrue("hop:hop" in errors)
        assertTrue(connectionFormErrors(profile, Credentials(password = "example", jumps = mapOf("hop" to JumpCredentials(password = "example"))), true).isEmpty())
    }
    @Test fun `invalid hidden configuration blocks saving with actionable field keys`() {
        val profile = HostProfile(host = "work.example", username = "user", port = 0, proxyEnabled = true, proxyPort = 70000, routeMode = "remoteSsh")
        assertEquals(setOf("port", "proxy", "target"), connectionFormErrors(profile, Credentials(), false).keys)
    }
}
