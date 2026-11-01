package io.github.alextherajunction.codexweb

enum class ComposerPanel {
    MODEL,
    REASONING,
    MCP,
    REVIEW,
    STATUS,
    MEMORY,
    HELP,
    COST,
}

data class SlashCommandSpec(
    val command: String,
    val label: String,
    val description: String,
)

val BUILT_IN_SLASH_COMMANDS = listOf(
    SlashCommandSpec("/mcp", "MCP", "Show MCP server status"),
    SlashCommandSpec("/review", "Review", "Review unstaged changes or compare with a branch"),
    SlashCommandSpec("/compact", "Compact", "Compact context for this task"),
    SlashCommandSpec("/reasoning", "Reasoning", "Set model reasoning effort"),
    SlashCommandSpec("/model", "Model", "Select model for the current task"),
    SlashCommandSpec("/status", "Status", "Show task ID, context usage, and rate limits"),
    SlashCommandSpec("/goal", "Goal", "Set a goal to continuously pursue"),
    SlashCommandSpec("/plan", "Plan Mode", "Enable plan mode"),
    SlashCommandSpec("/memories", "Memories", "Configure task memories"),
)

sealed interface ComposerCommand {
    data object GoalPrompt : ComposerCommand
    data class GoalSet(val objective: String) : ComposerCommand
    data object GoalPause : ComposerCommand
    data object GoalResume : ComposerCommand
    data object GoalClear : ComposerCommand
    data object GoalEdit : ComposerCommand
    data object Plan : ComposerCommand
    data object Compact : ComposerCommand
    data object Mcp : ComposerCommand
    data object Review : ComposerCommand
    data object Reasoning : ComposerCommand
    data object Model : ComposerCommand
    data object Status : ComposerCommand
    data object Memories : ComposerCommand
    data object Help : ComposerCommand
    data object Clear : ComposerCommand
    data object Cost : ComposerCommand
}

internal fun parseComposerCommand(input: String): ComposerCommand? {
    val text = input.trim()
    if (!text.startsWith("/")) return null
    if (text == "/goal" || text.startsWith("/goal") && text.getOrNull(5)?.isWhitespace() == true) {
        return when (val action = text.removePrefix("/goal").trim()) {
            "" -> ComposerCommand.GoalPrompt
            "pause" -> ComposerCommand.GoalPause
            "resume" -> ComposerCommand.GoalResume
            "clear" -> ComposerCommand.GoalClear
            "edit" -> ComposerCommand.GoalEdit
            else -> ComposerCommand.GoalSet(action)
        }
    }
    return when (text) {
        "/plan" -> ComposerCommand.Plan
        "/compact" -> ComposerCommand.Compact
        "/mcp" -> ComposerCommand.Mcp
        "/review" -> ComposerCommand.Review
        "/reasoning" -> ComposerCommand.Reasoning
        "/model" -> ComposerCommand.Model
        "/status" -> ComposerCommand.Status
        "/memories" -> ComposerCommand.Memories
        "/help" -> ComposerCommand.Help
        "/clear" -> ComposerCommand.Clear
        "/cost" -> ComposerCommand.Cost
        else -> null
    }
}

fun slashCommandSuggestions(input: String): List<SlashCommandSpec> {
    val query = input.trim()
    if (!query.startsWith("/") || query.any(Char::isWhitespace)) return emptyList()
    return BUILT_IN_SLASH_COMMANDS.filter { it.command.startsWith(query) }
}

internal fun editedGoalStatus(status: String): String = when (status) {
    "budgetLimited", "complete" -> "active"
    else -> status.ifBlank { "active" }
}
