package io.github.alextherajunction.codexweb.protocol

data class UserInputOption(
    val label: String,
    val description: String,
)

data class UserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val isOther: Boolean,
    val isSecret: Boolean,
    val options: List<UserInputOption>,
)

internal fun parseUserInputQuestions(request: PendingRequest): List<UserInputQuestion> =
    request.params.array("questions")?.mapNotNull { raw ->
        val question = raw.asObjectOrNull() ?: return@mapNotNull null
        UserInputQuestion(
            id = question.string("id").orEmpty(),
            header = question.string("header").orEmpty(),
            question = question.string("question").orEmpty(),
            isOther = question.boolean("isOther") == true,
            isSecret = question.boolean("isSecret") == true,
            options = question.array("options")?.mapNotNull { rawOption ->
                val option = rawOption.asObjectOrNull() ?: return@mapNotNull null
                val label = option.string("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                UserInputOption(
                    label = label,
                    description = option.string("description").orEmpty(),
                )
            }.orEmpty(),
        )
    }.orEmpty().filter { it.id.isNotBlank() }

internal fun buildUserInputAnswers(
    questions: List<UserInputQuestion>,
    selectedOptions: Map<String, String>,
    customAnswers: Map<String, String>,
): Map<String, List<String>> = questions.mapNotNull { question ->
    val answer = customAnswers[question.id]?.trim().orEmpty()
        .ifBlank { selectedOptions[question.id].orEmpty() }
    if (answer.isBlank()) null else question.id to listOf(answer)
}.toMap()
