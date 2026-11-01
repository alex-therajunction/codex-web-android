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

    data class Diff(
        override val turnId: String,
        val diff: String,
    ) : ConversationRow {
        override val key: String = "$turnId:diff"
    }
}

internal fun buildConversationRows(turns: List<TurnInfo>): List<ConversationRow> = buildList {
    turns.forEach { turn ->
        val hasTurnDiff = turn.diff.isNotBlank()
        turn.items.forEach { item ->
            val coveredByTurnDiff = hasTurnDiff &&
                item.kind == "fileChange" &&
                item.status != "failed" &&
                item.status != "declined"
            if (coveredByTurnDiff) return@forEach
            add(ConversationRow.Item(turn.id, item))
        }
        if (hasTurnDiff) {
            add(ConversationRow.Diff(turn.id, turn.diff))
        }
        turn.error?.takeIf { it.isNotBlank() }?.let { message ->
            add(ConversationRow.Error(turn.id, message))
        }
    }
}
