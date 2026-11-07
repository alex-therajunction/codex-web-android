package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `thread starts use the web permission profile mappings`() {
        val request = buildJsonObject { putThreadPermissionOptions(PermissionProfile.REQUEST_APPROVAL) }
        assertEquals("on-request", request.string("approvalPolicy"))
        assertEquals("user", request.string("approvalsReviewer"))
        assertEquals("workspace-write", request.string("sandbox"))

        val auto = buildJsonObject { putThreadPermissionOptions(PermissionProfile.AUTO_APPROVAL) }
        assertEquals("on-request", auto.string("approvalPolicy"))
        assertEquals("auto_review", auto.string("approvalsReviewer"))
        assertEquals("workspace-write", auto.string("sandbox"))

        val full = buildJsonObject { putThreadPermissionOptions(PermissionProfile.FULL_ACCESS) }
        assertEquals("never", full.string("approvalPolicy"))
        assertEquals("user", full.string("approvalsReviewer"))
        assertEquals("danger-full-access", full.string("sandbox"))

        assertTrue(buildJsonObject { putThreadPermissionOptions(PermissionProfile.CONFIG) }.isEmpty())
    }

    @Test
    fun `turn starts override full access and workspace permissions`() {
        val config = EffectivePermissionConfig(
            writableRoots = listOf("/configured"),
            networkAccess = true,
            excludeTmpdirEnvVar = true,
            excludeSlashTmp = true,
        )
        val workspace = buildJsonObject {
            putTurnPermissionOptions(PermissionProfile.REQUEST_APPROVAL, "/work", config)
        }
        assertEquals("on-request", workspace.string("approvalPolicy"))
        assertEquals("user", workspace.string("approvalsReviewer"))
        val workspaceSandbox = workspace.obj("sandboxPolicy")!!
        assertEquals("workspaceWrite", workspaceSandbox.string("type"))
        assertEquals(listOf("/configured"), workspaceSandbox.stringList("writableRoots"))
        assertEquals(true, workspaceSandbox.boolean("networkAccess"))
        assertEquals(true, workspaceSandbox.boolean("excludeTmpdirEnvVar"))
        assertEquals(true, workspaceSandbox.boolean("excludeSlashTmp"))

        val full = buildJsonObject {
            putTurnPermissionOptions(PermissionProfile.FULL_ACCESS, "/work", config)
        }
        assertEquals("never", full.string("approvalPolicy"))
        assertEquals("dangerFullAccess", full.obj("sandboxPolicy")?.string("type"))

        assertTrue(buildJsonObject {
            putTurnPermissionOptions(PermissionProfile.CONFIG, "/work", config)
        }.isEmpty())
    }

    @Test
    fun `thread settings updates use built in profiles or configured fallback`() {
        val full = buildJsonObject {
            putThreadPermissionUpdateOptions(PermissionProfile.FULL_ACCESS, EffectivePermissionConfig())
        }
        assertEquals("never", full.string("approvalPolicy"))
        assertEquals(":danger-full-access", full.string("permissions"))

        val auto = buildJsonObject {
            putThreadPermissionUpdateOptions(PermissionProfile.AUTO_APPROVAL, EffectivePermissionConfig())
        }
        assertEquals("on-request", auto.string("approvalPolicy"))
        assertEquals("auto_review", auto.string("approvalsReviewer"))
        assertEquals(":workspace", auto.string("permissions"))

        val custom = buildJsonObject {
            putThreadPermissionUpdateOptions(
                PermissionProfile.CONFIG,
                EffectivePermissionConfig(configuredProfileId = "team-profile"),
            )
        }
        assertEquals("team-profile", custom.string("permissions"))

        val fallback = buildJsonObject {
            putThreadPermissionUpdateOptions(
                PermissionProfile.CONFIG,
                EffectivePermissionConfig(
                    approvalPolicy = "on-request",
                    approvalsReviewer = "user",
                    sandboxMode = "read-only",
                ),
            )
        }
        assertEquals("on-request", fallback.string("approvalPolicy"))
        assertEquals("readOnly", fallback.obj("sandboxPolicy")?.string("type"))
        assertNull(fallback.string("permissions"))
    }

    @Test
    fun `effective config determines the new chat permission default`() {
        assertEquals(
            PermissionProfile.FULL_ACCESS,
            parseEffectivePermissionConfig(parse("""{"config":{"default_permissions":":danger-full-access"}}"""))
                .defaultProfile,
        )
        assertEquals(
            PermissionProfile.CONFIG,
            parseEffectivePermissionConfig(parse("""{"config":{"default_permissions":"team-profile"}}"""))
                .defaultProfile,
        )
        assertEquals(
            PermissionProfile.AUTO_APPROVAL,
            parseEffectivePermissionConfig(
                parse("""{"config":{"default_permissions":":workspace","approvals_reviewer":"auto_review"}}"""),
            ).defaultProfile,
        )
        assertEquals(
            PermissionProfile.REQUEST_APPROVAL,
            parseEffectivePermissionConfig(parse("""{"config":{"sandbox_mode":"workspace-write"}}"""))
                .defaultProfile,
        )
    }

    @Test
    fun `full access default writes the complete permission mode`() {
        val edits = defaultPermissionModeConfigEdits(PermissionProfile.FULL_ACCESS)

        assertEquals(
            listOf("default_permissions", "approvals_reviewer", "approval_policy", "sandbox_mode"),
            edits.map { it.string("keyPath") },
        )
        assertEquals(":danger-full-access", edits[0].string("value"))
        assertEquals("user", edits[1].string("value"))
        assertEquals("never", edits[2].string("value"))
        assertEquals("danger-full-access", edits[3].string("value"))
        assertTrue(edits.all { it.string("mergeStrategy") == "replace" })
    }

    @Test
    fun `workspace defaults write all keys and custom config writes none`() {
        val auto = defaultPermissionModeConfigEdits(PermissionProfile.AUTO_APPROVAL)
        assertEquals(":workspace", auto[0].string("value"))
        assertEquals("auto_review", auto[1].string("value"))
        assertEquals("on-request", auto[2].string("value"))
        assertEquals("workspace-write", auto[3].string("value"))
        assertTrue(defaultPermissionModeConfigEdits(PermissionProfile.CONFIG).isEmpty())
    }

    @Test
    fun `resumed thread settings are mapped back to a permission profile`() {
        assertEquals(
            PermissionProfile.FULL_ACCESS,
            permissionProfileFromRuntimeSettings(parse("""{"approvalPolicy":"never","sandbox":{"type":"dangerFullAccess"}}""")),
        )
        assertEquals(
            PermissionProfile.AUTO_APPROVAL,
            permissionProfileFromRuntimeSettings(
                parse("""{"approvalPolicy":"on-request","approvalsReviewer":"auto_review","sandbox":{"type":"workspaceWrite"}}"""),
            ),
        )
        assertEquals(
            PermissionProfile.CONFIG,
            permissionProfileFromRuntimeSettings(parse("""{"sandbox":{"type":"readOnly","networkAccess":false}}""")),
        )
        assertEquals(
            PermissionProfile.FULL_ACCESS,
            permissionProfileFromRuntimeSettings(
                parse("""{"activePermissionProfile":{"id":":danger-full-access","extends":null}}"""),
            ),
        )
    }

    private fun parse(value: String): JsonObject = json.parseToJsonElement(value).jsonObject
}
