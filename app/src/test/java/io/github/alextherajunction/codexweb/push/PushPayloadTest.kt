package io.github.alextherajunction.codexweb.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushPayloadTest {
    @Test
    fun parsesSupportedCompletionPayload() {
        val payload = PushPayloadParser.parse(
            """
            {
              "version": 1,
              "kind": "turn_completed",
              "title": "Codex turn completed",
              "body": "Implemented notifications.",
              "threadId": "thread-1",
              "turnId": "turn-1",
              "source": "app-server.turn/completed",
              "createdAt": 1700000000000
            }
            """.trimIndent().toByteArray(),
        )

        assertEquals("turn_completed", payload?.kind)
        assertEquals("Implemented notifications.", payload?.body)
        assertEquals("thread-1", payload?.threadId)
    }

    @Test
    fun acceptsInteractionPrompts() {
        assertEquals(
            "approval_required",
            PushPayloadParser.parse(
                """{"kind":"approval_required","title":"Permission","body":"Run command"}""".toByteArray(),
            )?.kind,
        )
        assertEquals(
            "question_required",
            PushPayloadParser.parse(
                """{"kind":"question_required","title":"Question","body":"Choose one"}""".toByteArray(),
            )?.kind,
        )
    }

    @Test
    fun rejectsUnknownKindsAndMalformedMessages() {
        assertNull(PushPayloadParser.parse("""{"kind":"message_delta","title":"x","body":"y"}""".toByteArray()))
        assertNull(PushPayloadParser.parse("not json".toByteArray()))
        assertNull(PushPayloadParser.parse("""{"kind":"goal_completed","title":"","body":"x"}""".toByteArray()))
    }
}
