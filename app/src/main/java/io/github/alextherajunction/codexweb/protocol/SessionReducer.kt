package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val knownNoOpNotifications = setOf(
    "skills/changed",
    "mcpServer/startupStatus/updated",
    "mcpServer/oauthLogin/completed",
    "account/login/completed",
    "account/rateLimits/updated",
    "remoteControl/status/changed",
    "thread/realtime/started",
    "thread/realtime/closed",
    "thread/realtime/error",
    "serverRequest/resolved",
    "process/outputDelta",
    "process/exited",
)

fun AppServerSnapshot.applyNotification(notification: RpcNotification): AppServerSnapshot {
    val method = notification.method
    val params = notification.params ?: JsonObject(emptyMap())
    return try {
        when (method) {
            "thread/started" -> {
                val thread = params.obj("thread")?.let { ThreadInfo.fromJson(it) }
                if (thread == null) diagnosed(notification) else withThread(thread)
            }
            "thread/status/changed" -> {
                val threadId = params.string("threadId")
                val status = params.obj("status")
                if (threadId == null || status == null) {
                    diagnosed(notification)
                } else {
                    val statusType = status.string("type") ?: "idle"
                    val flags = status.stringList("activeFlags")
                    copy(
                        threads = threads.map {
                            if (it.id == threadId) it.copy(status = statusType, activeFlags = flags) else it
                        },
                        threadStatusByThread = threadStatusByThread + (threadId to statusType),
                    )
                }
            }
            "thread/name/updated" -> {
                val threadId = params.string("threadId")
                if (threadId == null) {
                    diagnosed(notification)
                } else {
                    copy(
                        threads = threads.map {
                            if (it.id == threadId) it.copy(name = params.string("threadName")) else it
                        },
                    )
                }
            }
            "thread/settings/updated" -> {
                val threadId = params.string("threadId")
                val settings = params.obj("threadSettings")
                if (threadId == null || settings == null) {
                    copy()
                } else if (selectedThreadId == threadId) {
                    copy(selectedPermissionProfile = permissionProfileFromRuntimeSettings(settings))
                } else {
                    copy()
                }
            }
            "turn/started" -> applyTurn(params, completed = false)
            "turn/completed" -> applyTurn(params, completed = true)
            "item/started" -> upsertNotificationItem(params, completed = false)
            "item/completed" -> upsertNotificationItem(params, completed = true)
            "item/agentMessage/delta" -> appendToItem(params, "agentMessage", "delta")
            "item/commandExecution/outputDelta" -> appendToItem(params, "commandExecution", "delta", detail = true)
            "item/commandExecution/terminalInteraction" -> applyTerminalInteraction(params)
            "item/fileChange/outputDelta" -> appendToItem(params, "fileChange", "delta", detail = true)
            "item/reasoning/summaryTextDelta" -> appendToItem(params, "reasoning", "delta")
            "item/reasoning/textDelta" -> appendToItem(params, "reasoning", "delta")
            "item/plan/delta" -> appendToItem(params, "plan", "delta")
            "item/fileChange/patchUpdated" -> applyFileChangePatch(params)
            "turn/diff/updated" -> applyTurnDiff(params)
            "turn/plan/updated" -> {
                val threadId = params.string("threadId")
                if (threadId == null) diagnosed(notification)
                else copy(planByThread = planByThread + (threadId to parsePlan(params)))
            }
            "thread/tokenUsage/updated" -> {
                val threadId = params.string("threadId")
                val usage = params.obj("tokenUsage")
                if (threadId == null || usage == null) diagnosed(notification)
                else copy(usageByThread = usageByThread + (threadId to parseUsage(usage)))
            }
            "thread/goal/updated" -> {
                val goal = params.obj("goal")
                val threadId = params.string("threadId") ?: goal?.string("threadId")
                if (threadId == null || goal == null) diagnosed(notification)
                else copy(goalByThread = goalByThread + (threadId to parseGoal(goal)))
            }
            "account/updated" -> {
                val authMode = params.string("authMode").orEmpty()
                val planType = params.string("planType").orEmpty()
                val label = listOf(authMode, planType).filter { it.isNotBlank() }.joinToString(" · ")
                if (label.isBlank()) copy(codexAccount = "Signed in") else copy(codexAccount = label)
            }
            "thread/goal/cleared" -> {
                val threadId = params.string("threadId")
                if (threadId == null) diagnosed(notification) else copy(goalByThread = goalByThread - threadId)
            }
            "serverRequest/resolved" -> {
                val requestId = params["requestId"]
                if (requestId == null) copy() else copy(
                    pendingRequests = pendingRequests.filterNot { it.id == requestId },
                )
            }
            "bridge/error" -> {
                val message = params.string("message") ?: "Web bridge error"
                copy(
                    lastError = message,
                    diagnostics = appendDiagnostic(method, message),
                )
            }
            "deprecationNotice" -> {
                val summary = params.string("summary") ?: "App-server deprecation notice"
                val details = params.string("details")
                val diagnostic = listOf(summary, details)
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                if (diagnostics.any { it.method == "app-server.deprecationNotice" && it.summary == diagnostic }) {
                    copy()
                } else {
                    copy(
                        diagnostics = appendDiagnostic("app-server.deprecationNotice", diagnostic),
                    )
                }
            }
            in knownNoOpNotifications -> copy()
            else -> diagnosed(notification)
        }
    } catch (error: Exception) {
        diagnosed(
            notification,
            "Failed to reduce notification: ${error.message ?: error::class.simpleName}",
        )
    }
}

