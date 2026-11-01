package io.github.alextherajunction.codexweb.protocol

internal data class DiffStats(
    val fileCount: Int,
    val additions: Int,
    val deletions: Int,
)

internal enum class DiffLineKind {
    META,
    HUNK,
    ADDED,
    REMOVED,
    CONTEXT,
}

internal data class DiffLine(
    val text: String,
    val kind: DiffLineKind,
)

internal fun summarizeDiff(diff: String): DiffStats {
    var gitFileCount = 0
    var headerFileCount = 0
    var additions = 0
    var deletions = 0
    diff.lineSequence().forEach { line ->
        when {
            line.startsWith("diff --git ") -> gitFileCount += 1
            line.startsWith("+++ ") -> headerFileCount += 1
            line.startsWith("--- ") -> Unit
            line.startsWith("+") -> additions += 1
            line.startsWith("-") -> deletions += 1
        }
    }
    val fileCount = maxOf(gitFileCount, headerFileCount).takeIf { it > 0 }
        ?: if (diff.isBlank()) 0 else 1
    return DiffStats(
        fileCount = fileCount,
        additions = additions,
        deletions = deletions,
    )
}

internal fun classifyDiffLines(diff: String): List<DiffLine> = diff
    .lineSequence()
    .map { line -> DiffLine(text = line, kind = classifyDiffLine(line)) }
    .toList()

private fun classifyDiffLine(line: String): DiffLineKind = when {
    line.startsWith("@@") -> DiffLineKind.HUNK
    line.startsWith("diff ") ||
        line.startsWith("--- ") ||
        line.startsWith("+++ ") ||
        line.startsWith("index ") ||
        line.startsWith("new file mode") ||
        line.startsWith("deleted file mode") ||
        line.startsWith("similarity index") ||
        line.startsWith("rename ") -> DiffLineKind.META
    line.startsWith("+") -> DiffLineKind.ADDED
    line.startsWith("-") -> DiffLineKind.REMOVED
    else -> DiffLineKind.CONTEXT
}
