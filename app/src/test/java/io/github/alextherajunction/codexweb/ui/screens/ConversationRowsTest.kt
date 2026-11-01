package io.github.alextherajunction.codexweb.ui.screens

import io.github.alextherajunction.codexweb.protocol.ChatItem
import io.github.alextherajunction.codexweb.protocol.TurnInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationRowsTest {
    @Test
    fun `renders each turn error immediately after that turn`() {
        val rows = buildConversationRows(
            listOf(
                TurnInfo(
                    id = "turn-1",
                    status = "failed",
                    error = "first failed",
                    items = listOf(ChatItem(id = "item-1", kind = "agentMessage", text = "one")),
                ),
                TurnInfo(
                    id = "turn-2",
                    status = "failed",
                    error = "second failed",
                    items = listOf(ChatItem(id = "item-2", kind = "agentMessage", text = "two")),
                ),
            ),
        )

        assertEquals(
            listOf(
                "turn-1:item:item-1",
                "turn-1:error",
                "turn-2:item:item-2",
                "turn-2:error",
            ),
            rows.map { it.key },
        )
        assertEquals(
            listOf("first failed", "second failed"),
            rows.filterIsInstance<ConversationRow.Error>().map { it.message },
        )
    }

    @Test
    fun `does not add an error row for blank or missing errors`() {
        val rows = buildConversationRows(
            listOf(
                TurnInfo(id = "turn-1", status = "completed"),
                TurnInfo(id = "turn-2", status = "failed", error = "   "),
                TurnInfo(
                    id = "turn-3",
                    status = "failed",
                    error = "useful error",
                    items = listOf(ChatItem(id = "item-3", kind = "agentMessage", text = "three")),
                ),
            ),
        )

        assertEquals(
            listOf("turn-3:item:item-3", "turn-3:error"),
            rows.map { it.key },
        )
    }

    @Test
    fun `uses the aggregate diff instead of successful file change items`() {
        val rows = buildConversationRows(
            listOf(
                TurnInfo(
                    id = "turn-1",
                    status = "completed",
                    diff = "diff --git a/src/app.ts b/src/app.ts\n+new\n",
                    items = listOf(
                        ChatItem(id = "message-1", kind = "agentMessage", text = "done"),
                        ChatItem(id = "change-1", kind = "fileChange", detail = "old detail", status = "completed"),
                        ChatItem(id = "change-2", kind = "fileChange", status = "failed"),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("turn-1:item:message-1", "turn-1:item:change-2", "turn-1:diff"),
            rows.map { it.key },
        )
        assertEquals(
            "diff --git a/src/app.ts b/src/app.ts\n+new\n",
            rows.filterIsInstance<ConversationRow.Diff>().single().diff,
        )
    }
}
