package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `threads are grouped by cwd with the active project first`() {
        val groups = groupThreadsByProjects(
            threads = listOf(
                thread("a-old", "/work/alpha", 10),
                thread("b-new", "/work/beta", 20),
                thread("a-new", "/work/alpha", 30),
            ),
            activeWorkingDirectory = "/work/beta",
        )

        assertEquals(listOf("beta", "alpha"), groups.map { it.displayName })
        assertEquals(listOf("a-new", "a-old"), groups.last().threads.map { it.id })
        assertEquals(30L, groups.last().latestUpdatedAt)
    }

    @Test
    fun `active empty project appears once and before projects with threads`() {
        val groups = groupThreadsByProjects(
            threads = listOf(thread("existing", "/work/alpha", 10)),
            activeWorkingDirectory = "/work/new-project/",
        )

        assertEquals(listOf("/work/new-project", "/work/alpha"), groups.map { it.workingDirectory })
        assertTrue(groups.first().threads.isEmpty())
        assertEquals(1, groups.count { it.workingDirectory == "/work/new-project" })
    }

    @Test
    fun `blank thread cwd is shown as no project instead of being dropped`() {
        val group = groupThreadsByProjects(listOf(thread("legacy", "", 5))).single()

        assertEquals("", group.workingDirectory)
        assertEquals("No project", group.displayName)
        assertEquals(listOf("legacy"), group.threads.map { it.id })
    }

    @Test
    fun `project display name handles unix and windows separators`() {
        assertEquals("codex-web", projectDisplayName("/home/user/codex-web/"))
        assertEquals("codex-web", projectDisplayName("C:\\Users\\user\\codex-web"))
        assertEquals("/", projectDisplayName("/"))
    }

    @Test
    fun `directory browser joins paths and walks to parents`() {
        assertEquals("/home/test/other", joinServerPath("/home/test", "other"))
        assertEquals("/other", joinServerPath("/", "other"))
        assertEquals("/home", serverParentPath("/home/test/"))
        assertEquals("/", serverParentPath("/home"))
        assertNull(serverParentPath("/"))
        assertNull(serverParentPath(" "))
    }

    @Test
    fun `fs readDirectory response keeps only directories and joins their paths`() {
        val directories = parseServerDirectories(
            parentPath = "/home/test",
            response = json.parseToJsonElement(
                """
                    {"entries":[
                      {"fileName":"work","isDirectory":true,"isFile":false},
                      {"fileName":"README.md","isDirectory":false,"isFile":true},
                      {"fileName":"other","isDirectory":true,"isFile":false}
                    ]}
                """,
            ),
        )

        assertEquals(listOf("other", "work"), directories.map { it.name })
        assertEquals(listOf("/home/test/other", "/home/test/work"), directories.map { it.path })
    }

    private fun thread(id: String, cwd: String, updatedAt: Long): ThreadInfo = ThreadInfo(
        id = id,
        preview = id,
        name = null,
        cwd = cwd,
        updatedAt = updatedAt,
        status = "idle",
    )
}
