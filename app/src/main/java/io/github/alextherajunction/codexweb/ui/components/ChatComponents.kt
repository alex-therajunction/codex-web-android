package io.github.alextherajunction.codexweb.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alextherajunction.codexweb.protocol.ApprovalDecision
import io.github.alextherajunction.codexweb.protocol.ChatItem
import io.github.alextherajunction.codexweb.protocol.GoalInfo
import io.github.alextherajunction.codexweb.protocol.PendingRequest
import io.github.alextherajunction.codexweb.protocol.PendingRequestKind
import io.github.alextherajunction.codexweb.protocol.PlanInfo
import io.github.alextherajunction.codexweb.protocol.UsageInfo
import io.github.alextherajunction.codexweb.ui.theme.CodexAmber
import io.github.alextherajunction.codexweb.ui.theme.CodexDanger
import io.github.alextherajunction.codexweb.ui.theme.CodexLime
import io.github.alextherajunction.codexweb.ui.theme.CodexLimeSoft
import io.github.alextherajunction.codexweb.ui.theme.CodexMuted
import io.github.alextherajunction.codexweb.ui.theme.CodexOutline
import io.github.alextherajunction.codexweb.ui.theme.CodexSurface

@Composable
fun ChatItemRow(item: ChatItem) {
    when (item.kind) {
        "userMessage" -> UserMessage(item.text)
        "agentMessage" -> AgentMessage(item)
        "reasoning" -> ExpandableToolCard(
            icon = Icons.Rounded.Tune,
            title = "Reasoning",
            subtitle = if (item.isStreaming) "Thinking" else "Reasoning summary",
            status = item.status,
            defaultExpanded = false,
        ) { RichText(item.text) }
        "commandExecution" -> ExpandableToolCard(
            icon = Icons.Rounded.Terminal,
            title = item.title.ifBlank { "Command" },
            subtitle = item.status.ifBlank { "Command execution" },
            status = item.status,
            monospaceTitle = true,
            defaultExpanded = item.status == "failed",
        ) {
            if (item.detail.isBlank()) {
                Text("No output", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CodeText(item.detail)
            }
        }
        "fileChange" -> ExpandableToolCard(
            icon = Icons.Rounded.CheckCircle,
            title = item.title.ifBlank { "File changes" },
            subtitle = item.status,
            status = item.status,
            defaultExpanded = item.status == "failed" || item.status == "declined",
        ) {
            CodeText(item.detail)
        }
        "plan" -> ExpandableToolCard(
            icon = Icons.Rounded.CheckCircle,
            title = "Plan",
            subtitle = "Proposed by Codex",
            status = item.status,
            defaultExpanded = true,
        ) { RichText(item.text) }
        "unknown" -> ExpandableToolCard(
            icon = Icons.Rounded.ErrorOutline,
            title = "Unrecognized item · ${item.title}",
            subtitle = "Preserved from app-server",
            status = "",
            defaultExpanded = false,
        ) { CodeText(item.detail) }
        else -> ExpandableToolCard(
            icon = Icons.Rounded.Tune,
            title = item.title.ifBlank { item.kind },
            subtitle = item.text.take(120),
            status = item.status,
            defaultExpanded = false,
        ) {
            if (item.text.isNotBlank()) RichText(item.text)
            if (item.detail.isNotBlank()) CodeText(item.detail)
        }
    }
}

@Composable
fun UserMessage(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            color = CodexLimeSoft,
            border = BorderStroke(1.dp, CodexLime.copy(alpha = 0.22f)),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun AgentMessage(item: ChatItem) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(CodexLime, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "›_",
                color = Color(0xFF12150F),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            RichText(item.text)
            if (item.isStreaming) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.width(38.dp).height(2.dp),
                        color = CodexLime,
                        trackColor = CodexSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Streaming",
                        style = MaterialTheme.typography.labelMedium,
                        color = CodexMuted,
                    )
                }
            }
        }
    }
}

