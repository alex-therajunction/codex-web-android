package io.github.alextherajunction.codexweb.push

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PushPayload(
    val version: Int = 1,
    val kind: String,
    val title: String,
    val body: String,
    val threadId: String? = null,
    val turnId: String? = null,
    val requestId: String? = null,
    val source: String = "",
    val createdAt: Long = 0,
)

object PushPayloadParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val supportedKinds = setOf(
        "turn_completed",
        "turn_failed",
        "goal_completed",
        "approval_required",
        "question_required",
    )

    fun parse(content: ByteArray): PushPayload? {
        val payload = runCatching {
            json.decodeFromString<PushPayload>(content.toString(Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (payload.version != 1 || payload.kind !in supportedKinds) return null
        if (payload.title.isBlank() || payload.body.isBlank()) return null
        return payload
    }
}
