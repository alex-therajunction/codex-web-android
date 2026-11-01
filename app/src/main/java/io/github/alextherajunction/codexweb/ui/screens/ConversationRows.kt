package io.github.alextherajunction.codexweb.ui.screens

import io.github.alextherajunction.codexweb.protocol.ChatItem
import io.github.alextherajunction.codexweb.protocol.TurnInfo

internal sealed interface ConversationRow {
    val turnId: String
    val key: String

    data class Item(
        override val turnId: String,
        val item: ChatItem,
    ) : ConversationRow {
        override val key: String = "$turnId:item:${item.id}"
    }

    data class Error(
        override val turnId: String,
        val message: String,
    ) : ConversationRow {
        override val key: String = "$turnId:error"
    }
}

internal fun buildConversationRows(turns: List<TurnInfo>): List<ConversationRow> = buildList {
    turns.forEach { turn ->
        turn.items.forEach { item ->
            add(ConversationRow.Item(turn.id, item))
        }
        turn.error?.takeIf { it.isNotBlank() }?.let { message ->
            add(ConversationRow.Error(turn.id, message))
        }
    }
}
