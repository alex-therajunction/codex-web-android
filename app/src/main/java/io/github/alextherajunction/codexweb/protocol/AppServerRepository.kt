package io.github.alextherajunction.codexweb.protocol

import io.github.alextherajunction.codexweb.data.DeploymentSession
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
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
                        diagnostics = it.appendDiagnostic("web-bridge.close", reason),
                    )
                }
            }
        }
    }

    suspend fun connect(session: DeploymentSession) {
        this.session = session
        _state.value = AppServerSnapshot(
            connection = "connecting",
            deploymentUrl = session.deploymentUrl,
            bridgeUrl = redactUrl(session.bridgeUrl),
            userEmail = session.user.email,
            homeDirectory = session.homeDirectory,
            workspaceCwd = session.user.cwd.ifBlank { session.homeDirectory },
        )
        rpc.connect(
            endpoint = session.bridgeUrl,
            cookieHeader = session.cookieHeader,
            origin = session.origin,
        )

        val modelsResponse = rpc.request("model/list", buildJsonObject {
            put("includeHidden", false)
        })
        val configResponse = runCatching {
            rpc.request("config/read", buildJsonObject {
                put("includeLayers", false)
            })
        }.getOrNull()
        val modelCatalog = resolveServerModelCatalog(modelsResponse, configResponse)

        val account = readAccount(rpc.request("account/read", buildJsonObject {
            put("refreshToken", false)
        }))

        val threadsResponse = rpc.request("thread/list", threadListParams())
        val threads = parseThreads(threadsResponse)

        _state.update {
            it.copy(
                connection = "connected",
                models = modelCatalog.models,
                selectedModelId = modelCatalog.defaultModelId,
                codexAccount = account.first,
                requiresOpenaiAuth = account.second,
                threads = threads,
                diagnostics = it.appendDiagnostic(
                    "app-server.initialize",
                    "Connected to ${session.deploymentUrl}",
                ),
            )
        }

        val first = threads.firstOrNull()
        if (first != null) {
            runCatching { openThread(first.id) }
                .onFailure { error ->
                    _state.update {
                        it.withError("Could not open recent thread: ${error.message}")
                    }
                }
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
        if (_state.value.models.none { it.id == modelId }) return
        _state.update { it.copy(selectedModelId = modelId) }
    }

    fun updateWorkspaceCwd(path: String) {
        _state.update { it.copy(workspaceCwd = path.trim()) }
    }

    fun startNewChat() {
        resumedThreads.clear()
        _state.update {
            it.copy(selectedThreadId = null, lastError = null)
        }
    }

    suspend fun refreshThreads() {
        val response = rpc.request("thread/list", threadListParams())
        val threads = parseThreads(response)
        _state.update { current ->
            current.copy(threads = threads)
        }
    }

    suspend fun openThread(threadId: String) {
        _state.update { it.copy(selectedThreadId = threadId, lastError = null) }
        try {
            rpc.request("thread/resume", buildJsonObject {
                put("threadId", threadId)
            })
            resumedThreads += threadId
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

        val response = rpc.request("thread/read", buildJsonObject {
            put("threadId", threadId)
            put("includeTurns", true)
        })
        val threadJson = response.asObjectOrNull()?.obj("thread")
            ?: throw RpcException("thread/read response did not include a thread")
        val thread = ThreadInfo.fromJson(threadJson, includeTurns = true)
        _state.update { it.hydrateThread(thread) }
    }

    suspend fun sendMessage(text: String, cwd: String, model: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        sendMutex.withLock {
            val current = _state.value
            val threadId = current.selectedThreadId ?: startThread(cwd, model)
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

    private suspend fun startThread(cwd: String, model: String): String {
        val params = buildJsonObject {
            val normalizedCwd = cwd.trim().ifBlank { _state.value.homeDirectory }
            if (normalizedCwd.isNotBlank()) put("cwd", normalizedCwd)
            if (model.isNotBlank()) put("model", model)
            put("serviceName", "codex_web_android")
            put("threadSource", "codex_web_android")
        }
        val response = rpc.request("thread/start", params)
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

internal fun redactUrl(value: String): String {
    val index = value.indexOf('?')
    if (index < 0) return value
    val query = value.substring(index + 1)
    val safe = query.split('&').filterNot { part ->
        part.substringBefore('=').lowercase() in setOf("token", "access_token", "key", "password")
    }.joinToString("&")
    return if (safe.isBlank()) value.substring(0, index) else "${value.substring(0, index)}?$safe"
}
