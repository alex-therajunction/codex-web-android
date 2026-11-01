package io.github.alextherajunction.codexweb.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.alextherajunction.codexweb.protocol.Diagnostic
import io.github.alextherajunction.codexweb.protocol.PendingRequest
import io.github.alextherajunction.codexweb.protocol.array
import io.github.alextherajunction.codexweb.protocol.asObjectOrNull
import io.github.alextherajunction.codexweb.protocol.string
import io.github.alextherajunction.codexweb.ui.theme.CodexAmber
import io.github.alextherajunction.codexweb.ui.theme.CodexLime
import io.github.alextherajunction.codexweb.ui.theme.CodexMuted
import io.github.alextherajunction.codexweb.ui.theme.CodexOutline
import io.github.alextherajunction.codexweb.ui.theme.CodexSurface

@Composable
fun WorkingDirectoryDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Folder, null) },
        title = { Text("Server working directory") },
        text = {
            Column {
                Text(
                    "Codex runs on the server that hosts your Codex Web deployment. This path is remote from this Android device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CodexMuted,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Absolute path") },
                    placeholder = { Text("/home/user/project") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(value.trim())
                    onDismiss()
                },
                enabled = value.isNotBlank(),
            ) { Text("Use path") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DiagnosticsDialog(
    diagnostics: List<Diagnostic>,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics") },
        text = {
            if (diagnostics.isEmpty()) {
                Text(
                    "No diagnostics. Unknown app-server notifications will be preserved here.",
                    color = CodexMuted,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(diagnostics.asReversed()) { diagnostic ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(
                                        AnnotatedString("${diagnostic.method}\n${diagnostic.summary}"),
                                    )
                                    Toast.makeText(context, "Diagnostic copied", Toast.LENGTH_SHORT).show()
                                },
                            color = CodexSurface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CodexOutline),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        diagnostic.method,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CodexAmber,
                                    )
                                    Icon(
                                        Icons.Rounded.ContentCopy,
                                        contentDescription = "Copy diagnostic",
                                        tint = CodexMuted,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    diagnostic.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private data class InputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val isOther: Boolean,
    val isSecret: Boolean,
    val options: List<String>,
)

@Composable
fun UserInputDialog(
    request: PendingRequest,
    onDismiss: () -> Unit,
    onSubmit: (Map<String, List<String>>) -> Unit,
) {
    val questions = remember(request.id) { parseQuestions(request) }
    val selected = remember(request.id) { mutableMapOf<String, String>() }
    val other = remember(request.id) { mutableMapOf<String, String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Codex needs input") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (questions.isEmpty()) {
                    Text(
                        "This request did not include supported question fields. Skip it to return no answers.",
                        color = CodexMuted,
                    )
                }
                questions.forEach { question ->
                    Column {
                        Text(
                            question.header.ifBlank { "Question" },
                            style = MaterialTheme.typography.labelLarge,
                            color = CodexLime,
                        )
                        Text(
                            question.question,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (question.options.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                question.options.forEach { option ->
                                    FilterChip(
                                        selected = selected[question.id] == option,
                                        onClick = {
                                            selected[question.id] = option
                                            other.remove(question.id)
                                        },
                                        label = { Text(option) },
                                        leadingIcon = if (selected[question.id] == option) {
                                            { Icon(Icons.Rounded.Check, null, Modifier.height(16.dp)) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                        if (question.isOther || question.options.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = other[question.id].orEmpty(),
                                onValueChange = {
                                    other[question.id] = it
                                    if (it.isNotBlank()) selected.remove(question.id)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(if (question.isOther) "Other" else "Answer") },
                                visualTransformation = if (question.isSecret) {
                                    PasswordVisualTransformation()
                                } else {
                                    androidx.compose.ui.text.input.VisualTransformation.None
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (question.isSecret) KeyboardType.Password else KeyboardType.Text,
                                ),
                                singleLine = false,
                                maxLines = 5,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val answers = questions.mapNotNull { question ->
                    val answer = other[question.id]?.trim().orEmpty()
                        .ifBlank { selected[question.id].orEmpty() }
                    if (answer.isBlank()) null else question.id to listOf(answer)
                }.toMap()
                onSubmit(answers)
            }) { Text("Submit") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSubmit(emptyMap()) }) { Text("Skip") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun parseQuestions(request: PendingRequest): List<InputQuestion> {
    return request.params.array("questions")?.mapNotNull { raw ->
        val obj = raw.asObjectOrNull() ?: return@mapNotNull null
        val options = obj.array("options")?.mapNotNull { option ->
            option.asObjectOrNull()?.string("label")
        }.orEmpty()
        InputQuestion(
            id = obj.string("id").orEmpty(),
            header = obj.string("header").orEmpty(),
            question = obj.string("question").orEmpty(),
            isOther = obj["isOther"]?.toString() == "true",
            isSecret = obj["isSecret"]?.toString() == "true",
            options = options,
        )
    }.orEmpty().filter { it.id.isNotBlank() }
}
