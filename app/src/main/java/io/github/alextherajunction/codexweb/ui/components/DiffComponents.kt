package io.github.alextherajunction.codexweb.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alextherajunction.codexweb.protocol.DiffLineKind
import io.github.alextherajunction.codexweb.protocol.classifyDiffLines
import io.github.alextherajunction.codexweb.protocol.summarizeDiff
import io.github.alextherajunction.codexweb.ui.theme.CodexDanger
import io.github.alextherajunction.codexweb.ui.theme.CodexLime
import io.github.alextherajunction.codexweb.ui.theme.CodexMuted
import io.github.alextherajunction.codexweb.ui.theme.CodexOutline
import io.github.alextherajunction.codexweb.ui.theme.CodexSurface

private val DiffAddedText = Color(0xFFB9E7A0)
private val DiffRemovedText = Color(0xFFFFB4AB)
private val DiffHunkText = Color(0xFF9CC7FF)
private val DiffContextText = Color(0xFFDCE5D2)
private val DiffGutter = Color(0xFF131611)

@Composable
fun TurnDiffCard(diff: String) {
    if (diff.isBlank()) return
    val stats = remember(diff) { summarizeDiff(diff) }
    var expanded by remember { mutableStateOf(diff.lineSequence().take(301).count() <= 300) }
    val title = when (stats.fileCount) {
        0 -> "File changes"
        1 -> "1 file changed"
        else -> "${stats.fileCount} files changed"
    }

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
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = CodexLime,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row {
                        if (stats.additions > 0) {
                            Text(
                                text = "+${stats.additions}",
                                style = MaterialTheme.typography.labelMedium,
                                color = CodexLime,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (stats.deletions > 0) {
                            Text(
                                text = "-${stats.deletions}",
                                style = MaterialTheme.typography.labelMedium,
                                color = CodexDanger,
                            )
                        }
                        if (stats.additions == 0 && stats.deletions == 0) {
                            Text(
                                text = "Unified diff",
                                style = MaterialTheme.typography.labelMedium,
                                color = CodexMuted,
                            )
                        }
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Collapse diff" else "Expand diff",
                    )
                }
            }
            AnimatedVisibility(expanded) {
                DiffBlock(diff)
            }
        }
    }
}

@Composable
fun DiffBlock(diff: String, maxHeight: Dp = 480.dp) {
    val lines = remember(diff) { classifyDiffLines(diff) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0C0E0B),
        shape = RoundedCornerShape(10.dp),
    ) {
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 5.dp),
                ) {
                    lines.forEach { line ->
                        Text(
                            text = line.text.ifEmpty { " " },
                            modifier = Modifier
                                .background(lineBackground(line.kind))
                                .padding(horizontal = 10.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                            color = lineTextColor(line.kind),
                        )
                    }
                }
            }
        }
    }
}

private fun lineBackground(kind: DiffLineKind): Color = when (kind) {
    DiffLineKind.ADDED -> Color(0xFF17331A)
    DiffLineKind.REMOVED -> Color(0xFF3A1719)
    DiffLineKind.HUNK -> Color(0xFF14253B)
    DiffLineKind.META -> DiffGutter
    DiffLineKind.CONTEXT -> Color.Transparent
}

private fun lineTextColor(kind: DiffLineKind): Color = when (kind) {
    DiffLineKind.ADDED -> DiffAddedText
    DiffLineKind.REMOVED -> DiffRemovedText
    DiffLineKind.HUNK -> DiffHunkText
    DiffLineKind.META -> CodexMuted
    DiffLineKind.CONTEXT -> DiffContextText
}
