package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class PermissionProfile {
    REQUEST_APPROVAL,
    AUTO_APPROVAL,
    FULL_ACCESS,
    CONFIG,
}

internal data class EffectivePermissionConfig(
    val defaultProfile: PermissionProfile = PermissionProfile.REQUEST_APPROVAL,
    val configuredProfileId: String = "",
    val approvalPolicy: String = "on-request",
    val approvalsReviewer: String = "user",
    val sandboxMode: String = "workspace-write",
    val writableRoots: List<String> = emptyList(),
    val networkAccess: Boolean = false,
    val excludeTmpdirEnvVar: Boolean = false,
    val excludeSlashTmp: Boolean = false,
)

internal fun parseEffectivePermissionConfig(response: JsonElement?): EffectivePermissionConfig {
    val config = response?.asObjectOrNull()?.obj("config") ?: return EffectivePermissionConfig()
    val configuredProfileId = config.string("default_permissions").orEmpty()
    val profile = when {
        configuredProfileId == ":danger-full-access" -> PermissionProfile.FULL_ACCESS
        configuredProfileId.isNotBlank() && configuredProfileId != ":workspace" -> PermissionProfile.CONFIG
        else -> resolvePermissionProfile(
            activeProfileId = configuredProfileId.takeIf { it == ":workspace" },
            activeParentId = null,
            approvalPolicy = config["approval_policy"],
            approvalsReviewer = config.string("approvals_reviewer"),
            sandbox = config["sandbox_mode"],
        )
    }
    val workspaceWrite = config.obj("sandbox_workspace_write")
    return EffectivePermissionConfig(
        defaultProfile = profile,
        configuredProfileId = configuredProfileId,
        approvalPolicy = config.string("approval_policy") ?: "on-request",
        approvalsReviewer = config.string("approvals_reviewer") ?: "user",
        sandboxMode = config.string("sandbox_mode") ?: "workspace-write",
        writableRoots = workspaceWrite?.stringList("writable_roots").orEmpty(),
        networkAccess = workspaceWrite?.boolean("network_access") == true,
        excludeTmpdirEnvVar = workspaceWrite?.boolean("exclude_tmpdir_env_var") == true,
        excludeSlashTmp = workspaceWrite?.boolean("exclude_slash_tmp") == true,
    )
}

internal fun permissionProfileFromRuntimeSettings(settings: JsonObject): PermissionProfile {
    val activeProfile = settings.obj("activePermissionProfile")
    return resolvePermissionProfile(
        activeProfileId = activeProfile?.string("id"),
        activeParentId = activeProfile?.string("extends"),
        approvalPolicy = settings["approvalPolicy"],
        approvalsReviewer = settings.string("approvalsReviewer"),
        sandbox = settings["sandboxPolicy"] ?: settings["sandbox"],
    )
}

internal fun JsonObjectBuilder.putThreadPermissionOptions(profile: PermissionProfile) {
    if (profile == PermissionProfile.CONFIG) return
    put("approvalPolicy", approvalPolicy(profile))
    put("approvalsReviewer", approvalsReviewer(profile))
    put("sandbox", if (profile == PermissionProfile.FULL_ACCESS) {
        "danger-full-access"
    } else {
        "workspace-write"
    })
}

internal fun JsonObjectBuilder.putTurnPermissionOptions(
    profile: PermissionProfile,
    cwd: String,
    config: EffectivePermissionConfig,
) {
    if (profile == PermissionProfile.CONFIG) return
    put("approvalPolicy", approvalPolicy(profile))
    put("approvalsReviewer", approvalsReviewer(profile))
    if (profile == PermissionProfile.FULL_ACCESS) {
        putJsonObject("sandboxPolicy") { put("type", "dangerFullAccess") }
    } else {
        putWorkspaceWriteSandbox(
            writableRoots = config.writableRoots.ifEmpty { listOf(cwd) },
            config = config,
        )
    }
}

internal fun JsonObjectBuilder.putThreadPermissionUpdateOptions(
    profile: PermissionProfile,
    config: EffectivePermissionConfig,
) {
    when (profile) {
        PermissionProfile.FULL_ACCESS -> {
            put("approvalPolicy", "never")
            put("approvalsReviewer", "user")
            put("permissions", ":danger-full-access")
        }
        PermissionProfile.REQUEST_APPROVAL,
        PermissionProfile.AUTO_APPROVAL -> {
            put("approvalPolicy", "on-request")
            put("approvalsReviewer", approvalsReviewer(profile))
            put("permissions", ":workspace")
        }
        PermissionProfile.CONFIG -> {
            if (config.configuredProfileId.isNotBlank()) {
                put("permissions", config.configuredProfileId)
            } else {
                put("approvalPolicy", config.approvalPolicy)
                put("approvalsReviewer", config.approvalsReviewer)
                when (config.sandboxMode) {
                    "danger-full-access" -> putJsonObject("sandboxPolicy") {
                        put("type", "dangerFullAccess")
                    }
                    "read-only" -> putJsonObject("sandboxPolicy") {
                        put("type", "readOnly")
                        put("networkAccess", false)
                    }
                    else -> putWorkspaceWriteSandbox(config.writableRoots, config)
                }
            }
        }
    }
}

private fun JsonObjectBuilder.putWorkspaceWriteSandbox(
    writableRoots: List<String>,
    config: EffectivePermissionConfig,
) {
    putJsonObject("sandboxPolicy") {
        put("type", "workspaceWrite")
        putJsonArray("writableRoots") { writableRoots.forEach(::add) }
        put("networkAccess", config.networkAccess)
        put("excludeTmpdirEnvVar", config.excludeTmpdirEnvVar)
        put("excludeSlashTmp", config.excludeSlashTmp)
    }
}

private fun approvalPolicy(profile: PermissionProfile): String =
    if (profile == PermissionProfile.FULL_ACCESS) "never" else "on-request"

private fun approvalsReviewer(profile: PermissionProfile): String =
    if (profile == PermissionProfile.AUTO_APPROVAL) "auto_review" else "user"

private fun resolvePermissionProfile(
    activeProfileId: String?,
    activeParentId: String?,
    approvalPolicy: JsonElement?,
    approvalsReviewer: String?,
    sandbox: JsonElement?,
): PermissionProfile {
    val profileId = activeProfileId?.takeIf { it.isNotBlank() }
        ?: activeParentId?.takeIf { it.isNotBlank() }
    if (profileId == ":danger-full-access") return PermissionProfile.FULL_ACCESS
    if (profileId != null && profileId != ":workspace") return PermissionProfile.CONFIG
    if (approvalsReviewer == "auto_review") return PermissionProfile.AUTO_APPROVAL

    val sandboxType = when (sandbox) {
        is JsonPrimitive -> sandbox.contentOrNull
        is JsonObject -> sandbox.string("type")
        else -> null
    }
    when (sandboxType) {
        "danger-full-access", "dangerFullAccess" -> return PermissionProfile.FULL_ACCESS
        "read-only", "readOnly", "externalSandbox" -> return PermissionProfile.CONFIG
    }
    val approval = (approvalPolicy as? JsonPrimitive)?.contentOrNull
    if (approval == "never") return PermissionProfile.FULL_ACCESS
    return PermissionProfile.REQUEST_APPROVAL
}
