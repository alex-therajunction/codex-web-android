package io.github.alextherajunction.codexweb.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.alextherajunction.codexweb.AppUiState
import io.github.alextherajunction.codexweb.ComposerPanel
import io.github.alextherajunction.codexweb.protocol.AppServerSnapshot
import io.github.alextherajunction.codexweb.protocol.McpServerSummary
import io.github.alextherajunction.codexweb.ui.theme.CodexLime
import io.github.alextherajunction.codexweb.ui.theme.CodexMuted
import io.github.alextherajunction.codexweb.ui.theme.CodexOutline
import io.github.alextherajunction.codexweb.ui.theme.CodexSurface
import io.github.alextherajunction.codexweb.ui.theme.CodexSurfaceHigh

@Composable
fun ComposerCommandDialog(
    panel: ComposerPanel,
    state: AppUiState,
    snapshot: AppServerSnapshot,
    onDismiss: () -> Unit,
    onModelSelected: (String) -> Unit,
    onReasoningEffortSelected: (String) -> Unit,
    onStartReviewUncommitted: () -> Unit,
    onStartReviewBaseBranch: (String) -> Unit,
    onSaveMemorySettings: (Boolean, Boolean) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            color = CodexSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, CodexOutline),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .heightIn(max = 620.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        composerPanelTitle(panel),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close command panel")
                    }
                }
                HorizontalDivider(color = CodexOutline, modifier = Modifier.padding(top = 8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (panel) {
                        ComposerPanel.MCP -> McpPanel(state)
                        ComposerPanel.REVIEW -> ReviewPanel(
                            hasThread = snapshot.selectedThreadId != null,
                            loading = state.composerPanelLoading,
                            onStartReviewUncommitted = onStartReviewUncommitted,
                            onStartReviewBaseBranch = onStartReviewBaseBranch,
                        )
                        ComposerPanel.REASONING -> ReasoningPanel(
                            snapshot = snapshot,
                            onReasoningEffortSelected = {
                                onReasoningEffortSelected(it)
                                onDismiss()
                            },
                        )
                        ComposerPanel.MODEL -> ModelPanel(
                            snapshot = snapshot,
                            onModelSelected = {
                                onModelSelected(it)
                                onDismiss()
                            },
                        )
                        ComposerPanel.STATUS -> StatusPanel(state, snapshot)
                        ComposerPanel.MEMORY -> MemoryPanel(
                            state = state,
                            onSaveMemorySettings = onSaveMemorySettings,
                        )
                        ComposerPanel.HELP -> HelpPanel()
                        ComposerPanel.COST -> CostPanel(snapshot)
                    }
                }
            }
        }
    }
}

@Composable
private fun McpPanel(state: AppUiState) {
    when {
        state.composerPanelLoading && state.mcpServers.isEmpty() -> LoadingRow("Loading MCP servers…")
        state.mcpServers.isEmpty() -> EmptyPanel("No MCP servers returned by app-server.")
        else -> state.mcpServers.forEach { server -> McpServerRow(server) }
    }
}

@Composable
private fun McpServerRow(server: McpServerSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CodexSurfaceHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                server.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${server.toolCount} tools",
                    style = MaterialTheme.typography.labelMedium,
                    color = CodexMuted,
                )
                Text(
                    if (server.authStatus == "notLoggedIn") "Not logged in" else "Enabled",
                    style = MaterialTheme.typography.labelMedium,
                    color = CodexMuted,
                )
            }
        }
    }
}

@Composable
private fun ReviewPanel(
    hasThread: Boolean,
    loading: Boolean,
    onStartReviewUncommitted: () -> Unit,
    onStartReviewBaseBranch: (String) -> Unit,
) {
    var branch by remember { mutableStateOf("main") }
    Button(
        onClick = onStartReviewUncommitted,
        modifier = Modifier.fillMaxWidth(),
        enabled = hasThread && !loading,
        colors = ButtonDefaults.buttonColors(
            containerColor = CodexLime,
            contentColor = CodexSurface,
        ),
    ) {
        Text("Review unstaged changes")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Base branch") },
            enabled = hasThread && !loading,
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { onStartReviewBaseBranch(branch) },
            enabled = hasThread && !loading && branch.isNotBlank(),
        ) {
            Text("Compare")
        }
    }
    if (loading) LoadingRow("Starting review…")
    if (!hasThread) EmptyPanel("Start a conversation before reviewing changes.")
}

@Composable
private fun ReasoningPanel(
    snapshot: AppServerSnapshot,
    onReasoningEffortSelected: (String) -> Unit,
) {
    val model = snapshot.models.firstOrNull { it.id == snapshot.selectedModelId }
    val efforts = model?.reasoningEfforts.orEmpty()
    if (efforts.isEmpty()) {
        EmptyPanel("This model does not expose reasoning effort options.")
    } else {
        efforts.forEach { effort ->
            CommandRow(
                title = effort.replaceFirstChar { it.uppercase() },
                selected = effort == snapshot.selectedReasoningEffort,
                onClick = { onReasoningEffortSelected(effort) },
            )
        }
    }
}

