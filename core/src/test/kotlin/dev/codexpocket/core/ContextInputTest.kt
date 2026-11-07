package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*
import kotlinx.serialization.json.*

class ContextInputTest {
    @Test fun `mixed context uses native skill input and concise references without phone paths`() {
        val input = contextUserInput("检查错误", listOf(
            ContextInput("1", "app.kt", "/repo/app.kt", startLine = 10, endLine = 25),
            ContextInput("2", "review", "/skills/review/SKILL.md", "skill"),
            ContextInput("3", "logs", "/repo/logs", "directory"),
            ContextInput("4", "search", "docs/search", "tool"),
        ))
        assertEquals(2, input.size)
        assertEquals("检查错误\n\n@/repo/app.kt:10-25\n@/repo/logs\n使用 MCP 工具 docs/search", input[0].text("text"))
        assertEquals(obj("type" to "skill", "name" to "review", "path" to "/skills/review/SKILL.md"), input[1])
    }
    @Test fun `skill only messages remain visible in conversation history`() {
        val item = chatItem(obj("id" to "user", "type" to "userMessage", "content" to listOf(obj("type" to "skill", "name" to "review", "path" to "/skill"))))
        assertEquals("\$review", item?.text)
        assertFailsWith<IllegalArgumentException> { contextUserInput("", listOf(ContextInput("1", "local", localPath = "/phone/file", bytes = 10))) }
    }
    @Test fun `invalid line ranges item counts and attachment budgets fail before upload`() {
        assertFailsWith<IllegalArgumentException> { validateContext(listOf(ContextInput("1", "f", "/f", startLine = 0))) }
        assertFailsWith<IllegalArgumentException> { validateContext(listOf(ContextInput("1", "f", "/f", startLine = 10, endLine = 2))) }
        assertFailsWith<IllegalArgumentException> { validateContext(listOf(ContextInput("1", "f", "/f", startLine = 1, endLine = 501))) }
        assertFailsWith<IllegalArgumentException> { validateContext((1..9).map { ContextInput("$it", "f", "/f") }) }
        assertFailsWith<IllegalArgumentException> { validateContext((1..4).map { ContextInput("$it", "f", localPath = "/phone/$it", bytes = MAX_ATTACHMENT_BYTES) }) }
        assertFailsWith<IllegalArgumentException> { validateContext(listOf(ContextInput("1", "bad\nname", "/f"))) }
        validateContext(listOf(ContextInput("1", "f", "/f", startLine = 1, endLine = 500)))
    }
    @Test fun `native tool catalogs preserve disable auth and partial failures`() {
        val skills = parseSkills(obj("data" to listOf(obj("skills" to listOf(obj("name" to "one", "path" to "/skill", "enabled" to false, "description" to "desc")), "errors" to listOf(obj("message" to "broken skill"))))))
        assertFalse(skills.first.single().enabled); assertEquals("broken skill", skills.second)
        val servers = parseServers(obj("data" to listOf(obj("name" to "docs", "runtimeStatus" to "failed", "authStatus" to "notLoggedIn", "toolsError" to "timeout", "tools" to obj()))))
        assertEquals("failed", servers.single().status); assertEquals("notLoggedIn", servers.single().auth); assertEquals("timeout", servers.single().error)
        val plugins = parsePlugins(obj("marketplaces" to listOf(obj("plugins" to listOf(obj("id" to "1", "name" to "installed", "installed" to true, "enabled" to true), obj("id" to "2", "installed" to false))))))
        assertEquals("installed", plugins.single().name)
    }
}
