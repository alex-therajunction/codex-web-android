package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class UserInputTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses plan question options and descriptions`() {
        val request = pendingRequest(
            """
            {
              "questions": [
                {
                  "id": "approach",
                  "header": "Implementation",
                  "question": "Which approach should I take?",
                  "isOther": true,
                  "isSecret": false,
                  "options": [
                    {"label": "Incremental", "description": "Ship small changes"},
                    {"label": "Rewrite", "description": "Replace the subsystem"}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val question = parseUserInputQuestions(request).single()
        assertEquals("approach", question.id)
        assertEquals(listOf("Incremental", "Rewrite"), question.options.map { it.label })
        assertEquals("Ship small changes", question.options.first().description)
        assertEquals(true, question.isOther)
    }

    @Test
    fun `builds answers from selected options and custom input`() {
        val questions = listOf(
            UserInputQuestion(
                id = "approach",
                header = "",
                question = "",
                isOther = false,
                isSecret = false,
                options = listOf(UserInputOption("Incremental", "")),
            ),
            UserInputQuestion(
                id = "notes",
                header = "",
                question = "",
                isOther = true,
                isSecret = false,
                options = emptyList(),
            ),
        )

        assertEquals(
            mapOf(
                "approach" to listOf("Incremental"),
                "notes" to listOf("Keep it small"),
            ),
            buildUserInputAnswers(
                questions = questions,
                selectedOptions = mapOf("approach" to "Incremental"),
                customAnswers = mapOf("notes" to "  Keep it small  "),
            ),
        )
    }

    private fun pendingRequest(params: String): PendingRequest = parsePendingRequest(
        JsonPrimitive(1),
        "item/tool/requestUserInput",
        json.parseToJsonElement(params).jsonObject,
    )
}
