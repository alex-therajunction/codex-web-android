package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal sealed interface PendingRequestReply {
    data class Result(val payload: JsonElement) : PendingRequestReply

    data class Failure(
        val message: String,
        val code: Int = -32601,
    ) : PendingRequestReply
}

internal fun PendingRequestReply.send(rpc: CodexRpcClient, requestId: JsonElement) {
    when (this) {
        is PendingRequestReply.Result -> rpc.respond(requestId, payload)
        is PendingRequestReply.Failure -> rpc.respondError(requestId, message, code)
    }
}

internal class PendingRequestReplyLedger(
    private val maxEntries: Int = 128,
) {
    private data class Entry(
        val fingerprint: String,
        val reply: PendingRequestReply,
    )

    private val lock = Any()
    private val repliesById = LinkedHashMap<JsonElement, Entry>()
    private val userInputRepliesByItem = LinkedHashMap<String, PendingRequestReply>()

    fun record(request: PendingRequest, reply: PendingRequestReply) = synchronized(lock) {
        repliesById.putBounded(request.id, Entry(request.fingerprint(), reply), maxEntries)
        request.userInputItemKey()?.let { key ->
            userInputRepliesByItem.putBounded(key, reply, maxEntries)
        }
    }

    fun lookup(request: PendingRequest): PendingRequestReply? = synchronized(lock) {
        val byId = repliesById[request.id]
        byId?.reply?.takeIf { byId.fingerprint == request.fingerprint() }
            ?: request.userInputItemKey()?.let(userInputRepliesByItem::get)
    }

    fun clear() = synchronized(lock) {
        repliesById.clear()
        userInputRepliesByItem.clear()
    }
}

private fun PendingRequest.fingerprint(): String = "$method\u0000$params"

internal fun PendingRequest.userInputItemKey(): String? {
    if (method != "item/tool/requestUserInput") return null
    val item = itemId?.takeIf { it.isNotBlank() } ?: return null
    val questionIds = params.array("questions")
        ?.mapNotNull { (it as? JsonObject)?.string("id") }
        .orEmpty()
    return buildString {
        append(threadId.orEmpty())
        append('\u0000')
        append(item)
        questionIds.forEach { questionId ->
            append('\u0000')
            append(questionId)
        }
    }
}

private fun <K, V> MutableMap<K, V>.putBounded(key: K, value: V, maxEntries: Int) {
    remove(key)
    put(key, value)
    while (size > maxEntries) {
        remove(keys.first())
    }
}
