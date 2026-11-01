package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.JsonElement

data class ProjectGroup(
    val workingDirectory: String,
    val displayName: String,
    val threads: List<ThreadInfo>,
    val latestUpdatedAt: Long,
)

data class ServerDirectory(
    val name: String,
    val path: String,
)

internal fun groupThreadsByProjects(
    threads: List<ThreadInfo>,
    activeWorkingDirectory: String = "",
): List<ProjectGroup> {
    val active = normalizeProjectPath(activeWorkingDirectory)
    val grouped = linkedMapOf<String, MutableList<ThreadInfo>>()
    threads.forEach { thread ->
        val projectPath = normalizeProjectPath(thread.cwd)
        grouped.getOrPut(projectPath) { mutableListOf() }.add(thread)
    }
    if (active.isNotBlank() && active !in grouped) grouped[active] = mutableListOf()

    return grouped.map { (path, projectThreads) ->
        val sortedThreads = projectThreads.sortedByDescending { it.updatedAt }
        ProjectGroup(
            workingDirectory = path,
            displayName = projectDisplayName(path),
            threads = sortedThreads,
            latestUpdatedAt = sortedThreads.maxOfOrNull { it.updatedAt } ?: 0L,
        )
    }.sortedWith(
        compareByDescending<ProjectGroup> { active.isNotBlank() && it.workingDirectory == active }
            .thenByDescending { it.latestUpdatedAt }
            .thenBy { it.displayName.lowercase() },
    )
}

internal fun projectDisplayName(path: String): String {
    val normalized = normalizeProjectPath(path)
    if (normalized.isBlank()) return "No project"
    if (normalized == "/") return "/"
    return normalized.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { normalized }
}

internal fun normalizeProjectPath(value: String): String {
    var path = value.trim()
    if (path.isBlank()) return ""
    if (path == "/") return path
    if (Regex("^[A-Za-z]:[\\\\/]$").matches(path)) return path
    while (path.length > 1 && (path.endsWith('/') || path.endsWith('\\'))) {
        path = path.dropLast(1)
    }
    return path
}

internal fun joinServerPath(parent: String, child: String): String {
    val base = normalizeProjectPath(parent)
    val name = child.trim()
    if (base.isBlank()) return name
    if (name.isBlank()) return base
    val separator = if (base.contains('\\') && !base.contains('/')) '\\' else '/'
    return if (base.endsWith('/') || base.endsWith('\\')) "$base$name" else "$base$separator$name"
}

internal fun serverParentPath(path: String): String? {
    val normalized = normalizeProjectPath(path)
    if (normalized.isBlank() || normalized == "/") return null
    if (Regex("^[A-Za-z]:[\\\\/]$").matches(normalized)) return null
    val separatorIndex = maxOf(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
    if (separatorIndex < 0) return null
    if (separatorIndex == 0) return "/"
    if (separatorIndex == 2 && normalized[1] == ':') return normalized.substring(0, 3)
    return normalized.substring(0, separatorIndex)
}

internal fun parseServerDirectories(parentPath: String, response: JsonElement): List<ServerDirectory> {
    val parent = normalizeProjectPath(parentPath).ifBlank { "/" }
    return response.asObjectOrNull()?.array("entries")
        ?.mapNotNull { raw -> raw.asObjectOrNull() }
        ?.filter { it.boolean("isDirectory") == true }
        ?.mapNotNull { entry ->
            val name = entry.string("fileName")?.trim().orEmpty()
            if (name.isBlank()) null else ServerDirectory(name, joinServerPath(parent, name))
        }
        ?.distinctBy { it.path }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()
}
