package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


data class ModelOption(
    val id: String,
    val displayName: String,
    val description: String,
    val isDefault: Boolean,
    val reasoningEfforts: List<String>,
    val defaultReasoningEffort: String?,
) {
    companion object {
        fun fromJson(json: JsonObject): ModelOption = ModelOption(
            id = json.string("id") ?: json.string("model").orEmpty(),
            displayName = json.string("displayName") ?: json.string("model").orEmpty(),
            description = json.string("description").orEmpty(),
            isDefault = json.boolean("isDefault") == true,
            reasoningEfforts = json.array("supportedReasoningEfforts")
                ?.mapNotNull { option ->
                    (option as? JsonObject)?.string("reasoningEffort")
                        ?: option.asStringOrNull()
                }
                .orEmpty(),
            defaultReasoningEffort = json.string("defaultReasoningEffort"),
        )
    }
}

data class UsageInfo(
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val contextWindow: Long? = null,
)

data class GoalInfo(
    val objective: String,
    val status: String,
    val tokensUsed: Long,
    val tokenBudget: Long?,
    val timeUsedSeconds: Long,
)

data class PlanInfo(
    val explanation: String?,
    val steps: List<PlanStep>,
)

data class PlanStep(
    val text: String,
    val status: String,
)

data class ChatItem(
    val id: String,
    val kind: String,
    val text: String = "",
    val title: String = "",
    val detail: String = "",
    val status: String = "",
    val isStreaming: Boolean = false,
)

data class TurnInfo(
    val id: String,
    val status: String,
    val error: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val items: List<ChatItem> = emptyList(),
) {
    companion object {
        fun fromJson(json: JsonObject): TurnInfo {
            val error = json.obj("error")?.string("message")
                ?: json.obj("error")?.string("additionalDetails")
            return TurnInfo(
                id = json.string("id").orEmpty(),
                status = json.string("status") ?: "inProgress",
                error = error,
                startedAt = json.long("startedAt"),
                completedAt = json.long("completedAt"),
                items = (json.array("items") ?: JsonArray(emptyList()))
                    .mapNotNull { (it as? JsonObject)?.let(::parseChatItem) },
            )
        }
    }
}

data class ThreadInfo(
    val id: String,
    val preview: String,
    val name: String?,
    val cwd: String,
    val updatedAt: Long,
    val status: String,
    val activeFlags: List<String> = emptyList(),
    val turns: List<TurnInfo> = emptyList(),
) {
    val title: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: preview.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: "Untitled thread"

    val isActive: Boolean
        get() = status == "active"

    companion object {
        fun fromJson(json: JsonObject, includeTurns: Boolean = false): ThreadInfo {
            val statusObject = json.obj("status")
            val turns = if (includeTurns) {
                json.array("turns")?.mapNotNull { (it as? JsonObject)?.let(TurnInfo::fromJson) }.orEmpty()
            } else {
                emptyList()
            }
            return ThreadInfo(
                id = json.string("id").orEmpty(),
                preview = json.string("preview").orEmpty(),
                name = json.string("name"),
                cwd = json.string("cwd").orEmpty(),
                updatedAt = json.long("updatedAt") ?: json.long("recencyAt") ?: 0,
                status = statusObject?.string("type") ?: "idle",
                activeFlags = statusObject?.stringList("activeFlags").orEmpty(),
                turns = turns,
            )
        }
    }
}

data class PendingRequest(
    val id: JsonElement,
    val method: String,
    val threadId: String?,
    val turnId: String?,
    val itemId: String?,
    val title: String,
    val body: String,
    val params: JsonObject,
    val kind: PendingRequestKind,
)

enum class PendingRequestKind {
    COMMAND_APPROVAL,
    FILE_APPROVAL,
    PERMISSIONS_APPROVAL,
    USER_INPUT,
    MCP_ELICITATION,
    UNKNOWN,
}