@Composable
private fun ModelPanel(
    snapshot: AppServerSnapshot,
    onModelSelected: (String) -> Unit,
) {
    if (snapshot.models.isEmpty()) {
        EmptyPanel("No models are available.")
    } else {
        snapshot.models.filterNot { it.hidden }.forEach { model ->
            CommandRow(
                title = model.displayName,
                subtitle = model.description,
                selected = model.id == snapshot.selectedModelId,
                onClick = { onModelSelected(model.id) },
            )
        }
    }
}

@Composable
private fun StatusPanel(state: AppUiState, snapshot: AppServerSnapshot) {
    val usage = snapshot.selectedUsage
    val context = if (usage?.contextWindow != null) {
        "${formatCompact(usage.totalTokens)} / ${formatCompact(usage.contextWindow)} tokens"
    } else {
        "Unavailable"
    }
    val rate = when {
        state.rateLimits?.primaryUsedPercent != null -> "${state.rateLimits.primaryUsedPercent}% used"
        state.composerPanelLoading -> "Loading…"
        else -> "Unavailable"
    }
    StatusRow("Task", snapshot.selectedThreadId ?: "Not created yet", monospace = true)
    StatusRow("Context", context)
    StatusRow("Rate limits", rate)
}

@Composable
private fun MemoryPanel(
    state: AppUiState,
    onSaveMemorySettings: (Boolean, Boolean) -> Unit,
) {
    val settings = state.memorySettings
    var useMemories by remember(settings?.useMemories, settings?.generateMemories) {
        mutableStateOf(settings?.useMemories ?: true)
    }
    var generateMemories by remember(settings?.useMemories, settings?.generateMemories) {
        mutableStateOf(settings?.generateMemories ?: true)
    }
    if (state.composerPanelLoading && settings == null) {
        LoadingRow("Loading memory settings…")
    }
    MemoryToggle(
        title = "Use memories",
        description = "Apply memories to subsequent new tasks",
        checked = useMemories,
        enabled = !state.composerPanelLoading,
        onCheckedChange = { useMemories = it },
    )
    MemoryToggle(
        title = "Generate memories",
        description = "Allow the current task to create new memories",
        checked = generateMemories,
        enabled = !state.composerPanelLoading,
        onCheckedChange = { generateMemories = it },
    )
    Button(
        onClick = { onSaveMemorySettings(useMemories, generateMemories) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.composerPanelLoading,
        colors = ButtonDefaults.buttonColors(
            containerColor = CodexLime,
            contentColor = CodexSurface,
        ),
    ) {
        Text(if (state.composerPanelLoading) "Saving…" else "Done")
    }
}

@Composable
private fun MemoryToggle(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        color = CodexSurfaceHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(description, style = MaterialTheme.typography.labelMedium, color = CodexMuted)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun HelpPanel() {
    Text(
        "Composer commands",
        style = MaterialTheme.typography.labelLarge,
        color = CodexLime,
    )
    listOf(
        "/mcp" to "Show MCP server status",
        "/review" to "Review unstaged changes or compare with a branch",
        "/compact" to "Compact context for the current task",
        "/reasoning" to "Set model reasoning effort",
        "/model" to "Select the model for this task",
        "/status" to "Show task ID, context usage, and rate limits",
        "/goal" to "Enter goal objective mode",
        "/goal <objective>" to "Create or replace the goal",
        "/goal pause|resume|edit|clear" to "Manage the current goal",
        "/plan" to "Enable plan mode for subsequent prompts",
        "/memories" to "Configure task memories",
        "/cost" to "Show token usage statistics",
        "/clear" to "Clear the locally displayed conversation",
        "/help" to "Show this command reference",
    ).forEach { (command, description) ->
        Row(verticalAlignment = Alignment.Top) {
            Text(
                command,
                modifier = Modifier.width(178.dp),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = CodexMuted,
            )
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CostPanel(snapshot: AppServerSnapshot) {
    val usage = snapshot.selectedUsage
    if (usage == null) {
        EmptyPanel("Token usage is available after sending or opening a message.")
        return
    }
    StatusRow("Total tokens", formatCompact(usage.totalTokens))
    StatusRow("Input tokens", formatCompact(usage.inputTokens))
    StatusRow("Output tokens", formatCompact(usage.outputTokens))
    StatusRow(
        "Context window",
        usage.contextWindow?.let(::formatCompact) ?: "Unavailable",
    )
}

@Composable
private fun StatusRow(label: String, value: String, monospace: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(118.dp),
            style = MaterialTheme.typography.labelMedium,
            color = CodexMuted,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun CommandRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String = "",
    selected: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected) CodexLime.copy(alpha = 0.12f) else CodexSurfaceHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = CodexMuted,
                    )
                }
            }
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = CodexLime)
            }
        }
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = CodexLime,
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = CodexMuted)
    }
}

@Composable
private fun EmptyPanel(message: String) {
    Text(
        message,
        modifier = Modifier.padding(vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = CodexMuted,
    )
}

private fun composerPanelTitle(panel: ComposerPanel): String = when (panel) {
    ComposerPanel.MCP -> "MCP"
    ComposerPanel.REVIEW -> "Code review"
    ComposerPanel.REASONING -> "Reasoning"
    ComposerPanel.MODEL -> "Model"
    ComposerPanel.STATUS -> "Status"
    ComposerPanel.MEMORY -> "Task memories"
    ComposerPanel.HELP -> "Help"
    ComposerPanel.COST -> "Token usage"
}
