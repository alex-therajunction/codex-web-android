package io.github.alextherajunction.codexweb.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alextherajunction.codexweb.protocol.ProjectGroup
import io.github.alextherajunction.codexweb.protocol.ServerDirectory
import io.github.alextherajunction.codexweb.protocol.normalizeProjectPath
import io.github.alextherajunction.codexweb.protocol.serverParentPath
import io.github.alextherajunction.codexweb.ui.theme.CodexDanger
import io.github.alextherajunction.codexweb.ui.theme.CodexLime
import io.github.alextherajunction.codexweb.ui.theme.CodexMuted
import io.github.alextherajunction.codexweb.ui.theme.CodexOutline
import io.github.alextherajunction.codexweb.ui.theme.CodexSurface

@Composable
fun ProjectPickerDialog(
    currentProject: String,
    projects: List<ProjectGroup>,
    browserPath: String,
    browserDirectories: List<ServerDirectory>,
    browserLoading: Boolean,
    browserError: String?,
    onBrowse: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var browsing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var pathInput by remember(browserPath) { mutableStateOf(browserPath) }
    val normalizedCurrent = normalizeProjectPath(currentProject)
    val filteredProjects = remember(projects, query) {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) projects else projects.filter {
            "${it.displayName} ${it.workingDirectory}".lowercase().contains(needle)
        }
    }

    LaunchedEffect(browserPath) {
        pathInput = browserPath
    }

    fun openBrowser() {
        browsing = true
        onBrowse(normalizedCurrent.ifBlank { "/" })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (browsing) Icons.Rounded.FolderOpen else Icons.Rounded.Folder, null) },
        title = { Text(if (browsing) "Choose server folder" else "Select project") },
        text = {
            if (browsing) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Browse folders on the Codex Web host. This path is remote from this Android device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CodexMuted,
                    )
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Server path") },
                        trailingIcon = {
                            TextButton(onClick = { onBrowse(pathInput) }) { Text("Open") }
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { serverParentPath(browserPath)?.let(onBrowse) },
                            enabled = serverParentPath(browserPath) != null,
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Parent folder")
                        }
                        Text(
                            browserPath.ifBlank { "No folder selected" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = CodexMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { browsing = false }) { Text("Projects") }
                    }
                    when {
                        browserLoading -> Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Loading folders…", color = CodexMuted)
                        }

                        browserError != null -> Text(
                            browserError,
                            color = CodexDanger,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        browserDirectories.isEmpty() -> Text(
                            "No subfolders. You can still use this folder.",
                            color = CodexMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        else -> LazyColumn(
                            modifier = Modifier.heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(browserDirectories, key = { it.path }) { directory ->
                                FolderRow(
                                    name = directory.name,
                                    path = directory.path,
                                    onClick = { onBrowse(directory.path) },
                                )
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        placeholder = { Text("Search projects…") },
                    )
                    if (filteredProjects.isEmpty()) {
                        Text(
                            if (query.isBlank()) {
                                "No projects yet. Browse server folders to choose one."
                            } else {
                                "No matching projects."
                            },
                            modifier = Modifier.padding(vertical = 20.dp),
                            color = CodexMuted,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(filteredProjects, key = { it.workingDirectory }) { project ->
                                ProjectRow(
                                    project = project,
                                    selected = project.workingDirectory == normalizedCurrent,
                                    onClick = { onSelect(project.workingDirectory) },
                                )
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = ::openBrowser),
                        color = CodexSurface,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.FolderOpen, null, tint = CodexLime, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Browse server folders", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "Choose an existing directory or enter its absolute path",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CodexMuted,
                                )
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = CodexMuted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (browsing) {
                Button(
                    onClick = { onSelect(browserPath) },
                    enabled = browserPath.isNotBlank(),
                ) { Text("Use this folder") }
            } else {
                TextButton(onClick = ::openBrowser) { Text("Browse folders") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProjectRow(
    project: ProjectGroup,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) CodexSurface else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Folder, null, tint = if (selected) CodexLime else CodexMuted, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        project.displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${project.threads.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = CodexMuted,
                    )
                }
                Text(
                    project.workingDirectory.ifBlank { "No project folder" },
                    style = MaterialTheme.typography.labelMedium,
                    color = CodexMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, path: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = CodexSurface,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CodexOutline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Folder, null, tint = CodexLime, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ChevronRight, null, tint = CodexMuted)
        }
    }
}