fun AppServerSnapshot.withThread(thread: ThreadInfo): AppServerSnapshot {
    val existing = threads.firstOrNull { it.id == thread.id }
    val merged = if (existing == null) {
        thread
    } else {
        thread.copy(
            turns = existing.turns.ifEmpty { thread.turns },
        )
    }
    val nextThreads = (threads.filterNot { it.id == thread.id } + merged)
        .sortedByDescending { it.updatedAt }
    return copy(
        threads = nextThreads,
        threadStatusByThread = threadStatusByThread + (thread.id to thread.status),
        turnsByThread = if (merged.turns.isNotEmpty()) {
            turnsByThread + (thread.id to merged.turns)
        } else {
            turnsByThread
        },
    )
}

fun AppServerSnapshot.hydrateThread(thread: ThreadInfo): AppServerSnapshot {
    val activeTurnId = thread.turns.lastOrNull { it.status == "inProgress" }?.id
    val turns = preserveTurnDiffs(thread.turns, turnsByThread[thread.id].orEmpty())
    return withThread(thread).copy(
        turnsByThread = turnsByThread + (thread.id to turns),
        activeTurnByThread = if (activeTurnId != null) {
            activeTurnByThread + (thread.id to activeTurnId)
        } else {
            activeTurnByThread - thread.id
        },
    )
}

fun AppServerSnapshot.addPendingRequest(request: PendingRequest): AppServerSnapshot =
    copy(pendingRequests = pendingRequests.filterNot { it.id == request.id } + request)

fun AppServerSnapshot.removePendingRequest(requestId: JsonElement): AppServerSnapshot =
    copy(pendingRequests = pendingRequests.filterNot { it.id == requestId })

fun AppServerSnapshot.withError(message: String?): AppServerSnapshot =
    copy(lastError = message)

fun AppServerSnapshot.appendDiagnostic(method: String, summary: String): List<Diagnostic> =
    diagnostics.appendDiagnostic(method, summary)

fun List<Diagnostic>.appendDiagnostic(method: String, summary: String): List<Diagnostic> =
    (this + Diagnostic(method = method, summary = summary.take(1_200))).takeLast(50)

private fun AppServerSnapshot.diagnosed(
    notification: RpcNotification,
    summary: String = notification.params?.toString()?.take(600) ?: "No params",
): AppServerSnapshot = copy(
    diagnostics = appendDiagnostic("unhandled:${notification.method}", summary),
)

