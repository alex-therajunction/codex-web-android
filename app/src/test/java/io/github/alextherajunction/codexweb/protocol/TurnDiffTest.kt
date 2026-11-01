package io.github.alextherajunction.codexweb.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnDiffTest {
    @Test
    fun `summarizes files and changed lines without counting file headers`() {
        val diff = """
            diff --git a/src/app.ts b/src/app.ts
            --- a/src/app.ts
            +++ b/src/app.ts
            @@ -1,2 +1,3 @@
             const value = 1;
            -const oldValue = 2;
            +const nextValue = 2;
            +export { nextValue };
        """.trimIndent()

        assertEquals(
            DiffStats(fileCount = 1, additions = 2, deletions = 1),
            summarizeDiff(diff),
        )
    }

    @Test
    fun `classifies diff lines for colorized rendering`() {
        val lines = classifyDiffLines(
            """
                diff --git a/file.txt b/file.txt
                --- a/file.txt
                +++ b/file.txt
                @@ -1 +1 @@
                -old
                +new
                 context
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                DiffLineKind.META,
                DiffLineKind.META,
                DiffLineKind.META,
                DiffLineKind.HUNK,
                DiffLineKind.REMOVED,
                DiffLineKind.ADDED,
                DiffLineKind.CONTEXT,
            ),
            lines.map { it.kind },
        )
    }
}
