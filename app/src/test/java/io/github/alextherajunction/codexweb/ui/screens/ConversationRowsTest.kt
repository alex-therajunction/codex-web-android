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
}