private fun AppServerSnapshot.applyTurn(
    params: JsonObject,
    completed: Boolean,
): AppServerSnapshot {
    val threadId = params.string("threadId")
    val turnJson = params.obj("turn")
    if (threadId == null || turnJson == null) {
        return diagnosed(RpcNotification(if (completed) "turn/completed" else "turn/started", params))
    }
    val turn = TurnInfo.fromJson(turnJson)
    val existing = turnsByThread[threadId].orEmpty()
    val previous = existing.firstOrNull { it.id == turn.id }
    val merged = turn.copy(
        items = if (turn.items.isEmpty()) previous?.items.orEmpty() else turn.items,
        diff = turn.diff.ifBlank { previous?.diff.orEmpty() },
    )
    val nextTurns = if (previous == null) existing + merged else existing.map {
        if (it.id == merged.id) merged else it
    }
    val activeTurns = if (completed) {
        activeTurnByThread - threadId
    } else {
        activeTurnByThread + (threadId to turn.id)
    }
    return copy(
        turnsByThread = turnsByThread + (threadId to nextTurns),
        activeTurnByThread = activeTurns,
        threads = threads.map {
            if (it.id == threadId) {
                it.copy(status = if (completed) "idle" else "active", updatedAt = System.currentTimeMillis() / 1000)
            } else {
                it
            }
        }.sortedByDescending { it.updatedAt },
        lastError = if (completed) turn.error ?: lastError else lastError,
    )
}

private fun AppServerSnapshot.upsertNotificationItem(
    params: JsonObject,
    completed: Boolean,
): AppServerSnapshot {
    val threadId = params.string("threadId") ?: return diagnosed(
        RpcNotification(if (completed) "item/completed" else "item/started", params),
    )
    val turnId = params.string("turnId")
    val itemJson = params.obj("item") ?: return diagnosed(
        RpcNotification(if (completed) "item/completed" else "item/started", params),
    )
    val item = parseChatItem(itemJson).copy(isStreaming = !completed)
    return putItem(threadId, turnId, item)
}

private fun AppServerSnapshot.applyFileChangePatch(params: JsonObject): AppServerSnapshot {
    val threadId = params.string("threadId") ?: return diagnosed(
        RpcNotification("item/fileChange/patchUpdated", params),
    )
    val itemId = params.string("itemId") ?: return diagnosed(
        RpcNotification("item/fileChange/patchUpdated", params),
    )
    val changes = params.array("changes") ?: return diagnosed(
        RpcNotification("item/fileChange/patchUpdated", params),
    )
    return updateTurnItem(threadId, params.string("turnId"), itemId) { existing ->
        (existing ?: ChatItem(
            id = itemId,
            kind = "fileChange",
            title = "File changes",
            status = "inProgress",
        )).copy(
            detail = formatFileChanges(changes),
            isStreaming = true,
        )
    }
}

private fun AppServerSnapshot.applyTurnDiff(params: JsonObject): AppServerSnapshot {
    val threadId = params.string("threadId") ?: return diagnosed(RpcNotification("turn/diff/updated", params))
    val turnId = params.string("turnId") ?: return diagnosed(RpcNotification("turn/diff/updated", params))
    val diff = params.string("diff") ?: return diagnosed(RpcNotification("turn/diff/updated", params))
    val turns = turnsByThread[threadId].orEmpty().toMutableList()
    val index = turns.indexOfFirst { it.id == turnId }
    if (index >= 0) {
        turns[index] = turns[index].copy(diff = diff)
    } else {
        turns.add(TurnInfo(id = turnId, status = "inProgress", diff = diff))
    }
    return copy(turnsByThread = turnsByThread + (threadId to turns))
}

private fun AppServerSnapshot.putItem(
    threadId: String,
    turnId: String?,
    item: ChatItem,
): AppServerSnapshot = updateTurnItem(threadId, turnId, item.id) { existing ->
    if (existing != null && item.terminalInput.isBlank()) {
        item.copy(terminalInput = existing.terminalInput)
    } else {
        item
    }
}

