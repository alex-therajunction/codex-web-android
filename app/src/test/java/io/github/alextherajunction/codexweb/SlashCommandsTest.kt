package io.github.alextherajunction.codexweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandsTest {
    @Test
    fun `parses built-in composer commands`() {
        assertEquals(ComposerCommand.Mcp, parseComposerCommand("/mcp"))
        assertEquals(ComposerCommand.Review, parseComposerCommand("/review"))
        assertEquals(ComposerCommand.Compact, parseComposerCommand("/compact"))
        assertEquals(ComposerCommand.Reasoning, parseComposerCommand("/reasoning"))
        assertEquals(ComposerCommand.Model, parseComposerCommand("/model"))
        assertEquals(ComposerCommand.Status, parseComposerCommand("/status"))
        assertEquals(ComposerCommand.Plan, parseComposerCommand("/plan"))
        assertEquals(ComposerCommand.Memories, parseComposerCommand("/memories"))
        assertEquals(ComposerCommand.Help, parseComposerCommand("/help"))
        assertEquals(ComposerCommand.Clear, parseComposerCommand("/clear"))
        assertEquals(ComposerCommand.Cost, parseComposerCommand("/cost"))
    }

    @Test
    fun `parses goal lifecycle commands`() {
        assertEquals(ComposerCommand.GoalPrompt, parseComposerCommand("/goal"))
        assertEquals(ComposerCommand.GoalPause, parseComposerCommand("/goal pause"))
        assertEquals(ComposerCommand.GoalPause, parseComposerCommand("/goal\tpause"))
        assertEquals(ComposerCommand.GoalResume, parseComposerCommand(" /goal resume "))
        assertEquals(ComposerCommand.GoalClear, parseComposerCommand("/goal clear"))
        assertEquals(ComposerCommand.GoalEdit, parseComposerCommand("/goal edit"))
    }

    @Test
    fun `treats remaining goal text as the objective`() {
        assertEquals(
            ComposerCommand.GoalSet("Reduce p95 latency below 120 ms"),
            parseComposerCommand("/goal Reduce p95 latency below 120 ms"),
        )
        assertEquals(ComposerCommand.GoalSet("pause everything"), parseComposerCommand("/goal pause everything"))
    }

    @Test
    fun `ignores unknown commands and ordinary prompts`() {
        assertNull(parseComposerCommand("/goals"))
        assertNull(parseComposerCommand("/plan now"))
        assertNull(parseComposerCommand("goal"))
        assertNull(parseComposerCommand("hello"))
    }

    @Test
    fun `suggests built-ins while the command word is being typed`() {
        assertEquals(listOf("/plan"), slashCommandSuggestions("/pl").map { it.command })
        assertEquals(listOf("/mcp", "/model", "/memories"), slashCommandSuggestions("/m").map { it.command })
        assertEquals(9, slashCommandSuggestions("/").size)
        assertEquals(emptyList<SlashCommandSpec>(), slashCommandSuggestions("/goal reduce latency"))
        assertEquals(emptyList<SlashCommandSpec>(), slashCommandSuggestions("hello"))
    }

    @Test
    fun `editing a stopped goal resumes it but preserves lifecycle states`() {
        assertEquals("active", editedGoalStatus("budgetLimited"))
        assertEquals("active", editedGoalStatus("complete"))
        assertEquals("paused", editedGoalStatus("paused"))
        assertEquals("blocked", editedGoalStatus("blocked"))
        assertEquals("active", editedGoalStatus(""))
    }
}
