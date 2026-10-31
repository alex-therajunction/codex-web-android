package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal fun buildApprovalResponse(
    request: PendingRequest,
    decision: ApprovalDecision,
): JsonElement? = when (request.method) {
    "item/commandExecution/requestApproval",
    "item/fileChange/requestApproval" -> buildJsonObject {
        put(
            "decision",
            when (decision) {
                ApprovalDecision.ALLOW -> "accept"
                ApprovalDecision.ALLOW_SESSION -> "acceptForSession"
                ApprovalDecision.DENY -> "decline"
            },
        )
    }
    "item/permissions/requestApproval" -> {
        if (decision == ApprovalDecision.DENY) {
            buildJsonObject {
                putJsonObject("permissions") { }
                put("scope", "turn")
            }
        } else {
            val requested = request.params.obj("permissions")
            buildJsonObject {
                put("permissions", buildJsonObject {
                    requested?.get("network")?.takeUnless { it is JsonNull }?.let { put("network", it) }
                    requested?.get("fileSystem")?.takeUnless { it is JsonNull }?.let { put("fileSystem", it) }
                })
                put("scope", if (decision == ApprovalDecision.ALLOW_SESSION) "session" else "turn")
            }
        }
    }
    else -> null
}

internal fun buildUserInputResponse(answers: Map<String, List<String>>): JsonElement = buildJsonObject {
    put("answers", buildJsonObject {
        answers.forEach { (questionId, values) ->
            putJsonObject(questionId) {
                putJsonArray("answers") {
                    values.forEach { add(JsonPrimitive(it)) }
                }
            }
        }
    })
}

internal fun buildMcpCancelResponse(): JsonElement = buildJsonObject {
    put("action", "cancel")
    put("content", JsonNull)
    put("_meta", JsonNull)
}
