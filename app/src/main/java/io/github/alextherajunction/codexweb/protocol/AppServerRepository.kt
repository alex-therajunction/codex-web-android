package io.github.alextherajunction.codexweb.protocol

import io.github.alextherajunction.codexweb.BuildConfig
import io.github.alextherajunction.codexweb.data.DeploymentSession
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class ApprovalDecision {
    ALLOW,
    ALLOW_SESSION,
    DENY,
}

class AppServerRepository(
    private val scope: CoroutineScope,
    private val rpc: CodexRpcClient = CodexRpcClient(),
) {
    private val _state = MutableStateFlow(AppServerSnapshot())
    val state: StateFlow<AppServerSnapshot> = _state.asStateFlow()
    private val sendMutex = Mutex()
    private val resumedThreads = mutableSetOf<String>()
    private var session: DeploymentSession? = null
    private var effectivePermissionConfig = EffectivePermissionConfig()

    init {
        scope.launch {
            rpc.notificationFlow.collect { notification ->
                _state.update { it.applyNotification(notification) }
            }
        }
        scope.launch {
            rpc.serverRequestFlow.collect { request ->
                val pending = parsePendingRequest(request.id, request.method, request.params)
                _state.update { it.addPendingRequest(pending) }
            }
        }
        scope.launch {
            rpc.malformedMessageFlow.collect { message ->
                _state.update {
                    it.copy(diagnostics = it.appendDiagnostic("web-bridge", message))
                }
            }
        }
        scope.launch {
            rpc.closeEventFlow.collect { reason ->
                resumedThreads.clear()
                _state.update {
                    it.copy(
                        connection = "disconnected",
                        lastError = reason,
                        pendingRequests = emptyList(),
                        diagnostics = it.appendDiagnostic("web-bridge.close", reason),
                    )
                }
            }
        }
    }

    suspend fun connect(
        session: DeploymentSession,
        preferredThreadId: String? = null,
        openMostRecentThread: Boolean = true,
    ) {
        this.session = session
        _state.update { current ->
            current.copy(
                connection = "connecting",
                pendingRequests = emptyList(),
                deploymentUrl = session.deploymentUrl,
                bridgeUrl = redactUrl(session.bridgeUrl),
                userEmail = session.user.email,
                homeDirectory = session.homeDirectory,
                workspaceCwd = current.workspaceCwd.ifBlank {
                    session.user.cwd.ifBlank { session.homeDirectory }
                },
                lastError = null,
            )
        }
        rpc.connect(
            endpoint = session.bridgeUrl,
            cookieHeader = session.cookieHeader,
            origin = session.origin,
        )

        val modelsResponse = rpc.request("model/list", buildJsonObject {
            put("includeHidden", false)
        })
        val account = readAccount(rpc.request("account/read", buildJsonObject {
            put("refreshToken", false)
        }))

        val threadsResponse = rpc.request("thread/list", threadListParams())
        val threads = parseThreads(threadsResponse)
        val configRead = readEffectiveConfig()
        effectivePermissionConfig = parseEffectivePermissionConfig(configRead.response)
        val modelCatalog = resolveServerModelCatalog(
            modelsResponse = modelsResponse,
            configResponse = configRead.response,
            fallbackConfig = serverModelConfigFromThreads(threads),
        )

        _state.update { current ->
            val diagnostics = if (configRead.error == null) {
                current.diagnostics
            } else {
                current.appendDiagnostic(
                    "app-server.config/read",
                    "Effective config unavailable (${configRead.error}); using thread metadata when available.",
                )
            }
            val selectedModelId = current.selectedModelId.takeIf { selected ->
                modelCatalog.models.any { it.id == selected }
            } ?: modelCatalog.defaultModelId
            val selectedReasoningEffort = modelCatalog.models
                .firstOrNull { it.id == selectedModelId }
                ?.effectiveReasoningEffort(current.selectedReasoningEffort)
                ?: modelCatalog.defaultReasoningEffort
            current.copy(
                connection = "connected",
                models = modelCatalog.models,
                selectedModelId = selectedModelId,
                selectedReasoningEffort = selectedReasoningEffort,
                selectedPermissionProfile = if (current.selectedThreadId == null) {
                    effectivePermissionConfig.defaultProfile
                } else {
                    current.selectedPermissionProfile
                },
                defaultPermissionProfile = effectivePermissionConfig.defaultProfile,
                codexAccount = account.first,
                requiresOpenaiAuth = account.second,
                threads = threads,
                lastError = null,
                diagnostics = diagnostics.appendDiagnostic(
                    "app-server.initialize",
                    "Connected to ${session.deploymentUrl} · Codex Web Android ${BuildConfig.VERSION_NAME}",
                ),
            )
        }

        val targetThreadId = preferredThreadId
            ?: if (openMostRecentThread) threads.firstOrNull()?.id else null
        if (targetThreadId != null) {
            runCatching { openThread(targetThreadId) }
                .onFailure { error ->
                    _state.update {
                        it.withError("Could not open thread: ${error.message}")
                    }
                }
        }
    }

    fun markDisconnected(reason: String) {
        resumedThreads.clear()
        _state.update {
            it.copy(
                connection = "disconnected",
                lastError = reason,
                diagnostics = it.appendDiagnostic("web-bridge.reconnect", reason),
            )
        }
    }

    fun close() {
        resumedThreads.clear()
        rpc.close()
        _state.update { it.copy(connection = "disconnected") }
    }

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    fun selectModel(modelId: String) {
        val current = _state.value
        val model = current.models.firstOrNull { it.id == modelId } ?: return
        _state.update {
            it.copy(
                selectedModelId = modelId,
                selectedReasoningEffort = model.effectiveReasoningEffort(current.selectedReasoningEffort),
            )
        }
    }

    fun selectReasoningEffort(effort: String) {
        val current = _state.value
        val model = current.models.firstOrNull { it.id == current.selectedModelId } ?: return
        if (effort !in model.reasoningEfforts) return
        _state.update { it.copy(selectedReasoningEffort = effort) }
    }

    fun selectPermissionProfile(profile: PermissionProfile) {
        _state.update { it.copy(selectedPermissionProfile = profile) }
    }

    fun updateWorkspaceCwd(path: String) {
        _state.update { it.copy(workspaceCwd = path.trim()) }
    }

    fun startNewChat() {
        resumedThreads.clear()
        _state.update {
            it.copy(
                selectedThreadId = null,
                selectedPermissionProfile = it.defaultPermissionProfile,
                lastError = null,
            )
        }
    }

    suspend fun refreshThreads() {
        val response = rpc.request("thread/list", threadListParams())
        val threads = parseThreads(response)
        _state.update { current ->
            current.copy(threads = threads)
        }
    }

    suspend fun readDirectories(path: String): List<ServerDirectory> {
        val normalizedPath = normalizeProjectPath(path).ifBlank { "/" }
        val response = rpc.request("fs/readDirectory", buildJsonObject {
            put("path", normalizedPath)
        })
        return parseServerDirectories(normalizedPath, response)
    }

    suspend fun openThread(threadId: String) {
        _state.update { it.copy(selectedThreadId = threadId, lastError = null) }
        try {
            val resumeResponse = rpc.request("thread/resume", buildJsonObject {
                put("threadId", threadId)
                put("excludeTurns", true)
            })
            resumedThreads += threadId
            val runtimeSettings = resumeResponse.asObjectOrNull()
            reconcileModelWithThread(runtimeSettings)
            reconcilePermissionWithThread(runtimeSettings)
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    diagnostics = it.appendDiagnostic(
                        "app-server.thread/resume",
                        error.message ?: "thread/resume failed",
                    ),
                )
            }
        }

        val response = rpc.request("thread/read", buildJsonObject { put("threadId", threadId) })
        val threadJson = response.asObjectOrNull()?.obj("thread")
            ?: throw RpcException("thread/read response did not include a thread")
        val thread = ThreadInfo.fromJson(threadJson)
        reconcileModelWithThread(buildJsonObject {
            put("model", thread.model.orEmpty())
            put("modelProvider", thread.modelProvider.orEmpty())
        })
        val page = readTurnsPage(threadId, cursor = null)
        val turns = page.turns.asReversed()
        _state.update {
            it.hydrateThread(thread.copy(turns = turns)).copy(
                earlierTurnsCursorByThread = if (page.nextCursor == null) {
                    it.earlierTurnsCursorByThread - threadId
                } else {
                    it.earlierTurnsCursorByThread + (threadId to page.nextCursor)
                },
            )
        }
    }

    suspend fun loadEarlierTurns(threadId: String) {
        val cursor = _state.value.earlierTurnsCursorByThread[threadId] ?: return
        val page = readTurnsPage(threadId, cursor = cursor)
        val earlier = page.turns.asReversed()
        _state.update { current ->
            val existing = current.turnsByThread[threadId].orEmpty()
            val seen = existing.mapTo(mutableSetOf()) { it.id }
            val merged = earlier.filter { seen.add(it.id) } + existing
            current.copy(
                turnsByThread = current.turnsByThread + (threadId to merged),
                earlierTurnsCursorByThread = if (page.nextCursor == null) {
                    current.earlierTurnsCursorByThread - threadId
                } else {
                    current.earlierTurnsCursorByThread + (threadId to page.nextCursor)
                },
            )
        }
    }

    suspend fun sendMessage(text: String, cwd: String, model: String, effort: String?) {
        val message = text.trim()
        if (message.isEmpty()) return
        sendMutex.withLock {
            val current = _state.value
            val permissionProfile = current.selectedPermissionProfile
            val threadId = current.selectedThreadId
                ?: startThread(cwd, model, effort, permissionProfile)
            val normalizedCwd = cwd.trim().ifBlank { _state.value.homeDirectory }

            if (threadId !in resumedThreads) {
                runCatching {
                    rpc.request("thread/resume", buildJsonObject { put("threadId", threadId) })
                    resumedThreads += threadId
                }
            }

            val params = buildJsonObject {
                put("threadId", threadId)
                put("clientUserMessageId", UUID.randomUUID().toString())
                putJsonArray("input") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", message)
                        put("text_elements", buildJsonArray { })
                    })
                }
                put("cwd", normalizedCwd)
                if (model.isNotBlank()) put("model", model)
                if (!effort.isNullOrBlank()) put("effort", effort)
                putTurnPermissionOptions(permissionProfile, normalizedCwd, effectivePermissionConfig)
            }
            val response = rpc.request("turn/start", params)
            val turn = response.asObjectOrNull()?.obj("turn")
            if (turn != null) {
                _state.update {
                    it.applyNotification(
                        RpcNotification(
                            "turn/started",
                            buildJsonObject {
                                put("threadId", threadId)
                                put("turn", turn)
                            },
                        ),
                    )
                }
            }
        }
    }

    suspend fun updateThreadPermissions(
        threadId: String,
        profile: PermissionProfile,
    ) {
        rpc.request("thread/settings/update", buildJsonObject {
            put("threadId", threadId)
            putThreadPermissionUpdateOptions(profile, effectivePermissionConfig)
        })
    }

    suspend fun interrupt(threadId: String, turnId: String) {
        rpc.request("turn/interrupt", buildJsonObject {
            put("threadId", threadId)
            put("turnId", turnId)
        })
    }

    suspend fun respondToApproval(request: PendingRequest, decision: ApprovalDecision) {
        val result = buildApprovalResponse(request, decision)
        if (result == null) {
            rpc.respondError(request.id, "Unsupported approval request: ${request.method}")
            _state.update { it.removePendingRequest(request.id) }
            return
        }
        rpc.respond(request.id, result)
        _state.update { it.removePendingRequest(request.id) }
    }

    suspend fun answerUserInput(request: PendingRequest, answers: Map<String, List<String>>) {
        rpc.respond(request.id, buildUserInputResponse(answers))
        _state.update { it.removePendingRequest(request.id) }
    }

    suspend fun cancelMcpRequest(request: PendingRequest) {
        rpc.respond(request.id, buildMcpCancelResponse())
        _state.update { it.removePendingRequest(request.id) }
    }

    suspend fun rejectUnsupportedRequest(request: PendingRequest) {
        rpc.respondError(request.id, "Unsupported by Codex Web Android: ${request.method}")
        _state.update {
            it.removePendingRequest(request.id)
                .copy(diagnostics = it.appendDiagnostic("app-server.serverRequest", "Rejected ${request.method}"))
        }
    }

    private suspend fun readEffectiveConfig(): EffectiveConfigRead {
        val parameterVariants = listOf(
            buildJsonObject {
                put("includeLayers", false)
                put("cwd", JsonNull)
            },
            buildJsonObject { put("includeLayers", false) },
        )
        var lastError: Exception? = null
        for (params in parameterVariants) {
            try {
                return EffectiveConfigRead(rpc.request("config/read", params), null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }
        return EffectiveConfigRead(
            response = null,
            error = lastError?.message ?: "config/read failed",
        )
    }

    private suspend fun readTurnsPage(threadId: String, cursor: String?): TurnPage {
        val params = buildJsonObject {
            put("threadId", threadId)
            put("limit", 50)
            put("sortDirection", "desc")
            put("itemsView", "full")
            if (cursor != null) put("cursor", cursor)
        }
        return parseTurnPage(rpc.request("thread/turns/list", params))
    }

    private fun reconcileModelWithThread(response: JsonObject?) {
        val model = response?.string("model")?.trim().orEmpty()
        val provider = response?.string("modelProvider")?.trim().orEmpty()
        val threadEffort = response?.string("reasoningEffort")?.trim().orEmpty()
        if (model.isBlank() && threadEffort.isBlank()) return
        _state.update { current ->
            val matching = current.models.firstOrNull {
                it.id == model || it.upstreamModelId == model
            }
            val nextModels = if (
                matching == null && model.isNotBlank() && provider.isNotBlank() &&
                !provider.equals("openai", ignoreCase = true)
            ) {
                listOf(configuredModelOption(ServerModelConfig(model, provider)))
            } else {
                current.models
            }
            val nextModelId = when {
                matching != null -> matching.id
                nextModels.size == 1 && model.isNotBlank() -> model
                else -> current.selectedModelId
            }
            val selectedModel = nextModels.firstOrNull { it.id == nextModelId }
            val nextEffort = selectedModel?.effectiveReasoningEffort(
                threadEffort.ifBlank { current.selectedReasoningEffort },
            )
            current.copy(
                models = nextModels,
                selectedModelId = nextModelId,
                selectedReasoningEffort = nextEffort ?: threadEffort.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun reconcilePermissionWithThread(response: JsonObject?) {
        if (response == null) return
        val profile = permissionProfileFromRuntimeSettings(response)
        _state.update { it.copy(selectedPermissionProfile = profile) }
    }

    private suspend fun startThread(
        cwd: String,
        model: String,
        effort: String?,
        permissionProfile: PermissionProfile,
    ): String {
        val params = buildJsonObject {
            val normalizedCwd = cwd.trim().ifBlank { _state.value.homeDirectory }
            if (normalizedCwd.isNotBlank()) put("cwd", normalizedCwd)
            if (model.isNotBlank()) put("model", model)
            if (!effort.isNullOrBlank()) {
                putJsonObject("config") { put("model_reasoning_effort", effort) }
            }
            putThreadPermissionOptions(permissionProfile)
            put("serviceName", "codex_web_android")
            put("threadSource", "codex_web_android")
        }
        val response = rpc.request("thread/start", params)
        reconcilePermissionWithThread(response.asObjectOrNull())
        val threadJson = response.asObjectOrNull()?.obj("thread")
            ?: throw RpcException("thread/start response did not include a thread")
        val thread = ThreadInfo.fromJson(threadJson)
        _state.update {
            it.withThread(thread).copy(selectedThreadId = thread.id, workspaceCwd = thread.cwd)
        }
        resumedThreads += thread.id
        return thread.id
    }

    private fun parseThreads(response: JsonElement): List<ThreadInfo> =
        response.asObjectOrNull()?.array("data")
            ?.mapNotNull { (it as? JsonObject)?.let { json -> ThreadInfo.fromJson(json) } }
            .orEmpty()
            .sortedByDescending { it.updatedAt }

    private fun readAccount(response: JsonElement): Pair<String, Boolean> {
        val obj = response.asObjectOrNull() ?: return "" to false
        val account = obj.obj("account")
        val summary = when (account?.string("type")) {
            "chatgpt" -> {
                val email = account.string("email").orEmpty()
                val plan = account.string("planType").orEmpty()
                listOf(email, plan).filter { it.isNotBlank() }.joinToString(" · ")
            }
            "apiKey" -> "OpenAI API key"
            "amazonBedrock" -> "Amazon Bedrock"
            null -> "Not signed in to Codex"
            else -> account.string("type").orEmpty()
        }
        return summary to (obj.boolean("requiresOpenaiAuth") == true)
    }

    private fun threadListParams(): JsonObject = buildJsonObject {
        put("limit", 50)
        put("sortKey", "recency_at")
        put("sortDirection", "desc")
        put("archived", false)
    }
}

private data class EffectiveConfigRead(
    val response: JsonElement?,
    val error: String?,
)

internal fun redactUrl(value: String): String {
    val index = value.indexOf('?')
    if (index < 0) return value
    val query = value.substring(index + 1)
    val safe = query.split('&').filterNot { part ->
        part.substringBefore('=').lowercase() in setOf("token", "access_token", "key", "password")
    }.joinToString("&")
    return if (safe.isBlank()) value.substring(0, index) else "${value.substring(0, index)}?$safe"
}