private fun AppServerSnapshot.updateTurnItem(
    threadId: String,
    turnId: String?,
    itemId: String,
    update: (ChatItem?) -> ChatItem,
): AppServerSnapshot {
    val turns = turnsByThread[threadId].orEmpty().toMutableList()
    val effectiveTurnId = turnId ?: turns.lastOrNull()?.id ?: "unknown"
    val index = turns.indexOfFirst { it.id == effectiveTurnId }
    val target = if (index >= 0) {
        turns[index]
    } else {
        TurnInfo(id = effectiveTurnId, status = "inProgress")
            .also { turns.add(it) }
    }
    val turnIndex = turns.indexOfFirst { it.id == target.id }
    val existingItem = target.items.firstOrNull { it.id == itemId }
    val updatedItem = update(existingItem)
    val items = target.items.filterNot { it.id == itemId } + updatedItem
    turns[turnIndex] = target.copy(items = items)
    return copy(
        turnsByThread = turnsByThread + (threadId to turns),
        threads = threads.map {
            if (it.id == threadId) it.copy(updatedAt = System.currentTimeMillis() / 1000) else it
        }.sortedByDescending { it.updatedAt },
    )
}

private fun preserveTurnDiffs(incoming: List<TurnInfo>, existing: List<TurnInfo>): List<TurnInfo> {
    if (incoming.isEmpty() || existing.isEmpty()) return incoming
    val diffsByTurn = existing.associate { it.id to it.diff }
    return incoming.map { turn ->
        if (turn.diff.isBlank()) turn.copy(diff = diffsByTurn[turn.id].orEmpty()) else turn
    }
}

private fun AppServerSnapshot.appendToItem(
    params: JsonObject,
    kind: String,
    textKey: String,
    detail: Boolean = false,
): AppServerSnapshot {
    val threadId = params.string("threadId") ?: return diagnosed(
        RpcNotification("item/delta", params),
    )
    val turnId = params.string("turnId")
    val itemId = params.string("itemId") ?: return diagnosed(RpcNotification("item/delta", params))
    val delta = params.string(textKey).orEmpty()
    if (delta.isEmpty()) return this
    val turns = turnsByThread[threadId].orEmpty().toMutableList()
    val effectiveTurnId = turnId ?: turns.lastOrNull()?.id ?: "unknown"
    var turnIndex = turns.indexOfFirst { it.id == effectiveTurnId }
    if (turnIndex < 0) {
        turns.add(TurnInfo(id = effectiveTurnId, status = "inProgress"))
        turnIndex = turns.lastIndex
    }
    val turn = turns[turnIndex]
    val existing = turn.items.firstOrNull { it.id == itemId }
    val updated = if (existing == null) {
        ChatItem(
            id = itemId,
            kind = kind,
            text = if (detail) "" else delta,
            detail = if (detail) delta else "",
            isStreaming = true,
        )
    } else if (detail) {
        existing.copy(detail = existing.detail + delta, isStreaming = true)
    } else {
        existing.copy(text = existing.text + delta, isStreaming = true)
    }
    val items = turn.items.filterNot { it.id == itemId } + updated
    turns[turnIndex] = turn.copy(items = items)
    return copy(turnsByThread = turnsByThread + (threadId to turns))
}

private fun AppServerSnapshot.applyTerminalInteraction(params: JsonObject): AppServerSnapshot {
    val threadId = params.string("threadId") ?: return diagnosed(
        RpcNotification("item/commandExecution/terminalInteraction", params),
    )
    val turnId = params.string("turnId")
    val itemId = params.string("itemId") ?: return diagnosed(
        RpcNotification("item/commandExecution/terminalInteraction", params),
    )
    val stdin = params.string("stdin").orEmpty()
    if (stdin.isEmpty()) return this

    val turns = turnsByThread[threadId].orEmpty().toMutableList()
    val effectiveTurnId = turnId ?: turns.lastOrNull()?.id ?: "unknown"
    var turnIndex = turns.indexOfFirst { it.id == effectiveTurnId }
    if (turnIndex < 0) {
        turns.add(TurnInfo(id = effectiveTurnId, status = "inProgress"))
        turnIndex = turns.lastIndex
    }
    val turn = turns[turnIndex]
    val existing = turn.items.firstOrNull { it.id == itemId } ?: ChatItem(
        id = itemId,
        kind = "commandExecution",
        title = "Command",
        status = "inProgress",
        isStreaming = true,
    )
    val updated = existing.copy(
        terminalInput = existing.terminalInput + stdin,
        isStreaming = true,
    )
    val items = turn.items.filterNot { it.id == itemId } + updated
    turns[turnIndex] = turn.copy(items = items)
    return copy(turnsByThread = turnsByThread + (threadId to turns))
}