data class Diagnostic(
    val method: String,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class AppServerSnapshot(
    val connection: String = "disconnected",
    val deploymentUrl: String = "",
    val bridgeUrl: String = "",
    val userEmail: String = "",
    val codexAccount: String = "",
    val requiresOpenaiAuth: Boolean = false,
    val homeDirectory: String = "",
    val workspaceCwd: String = "",
    val models: List<ModelOption> = emptyList(),
    val selectedModelId: String = "",
    val threads: List<ThreadInfo> = emptyList(),
    val selectedThreadId: String? = null,
    val turnsByThread: Map<String, List<TurnInfo>> = emptyMap(),
    val activeTurnByThread: Map<String, String> = emptyMap(),
    val threadStatusByThread: Map<String, String> = emptyMap(),
    val planByThread: Map<String, PlanInfo> = emptyMap(),
    val usageByThread: Map<String, UsageInfo> = emptyMap(),
    val goalByThread: Map<String, GoalInfo> = emptyMap(),
    val pendingRequests: List<PendingRequest> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val lastError: String? = null,
) {
    val selectedThread: ThreadInfo?
        get() = threads.firstOrNull { it.id == selectedThreadId }

    val selectedTurns: List<TurnInfo>
        get() = selectedThreadId?.let { turnsByThread[it] }.orEmpty()

    val selectedActiveTurnId: String?
        get() = selectedThreadId?.let { activeTurnByThread[it] }

    val selectedUsage: UsageInfo?
        get() = selectedThreadId?.let { usageByThread[it] }

    val selectedGoal: GoalInfo?
        get() = selectedThreadId?.let { goalByThread[it] }

    val selectedPlan: PlanInfo?
        get() = selectedThreadId?.let { planByThread[it] }
}

internal fun parseChatItem(json: JsonObject): ChatItem {
    val id = json.string("id").orEmpty()
    val type = json.string("type") ?: "unknown"

    fun item(
        text: String = "",
        title: String = "",
        detail: String = "",
        status: String = "",
        kind: String = type,
        streaming: Boolean = status == "inProgress",
    ) = ChatItem(id, kind, text, title, detail, status, streaming)

    return when (type) {
        "userMessage" -> {
            val text = json.array("content")?.mapNotNull { content ->
                val obj = content as? JsonObject ?: return@mapNotNull null
                when (obj.string("type")) {
                    "text" -> obj.string("text")
                    "image", "localImage" -> "[image]"
                    "skill" -> "[skill: ${obj.string("name").orEmpty()}]"
                    "mention" -> "[file: ${obj.string("name").orEmpty()}]"
                    else -> null
                }
            }.orEmpty().joinToString("\n")
            item(text = text)
        }
        "agentMessage" -> item(text = json.string("text").orEmpty())
        "reasoning" -> {
            val summary = json.stringList("summary").joinToString("\n")
            val content = json.stringList("content").joinToString("\n")
            item(text = listOf(summary, content).filter { it.isNotBlank() }.joinToString("\n\n"))
        }
        "plan" -> item(text = json.string("text").orEmpty())
        "commandExecution" -> {
            val status = json.string("status").orEmpty()
            val output = json.string("aggregatedOutput").orEmpty()
            val exit = json.long("exitCode")?.let { "\nexit code: $it" }.orEmpty()
            item(
                title = json.string("command").orEmpty(),
                detail = output + exit,
                status = status,
            )
        }
        "fileChange" -> {
            val status = json.string("status").orEmpty()
            val changes = json.array("changes")
                ?.mapNotNull { it as? JsonObject }
                ?.joinToString("\n") { change ->
                    val path = change.string("path").orEmpty()
                    val kind = change.string("kind").orEmpty()
                    val diff = change.string("diff").orEmpty()
                    listOf(path, kind.takeIf { it.isNotBlank() }?.let { "($it)" }, diff)
                        .filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
                }
                .orEmpty()
            item(title = "File changes", detail = changes, status = status)
        }
        "mcpToolCall" -> {
            val status = json.string("status").orEmpty()
            val server = json.string("server").orEmpty()
            val tool = json.string("tool").orEmpty()
            val arguments = json["arguments"]?.toString().orEmpty()
            val result = json["result"]?.toString().orEmpty()
            val error = json.obj("error")?.string("message").orEmpty()
            item(
                title = "$server / $tool",
                detail = listOf(arguments, result, error).filter { it.isNotBlank() }.joinToString("\n"),
                status = status,
            )
        }
        "dynamicToolCall" -> {
            val namespace = json.string("namespace").orEmpty()
            val tool = json.string("tool").orEmpty()
            val status = json.string("status").orEmpty()
            item(
                title = listOf(namespace, tool).filter { it.isNotBlank() }.joinToString(" / "),
                detail = json["arguments"]?.toString().orEmpty(),
                status = status,
            )
        }
        "collabAgentToolCall" -> {
            val status = json.string("status").orEmpty()
            val tool = json.string("tool").orEmpty()
            val prompt = json.string("prompt").orEmpty()
            item(title = "Agent: $tool", detail = prompt, status = status)
        }
        "subAgentActivity" -> {
            val kind = json.string("kind").orEmpty()
            item(title = "Sub-agent", detail = kind)
        }
        "webSearch" -> item(title = "Web search", text = json.string("query").orEmpty())
        "imageView" -> item(title = "Image", text = json.string("path").orEmpty())
        "sleep" -> item(
            title = "Waiting",
            text = json.long("durationMs")?.let { "${it / 1000}s" }.orEmpty(),
        )
        "imageGeneration" -> item(
            title = "Image generation",
            text = json.string("revisedPrompt").orEmpty(),
            status = json.string("status").orEmpty(),
        )
        "enteredReviewMode" -> item(title = "Review", text = json.string("review").orEmpty())
        "exitedReviewMode" -> item(title = "Review complete", text = json.string("review").orEmpty())
        "contextCompaction" -> item(title = "Context compacted")
        "hookPrompt" -> item(title = "Hook", text = json.array("fragments")?.toString().orEmpty())
        else -> item(
            title = type,
            detail = json.toString(),
            kind = "unknown",
        )
    }
}

internal fun parseUsage(json: JsonObject): UsageInfo {
    fun tokens(name: String): Long = json.obj(name)?.long("totalTokens") ?: 0
    fun input(name: String): Long = json.obj(name)?.long("inputTokens") ?: 0
    fun output(name: String): Long = json.obj(name)?.long("outputTokens") ?: 0
    return UsageInfo(
        totalTokens = tokens("total"),
        inputTokens = input("total"),
        outputTokens = output("total"),
        contextWindow = json.long("modelContextWindow"),
    )
}

internal fun parseGoal(json: JsonObject): GoalInfo = GoalInfo(
    objective = json.string("objective").orEmpty(),
    status = json.string("status").orEmpty(),
    tokensUsed = json.long("tokensUsed") ?: 0,
    tokenBudget = json.long("tokenBudget"),
    timeUsedSeconds = json.long("timeUsedSeconds") ?: 0,
)

internal fun parsePlan(params: JsonObject): PlanInfo = PlanInfo(
    explanation = params.string("explanation"),
    steps = params.array("plan")?.mapNotNull { step ->
        (step as? JsonObject)?.let {
            PlanStep(
                text = it.string("step").orEmpty(),
                status = it.string("status").orEmpty(),
            )
        }
    }.orEmpty(),
)

internal fun parsePendingRequest(
    id: JsonElement,
    method: String,
    params: JsonObject,
): PendingRequest {
    val threadId = params.string("threadId")
    val turnId = params.string("turnId")
    val itemId = params.string("itemId")
    return when (method) {
        "item/commandExecution/requestApproval" -> PendingRequest(
            id = id,
            method = method,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            title = "Command approval",
            body = buildString {
                params.string("reason")?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                params.string("command")?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                params.string("cwd")?.takeIf { it.isNotBlank() }?.let { appendLine("in $it") }
            }.trim(),
            params = params,
            kind = PendingRequestKind.COMMAND_APPROVAL,
        )
        "item/fileChange/requestApproval" -> PendingRequest(
            id = id,
            method = method,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            title = "File change approval",
            body = params.string("reason")
                ?: params.string("grantRoot")?.let { "Allow writes under $it" }
                ?: "Codex wants to modify workspace files.",
            params = params,
            kind = PendingRequestKind.FILE_APPROVAL,
        )
        "item/permissions/requestApproval" -> PendingRequest(
            id = id,
            method = method,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            title = "Permission approval",
            body = buildString {
                params.string("reason")?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                params.string("cwd")?.takeIf { it.isNotBlank() }?.let { appendLine("cwd: $it") }
                params.obj("permissions")?.let { appendLine(it.toString()) }
            }.trim(),
            params = params,
            kind = PendingRequestKind.PERMISSIONS_APPROVAL,
        )
        "item/tool/requestUserInput" -> PendingRequest(
            id = id,
            method = method,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            title = "Codex needs input",
            body = params.array("questions")?.joinToString("\n\n") { question ->
                val q = question as? JsonObject ?: return@joinToString ""
                val options = q.array("options")?.mapNotNull { option ->
                    (option as? JsonObject)?.string("label")
                }.orEmpty()
                buildString {
                    append(q.string("header").orEmpty())
                    append(": ")
                    append(q.string("question").orEmpty())
                    if (options.isNotEmpty()) append("\nOptions: ${options.joinToString()}")
                }
            }.orEmpty(),
            params = params,
            kind = PendingRequestKind.USER_INPUT,
        )
        "mcpServer/elicitation/request" -> PendingRequest(
            id = id,
            method = method,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            title = "MCP request",
            body = params.string("message") ?: "An MCP server is requesting input.",
            params = params,
            kind = PendingRequestKind.MCP_ELICITATION,
        )
        else -> PendingRequest(
            id = id,
            method = method,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            title = "Unsupported app-server request",
            body = method,
            params = params,
            kind = PendingRequestKind.UNKNOWN,
        )
    }
}

