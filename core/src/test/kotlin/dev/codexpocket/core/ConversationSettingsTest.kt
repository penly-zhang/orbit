package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*

class ConversationSettingsTest {
    @Test fun `native labels keep model catalog effort IDs and permission reviewer semantics`() {
        val model = modelOption(obj("model" to "model-x", "displayName" to "GPT-6.1 Sol", "defaultReasoningEffort" to "medium",
            "supportedReasoningEfforts" to listOf("low", "medium", "high", "xhigh", "max", "ultra").map { obj("reasoningEffort" to it, "description" to it) }))
        assertEquals(listOf("低", "中", "高", "极高", "Max", "Ultra"), model.efforts.map { effortName(it.id) })
        assertEquals("6.1 Sol", compactModelName(model.name))
        assertEquals("custom-future", effortName("custom-future"))
        assertEquals("默认权限", permissionName(":workspace"))
        val automatic = conversationSettings(obj("activePermissionProfile" to obj("id" to ":workspace"), "approvalsReviewer" to "auto_review"))
        assertEquals("自动审查", automatic.permissionLabel)
        assertEquals("auto_review", permissionSettingsPatch(PermissionOption(":workspace", "自动审查", "", true, "auto_review")).text("approvalsReviewer"))
    }
    @Test fun `model and reasoning changes preserve mode instructions and override both model selectors`() {
        val current = conversationSettings(obj("model" to "old", "reasoningEffort" to "high", "sandbox" to obj("type" to "workspaceWrite"), "approvalPolicy" to "on-request",
            "collaborationMode" to obj("mode" to "plan", "settings" to obj("model" to "old", "reasoning_effort" to "high", "developer_instructions" to "keep this"))))
        val patch = modelSettingsPatch(current, "new", "medium")
        assertEquals("new", patch.text("model"))
        assertEquals("medium", patch.text("effort"))
        assertEquals("plan", patch.objectAt("collaborationMode").text("mode"))
        val mode = patch.objectAt("collaborationMode").objectAt("settings")
        assertEquals("new", mode.text("model")); assertEquals("medium", mode.text("reasoning_effort")); assertEquals("keep this", mode.text("developer_instructions"))
        assertFalse(patch.containsKey("permissions"))
    }
    @Test fun `permission selection changes the real permission profile and approval policy atomically`() {
        fun option(id: String, allowed: Boolean = true) = PermissionOption(id, id, "", allowed)
        val full = permissionSettingsPatch(option(":danger-full-access"))
        assertEquals(":danger-full-access", full.text("permissions")); assertEquals("never", full.text("approvalPolicy"))
        val normal = permissionSettingsPatch(option(":workspace"))
        assertEquals("on-request", normal.text("approvalPolicy")); assertFalse(normal.containsKey("sandboxPolicy"))
        val custom = permissionSettingsPatch(option("custom-company"))
        assertEquals(1, custom.size)
        assertFailsWith<IllegalArgumentException> { permissionSettingsPatch(option("blocked", false)) }
    }
    @Test fun `automatic approval and manual approval share sandbox but use separate reviewers`() {
        val auto = PermissionOption(":workspace", "自动审查", "", true, "auto_review")
        val manual = auto.copy(name = "默认权限", reviewer = "user")
        val patch = permissionSettingsPatch(auto)
        assertEquals(":workspace", patch.text("permissions"))
        assertEquals("on-request", patch.text("approvalPolicy"))
        assertEquals("auto_review", patch.text("approvalsReviewer"))
        val current = conversationSettings(obj("activePermissionProfile" to obj("id" to ":workspace"), "sandboxPolicy" to obj("type" to "workspaceWrite"), "approvalsReviewer" to "auto_review", "approvalPolicy" to "on-request"))
        assertEquals("自动审查", current.permissionLabel)
        assertTrue(permissionSelected(current, auto))
        assertFalse(permissionSelected(current, manual))
        assertTrue(settingsMatch(current, patch))
        assertFalse(settingsMatch(current, permissionSettingsPatch(manual)))
    }
    @Test fun `legacy sandbox records still show the matching permission selection`() {
        val current = conversationSettings(obj("sandbox" to obj("type" to "workspaceWrite"), "approvalsReviewer" to "user"))
        assertEquals("默认权限", current.permissionLabel)
        assertTrue(permissionSelected(current, PermissionOption(":workspace", "", "", true)))
        assertFalse(permissionSelected(current, PermissionOption(":danger-full-access", "", "", true)))
    }
}
