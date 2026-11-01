package io.github.alextherajunction.codexweb

import io.github.alextherajunction.codexweb.protocol.AppServerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDraftsTest {
    private fun state(threadId: String? = null) = AppUiState(
        snapshot = AppServerSnapshot(selectedThreadId = threadId),
    ).withThreadComposerDraft(threadId)

    @Test
    fun `switching threads restores each composer draft`() {
        val first = state("thread-a").withComposerDraft("first draft")
        val second = first.copy(snapshot = first.snapshot.copy(selectedThreadId = "thread-b"))
            .withThreadComposerDraft("thread-b")
            .withComposerDraft("second draft")

        val restoredFirst = second.copy(snapshot = second.snapshot.copy(selectedThreadId = "thread-a"))
            .withThreadComposerDraft("thread-a")

        assertEquals("first draft", first.composerText)
        assertEquals("second draft", second.composerText)
        assertEquals("first draft", restoredFirst.composerText)
        assertEquals("second draft", restoredFirst.composerDrafts["thread-b"])
    }

    @Test
    fun `automatically selected threads become the active draft key`() {
        val autoSelected = AppUiState(
            snapshot = AppServerSnapshot(selectedThreadId = "thread-auto"),
        ).withAdoptedSelectedThreadDraft()
        val typed = autoSelected.withComposerDraft("auto-selected draft")

        assertEquals("thread-auto", autoSelected.composerDraftKey)
        assertEquals("auto-selected draft", typed.composerDrafts["thread-auto"])
    }

    @Test
    fun `sending clears the submitted thread without clearing another draft`() {
        val first = state("thread-a").withComposerDraft("send me")
        val second = first.copy(snapshot = first.snapshot.copy(selectedThreadId = "thread-b"))
            .withThreadComposerDraft("thread-b")
            .withComposerDraft("keep me")

        val afterSend = second.afterComposerSend("thread-a", "send me")

        assertEquals("keep me", afterSend.composerText)
        assertEquals("thread-b", afterSend.composerDraftKey)
        assertFalse(afterSend.composerDrafts.containsKey("thread-a"))
        assertEquals("keep me", afterSend.composerDrafts["thread-b"])
    }

    @Test
    fun `sending from new chat clears the draft after a thread is created`() {
        val drafting = state().withComposerDraft("new chat message")
        val started = drafting.copy(
            snapshot = drafting.snapshot.copy(selectedThreadId = "thread-new"),
        )

        val afterSend = started.afterComposerSend(NEW_CHAT_COMPOSER_DRAFT_KEY, "new chat message")

        assertEquals("", afterSend.composerText)
        assertEquals("thread-new", afterSend.composerDraftKey)
        assertTrue(afterSend.composerDrafts.isEmpty())
    }
}
