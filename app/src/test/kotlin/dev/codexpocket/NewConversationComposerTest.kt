package dev.codexpocket

import dev.codexpocket.core.*
import org.junit.Test
import kotlin.test.*

class NewConversationComposerTest {
    private val project = ProjectInfo("p", "Project", listOf("/project"))
    @Test fun `first message composer uses only new draft and cannot stop or queue into an older task`() {
        val image = DraftImage("image", "/private/draft.jpg", "image.jpg", 10)
        val source = PocketState(project = project, connected = true,
            thread = ThreadInfo("old", "Old", "/project"), threadReady = true, activeTurn = "running",
            draft = "old draft", queuedMessages = listOf(QueuedMessage("old queue")), sendingComments = true,
            settingsApplying = true, newConversationDraft = "new draft", newImages = listOf(image))
        val composer = newConversationComposerState(source)
        assertEquals("new draft", composer.draft)
        assertEquals(listOf(image), composer.draftImages)
        assertNull(composer.thread); assertNull(composer.activeTurn); assertNull(composer.settings)
        assertTrue(composer.queuedMessages.isEmpty()); assertFalse(composer.settingsApplying); assertFalse(composer.sendingComments)
    }
    @Test fun `first send requires an available engine while existing sends still require an attached thread`() {
        val s = PocketState(project = project, connected = true, newConversationDraft = "hello", availableEngines = listOf("codex"))
        val composer = newConversationComposerState(s)
        assertTrue(composerSendEnabled(composer, true, false, true))
        assertFalse(composerSendEnabled(composer.copy(connected = false), true, false, true))
        assertFalse(composerSendEnabled(composer.copy(newConversationEngine = "unavailable"), true, false, true))
        assertFalse(composerSendEnabled(composer, false, false, true))
        assertFalse(composerSendEnabled(composer.copy(imagesLoading = true), true, false, true))
        assertFalse(composerSendEnabled(composer.copy(sending = true), true, false, true))
    }
    @Test fun `local attachment only drafts can choose a destination but empty drafts cannot send`() {
        val s = newConversationComposerState(PocketState(newImages = listOf(DraftImage("i", "/private/image.jpg", "image.jpg", 10))))
        assertTrue(composerSendEnabled(s, true, false, true))
        assertFalse(composerSendEnabled(s.copy(draftImages = emptyList()), true, false, true))
    }
}