@Composable
fun RichText(text: String) {
    if (text.isBlank()) return
    val parts = text.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEachIndexed { index, part ->
            if (part.isBlank()) return@forEachIndexed
            if (index % 2 == 1) {
                val lines = part.lines()
                val language = lines.firstOrNull()?.trim().orEmpty()
                val code = if (language.matches(Regex("[A-Za-z0-9_+.#-]{0,40}"))) {
                    lines.drop(1).joinToString("\n")
                } else {
                    part
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF0C0E0B),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CodexOutline.copy(alpha = 0.8f)),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        if (language.isNotBlank() && language != code.lines().firstOrNull()) {
                            Text(
                                language.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = CodexMuted,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            text = code.trimEnd(),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                            color = Color(0xFFDCE5D2),
                        )
                    }
                }
            } else {
                SelectionContainer {
                    Text(
                        text = part.trim(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
fun CodeText(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0C0E0B),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = text.ifBlank { " " },
            modifier = Modifier
                .padding(10.dp)
                .horizontalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            color = Color(0xFFDCE5D2),
        )
    }
}

@Composable
fun ExpandableToolCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    status: String,
    defaultExpanded: Boolean,
    monospaceTitle: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember(title, status) { mutableStateOf(defaultExpanded) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CodexSurface.copy(alpha = 0.86f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, CodexOutline.copy(alpha = 0.7f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = statusColor(status),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = if (monospaceTitle) FontFamily.Monospace else null,
                        maxLines = if (expanded) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = status.ifBlank { subtitle },
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor(status).takeIf { status.isNotBlank() } ?: CodexMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 40.dp, end = 12.dp, bottom = 12.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun PlanSummaryCard(plan: PlanInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CodexSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CodexOutline),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!plan.explanation.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    plan.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CodexMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
            plan.steps.forEach { step ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = when (step.status) {
                            "completed" -> Icons.Rounded.CheckCircle
                            "inProgress" -> Icons.Rounded.RadioButtonUnchecked
                            else -> Icons.Rounded.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        modifier = Modifier.size(17.dp).padding(top = 2.dp),
                        tint = when (step.status) {
                            "completed" -> CodexLime
                            "inProgress" -> CodexAmber
                            else -> CodexMuted
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        step.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (step.status == "pending") CodexMuted else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
fun GoalSummaryCard(goal: GoalInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CodexSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CodexOutline),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Goal", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(goal.status) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledLabelColor = CodexLime,
                        disabledContainerColor = CodexLime.copy(alpha = 0.08f),
                    ),
                    border = AssistChipDefaults.assistChipBorder(
                        enabled = false,
                        borderColor = CodexLime.copy(alpha = 0.25f),
                    ),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(goal.objective, style = MaterialTheme.typography.bodyMedium)
            if (goal.tokenBudget != null || goal.tokensUsed > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (goal.tokenBudget != null) {
                        "${formatCompact(goal.tokensUsed)} / ${formatCompact(goal.tokenBudget)} tokens"
                    } else {
                        "${formatCompact(goal.tokensUsed)} tokens used"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = CodexMuted,
                )
            }
        }
    }
}

@Composable
fun ContextChips(cwd: String, usage: UsageInfo?, model: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = {},
            label = {
                Text(
                    cwd,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (cwd.length > 28) Modifier.width(220.dp) else Modifier,
                )
            },
            leadingIcon = { Icon(Icons.Rounded.Terminal, null, modifier = Modifier.size(16.dp)) },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = CodexMuted,
                leadingIconContentColor = CodexMuted,
                containerColor = CodexSurface.copy(alpha = 0.6f),
            ),
            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = CodexOutline),
        )
        if (usage != null) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        if (usage.contextWindow != null) {
                            "${formatCompact(usage.totalTokens)} / ${formatCompact(usage.contextWindow)}"
                        } else {
                            "${formatCompact(usage.totalTokens)} tokens"
                        },
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = CodexMuted,
                    containerColor = CodexSurface.copy(alpha = 0.6f),
                ),
                border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = CodexOutline),
            )
        }
        if (!model.isNullOrBlank()) {
            AssistChip(
                onClick = {},
                label = { Text(model, maxLines = 1) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = CodexMuted,
                    containerColor = CodexSurface.copy(alpha = 0.6f),
                ),
                border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = CodexOutline),
            )
        }
    }
}

@Composable
fun PendingRequestCard(
    request: PendingRequest,
    index: Int,
    total: Int,
    onApprove: (ApprovalDecision) -> Unit,
    onAnswer: () -> Unit,
    onCancelMcp: () -> Unit,
    onReject: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CodexSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CodexAmber.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = CodexAmber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    request.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (total > 1) {
                    Text("${index + 1} of $total", style = MaterialTheme.typography.labelMedium, color = CodexMuted)
                }
            }
            if (request.body.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                CodeText(request.body)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = CodexOutline)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (request.kind) {
                    PendingRequestKind.COMMAND_APPROVAL,
                    PendingRequestKind.FILE_APPROVAL,
                    PendingRequestKind.PERMISSIONS_APPROVAL -> {
                        OutlinedButton(onClick = { onApprove(ApprovalDecision.DENY) }) {
                            Text("Deny")
                        }
                        Button(
                            onClick = { onApprove(ApprovalDecision.ALLOW) },
                            colors = ButtonDefaults.buttonColors(containerColor = CodexLime, contentColor = Color(0xFF12150F)),
                        ) {
                            Text("Allow once")
                        }
                        TextButton(onClick = { onApprove(ApprovalDecision.ALLOW_SESSION) }) {
                            Text("For session", color = CodexLime)
                        }
                    }
                    PendingRequestKind.USER_INPUT -> {
                        Button(
                            onClick = onAnswer,
                            colors = ButtonDefaults.buttonColors(containerColor = CodexLime, contentColor = Color(0xFF12150F)),
                        ) { Text("Answer") }
                    }
                    PendingRequestKind.MCP_ELICITATION -> {
                        OutlinedButton(onClick = onCancelMcp) { Text("Cancel request") }
                    }
                    PendingRequestKind.UNKNOWN -> {
                        OutlinedButton(onClick = onReject) { Text("Reject unsupported") }
                    }
                }
            }
        }
    }
}

@Composable
fun TurnErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CodexDanger.copy(alpha = 0.08f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, CodexDanger.copy(alpha = 0.38f)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = CodexDanger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = CodexDanger)
        }
    }
}

@Composable
fun WorkingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = CodexLime,
        )
        Spacer(Modifier.width(9.dp))
        Text("Codex is working", style = MaterialTheme.typography.bodyMedium, color = CodexMuted)
    }
}

fun statusColor(status: String): Color = when (status) {
    "inProgress" -> CodexAmber
    "completed" -> CodexLime
    "failed" -> CodexDanger
    "declined" -> CodexAmber
    else -> CodexMuted
}

fun formatCompact(value: Long): String = when {
    value >= 1_000_000 -> String.format("%.1fm", value / 1_000_000.0)
    value >= 1_000 -> String.format("%.1fk", value / 1_000.0)
    else -> value.toString()
}
