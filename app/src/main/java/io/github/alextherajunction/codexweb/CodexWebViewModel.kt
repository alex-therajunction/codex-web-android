package io.github.alextherajunction.codexweb

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.webkit.CookieManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.alextherajunction.codexweb.data.AppPreferences
import io.github.alextherajunction.codexweb.data.DeploymentSession
import io.github.alextherajunction.codexweb.data.ScreenShareCapability
import io.github.alextherajunction.codexweb.data.ScreenShareFrameMessage
import io.github.alextherajunction.codexweb.data.ScreenShareFrameState
import io.github.alextherajunction.codexweb.data.SessionExpiredException
import io.github.alextherajunction.codexweb.data.WebSessionGateway
import io.github.alextherajunction.codexweb.data.buildScreenShareWebSocketUrl
import io.github.alextherajunction.codexweb.data.hasWebAuthSessionCookie
import io.github.alextherajunction.codexweb.data.normalizeDeploymentUrl
import io.github.alextherajunction.codexweb.data.parseScreenShareFrameMessage
import io.github.alextherajunction.codexweb.protocol.AppServerRepository
import io.github.alextherajunction.codexweb.protocol.AppServerSnapshot
import io.github.alextherajunction.codexweb.protocol.ApprovalDecision
import io.github.alextherajunction.codexweb.protocol.McpServerSummary
import io.github.alextherajunction.codexweb.protocol.MemorySettings
import io.github.alextherajunction.codexweb.protocol.PendingRequest
import io.github.alextherajunction.codexweb.protocol.PermissionProfile
import io.github.alextherajunction.codexweb.protocol.RateLimitSummary
import io.github.alextherajunction.codexweb.protocol.RpcException
import io.github.alextherajunction.codexweb.protocol.ServerDirectory
import io.github.alextherajunction.codexweb.protocol.TerminalProcessEvent
import io.github.alextherajunction.codexweb.protocol.TerminalSize
import io.github.alextherajunction.codexweb.protocol.normalizeProjectPath
import io.github.alextherajunction.codexweb.push.PushDistributor
import io.github.alextherajunction.codexweb.push.PushDistributors
import io.github.alextherajunction.codexweb.push.PushNotifier
import io.github.alextherajunction.codexweb.push.PushRegistrationClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

enum class AppPhase {
    STARTUP,
    SIGNED_OUT,
    AUTHENTICATING,
    CONNECTING,
    READY,
}

data class AppUiState(
    val phase: AppPhase = AppPhase.STARTUP,
    val urlInput: String = "",
    val authUrl: String = "",
    val error: String? = null,
    val composerText: String = "",
    val composerDraftKey: String = NEW_CHAT_COMPOSER_DRAFT_KEY,
    val composerDrafts: Map<String, String> = emptyMap(),
    val hiddenTurnIdsByThread: Map<String, Set<String>> = emptyMap(),
    val sending: Boolean = false,
    val openingThreadId: String? = null,
    val loadingEarlierTurns: Boolean = false,
    val reconnecting: Boolean = false,
    val reconnectAttempt: Int = 0,
    val cwdInput: String = "",
    val projectBrowserPath: String = "",
    val projectBrowserDirectories: List<ServerDirectory> = emptyList(),
    val projectBrowserLoading: Boolean = false,
    val projectBrowserError: String? = null,
    val goalEditorOpen: Boolean = false,
    val goalPromptMode: Boolean = false,
    val planMode: Boolean = false,
    val activeComposerPanel: ComposerPanel? = null,
    val composerPanelLoading: Boolean = false,
    val mcpServers: List<McpServerSummary> = emptyList(),
    val rateLimits: RateLimitSummary? = null,
    val memorySettings: MemorySettings? = null,
    val pushEnabled: Boolean = false,
    val pushDistributors: List<PushDistributor> = emptyList(),
    val pushSelectedDistributor: String? = null,
    val pushStatus: String? = null,
    val pushError: String? = null,
    val terminal: TerminalUiState? = null,
    val screenShare: ScreenShareUiState? = null,
    val snapshot: AppServerSnapshot = AppServerSnapshot(),
)

enum class TerminalStatus {
    STARTING,
    RUNNING,
    EXITED,
    ERROR,
}

data class TerminalUiState(
    val processHandle: String,
    val cwd: String,
    val emulator: TerminalEmulator,
    val status: TerminalStatus,
    val exitCode: Int? = null,
    val error: String? = null,
)

enum class ScreenShareStatus {
    LOADING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ALREADY_ACTIVE,
    TAKEN_OVER,
    DISABLED,
    UNSUPPORTED,
    ERROR,
}

data class ScreenShareUiState(
    val deploymentUrl: String,
    val origin: String,
    val status: ScreenShareStatus = ScreenShareStatus.LOADING,
    val display: String? = null,
    val wsPath: String? = null,
    val webSocketUrl: String? = null,
    val takeoverWebSocketUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val reason: String? = null,
    val connectionRequestId: Long = 0,
    val useTakeover: Boolean = false,
) {
    val requestedWebSocketUrl: String?
        get() = if (useTakeover) takeoverWebSocketUrl else webSocketUrl
}

internal fun ScreenShareUiState.reduceFrameMessage(
    message: ScreenShareFrameMessage,
): ScreenShareUiState = when (message) {
    ScreenShareFrameMessage.Ready -> copy(status = ScreenShareStatus.CONNECTING)
    is ScreenShareFrameMessage.Error -> copy(
        status = ScreenShareStatus.ERROR,
        reason = message.message,
    )
    is ScreenShareFrameMessage.Status -> when (message.state) {
        ScreenShareFrameState.CONNECTING -> copy(
            status = ScreenShareStatus.CONNECTING,
            reason = null,
        )
        ScreenShareFrameState.CONNECTED -> copy(
            status = ScreenShareStatus.CONNECTED,
            width = message.width,
            height = message.height,
            reason = null,
        )
        ScreenShareFrameState.DISCONNECTED -> copy(
            status = when (message.code) {
                4001 -> ScreenShareStatus.TAKEN_OVER
                4002 -> ScreenShareStatus.ALREADY_ACTIVE
                else -> ScreenShareStatus.DISCONNECTED
            },
            width = null,
            height = null,
            reason = message.reason,
        )
    }
}

private const val MAX_TERMINAL_INPUT_BUFFER = 64 * 1024
private val TERMINAL_BACKGROUND = Color(0xFF10120E)
private val TERMINAL_FOREGROUND = Color(0xFFF0F3EA)
private val TERMINAL_ANSI_COLORS = intArrayOf(
    0xFF30343B.toInt(),
    0xFFE06C75.toInt(),
    0xFF98C379.toInt(),
    0xFFE5C07B.toInt(),
    0xFF61AFEF.toInt(),
    0xFFC678DD.toInt(),
    0xFF56B6C2.toInt(),
    0xFFD9DDE3.toInt(),
    0xFF6F7782.toInt(),
    0xFFFF8F82.toInt(),
    0xFFB9F47A.toInt(),
    0xFFF2C66D.toInt(),
    0xFF7DCFFF.toInt(),
    0xFFD7A6FF.toInt(),
    0xFF89DDFF.toInt(),
    0xFFFFFFFF.toInt(),
)

class CodexWebViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(65, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()
    private val gateway = WebSessionGateway(httpClient)

    private val _state = MutableStateFlow(
        AppUiState(
            urlInput = preferences.deploymentUrl.orEmpty(),
            cwdInput = preferences.lastProjectPath.orEmpty(),
            pushEnabled = preferences.notificationsEnabled,
            pushSelectedDistributor = preferences.pushDistributor,
            pushStatus = preferences.pushStatus,
            pushError = preferences.pushError,
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var repository: AppServerRepository? = null
    private var repositoryCollector: Job? = null
    private var reconnectJob: Job? = null
    private var screenShareJob: Job? = null
    private var deploymentSession: DeploymentSession? = null
    private var connecting = false
    private var checkingLoginCookie = false
    private var pendingNotificationThreadId: String? = null
    private var terminalEmulator: TerminalEmulator? = null
    private var terminalUnsubscribe: (() -> Unit)? = null
    private var terminalInputJob: Job? = null
    private var terminalOutputJob: Job? = null
    private var terminalResizeJob: Job? = null
    private var terminalInputChannel: Channel<ByteArray>? = null
    private var terminalResizeChannel: Channel<TerminalSize>? = null
    private val terminalInputBuffer = ByteArrayOutputStream()
    private val terminalOutputChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var terminalSize = TerminalSize()
    private val pushPreferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            syncPushPreferenceState()
        }

    init {
        preferences.registerChangeListener(pushPreferenceListener)
        refreshPushDistributors()
        val savedUrl = preferences.deploymentUrl?.trim().orEmpty()
        if (savedUrl.isNotBlank()) {
            val cookie = cookieFor(savedUrl)
            if (cookie != null && hasWebAuthSessionCookie(cookie)) {
                connectWithCookie(savedUrl, cookie)
            } else {
                _state.update { it.copy(phase = AppPhase.SIGNED_OUT) }
            }
        } else {
            _state.update { it.copy(phase = AppPhase.SIGNED_OUT) }
        }
    }

    fun updateUrl(value: String) {
        _state.update { it.copy(urlInput = value, error = null) }
    }

    fun updateComposer(value: String) {
        _state.update { it.withComposerDraft(value) }
    }

    fun handleNotificationIntent(intent: Intent?) {
        pendingNotificationThreadId = intent?.getStringExtra(PushNotifier.EXTRA_THREAD_ID)
            ?.takeIf { it.isNotBlank() }
        openPendingNotificationThread()
    }

    fun refreshPushDistributors() {
        val distributors = PushDistributors.available(getApplication())
        val stored = preferences.pushDistributor
        val selected = stored?.takeIf { candidate -> distributors.any { it.packageName == candidate } }
            ?: PushDistributors.current(getApplication())
                ?.takeIf { candidate -> distributors.any { it.packageName == candidate } }
            ?: distributors.firstOrNull()?.packageName
        _state.update {
            it.copy(
                pushDistributors = distributors,
                pushSelectedDistributor = selected,
            )
        }
    }

    fun selectPushDistributor(packageName: String) {
        if (_state.value.pushDistributors.none { it.packageName == packageName }) return
        preferences.pushDistributor = packageName
        _state.update { it.copy(pushSelectedDistributor = packageName, pushError = null) }
        if (preferences.notificationsEnabled) registerPushNotifications()
    }

    fun setPushNotificationsEnabled(enabled: Boolean) {
        if (!enabled) {
            val instance = PushDistributors.ensureInstance(getApplication())
            preferences.notificationsEnabled = false
            preferences.pushStatus = null
            preferences.pushError = null
            _state.update { it.copy(pushEnabled = false, pushStatus = null, pushError = null) }
            runCatching { PushDistributors.unregister(getApplication(), instance) }
            viewModelScope.launch {
                runCatching { PushRegistrationClient.unregister(getApplication(), instance) }
            }
            return
        }

        val selected = _state.value.pushSelectedDistributor
            ?: _state.value.pushDistributors.firstOrNull()?.packageName
        if (selected == null) {
            _state.update {
                it.copy(pushError = "Install a UnifiedPush distributor such as Sunup or ntfy first.")
            }
            return
        }
        preferences.notificationsEnabled = true
        preferences.pushDistributor = selected
        preferences.pushStatus = "Waiting for the UnifiedPush distributor…"
        preferences.pushError = null
        _state.update {
            it.copy(
                pushEnabled = true,
                pushSelectedDistributor = selected,
                pushStatus = preferences.pushStatus,
                pushError = null,
            )
        }
        registerPushNotifications()
    }

    fun retryPushRegistration() {
        refreshPushDistributors()
        if (!preferences.notificationsEnabled) {
            _state.update { it.copy(pushError = "Enable notifications first.") }
            return
        }
        registerPushNotifications()
    }

    fun updateCwd(value: String) {
        val normalized = normalizeProjectPath(value)
        preferences.lastProjectPath = normalized
        repository?.updateWorkspaceCwd(normalized)
        _state.update { it.copy(cwdInput = normalized) }
    }

    fun openTerminal() {
        val repo = repository ?: return
        val snapshot = _state.value.snapshot
        if (snapshot.connection != "connected") {
            _state.update { it.copy(error = "Reconnect the app-server before opening a terminal") }
            return
        }
        val cwd = normalizeProjectPath(
            snapshot.selectedThread?.cwd
                ?: _state.value.cwdInput.ifBlank { snapshot.workspaceCwd },
        )
        if (cwd.isBlank()) {
            _state.update { it.copy(error = "Set a working directory before opening a terminal") }
            return
        }
        closeTerminal()
        val emulator = ensureTerminalEmulator()
        emulator.writeInput("\u001B[0m\u001B[2J\u001B[3J\u001B[H".toByteArray())
        terminalSize = emulator.dimensions.let { TerminalSize(it.columns, it.rows) }
        terminalInputBuffer.reset()
        drainTerminalOutput()
        val processHandle = "codex-web-android-terminal-${UUID.randomUUID()}"
        _state.update {
            it.copy(
                terminal = TerminalUiState(
                    processHandle = processHandle,
                    cwd = cwd,
                    emulator = emulator,
                    status = TerminalStatus.STARTING,
                ),
                error = null,
            )
        }
        startTerminalProcess(repo, processHandle, cwd)
    }

    fun closeTerminal() {
        val terminal = _state.value.terminal ?: return
        terminalUnsubscribe?.invoke()
        terminalUnsubscribe = null
        terminalInputJob?.cancel()
        terminalInputJob = null
        terminalOutputJob?.cancel()
        terminalOutputJob = null
        terminalResizeJob?.cancel()
        terminalResizeJob = null
        terminalInputChannel?.close()
        terminalInputChannel = null
        terminalResizeChannel?.close()
        terminalResizeChannel = null
        terminalInputBuffer.reset()
        _state.update { it.copy(terminal = null) }
        repository?.let { repo ->
            viewModelScope.launch {
                runCatching { repo.killTerminal(terminal.processHandle) }
            }
        }
    }

    fun restartTerminal() {
        if (_state.value.terminal == null) return
        closeTerminal()
        openTerminal()
    }

    fun sendTerminalInput(input: ByteArray) {
        if (input.isEmpty()) return
        val terminal = _state.value.terminal ?: return
        when (terminal.status) {
            TerminalStatus.STARTING -> {
                if (terminalInputBuffer.size() + input.size <= MAX_TERMINAL_INPUT_BUFFER) {
                    terminalInputBuffer.write(input)
                }
            }
            TerminalStatus.RUNNING -> {
                terminalInputChannel?.trySend(input)
            }
            TerminalStatus.EXITED,
            TerminalStatus.ERROR,
            -> Unit
        }
    }

    fun openScreenShare() {
        if (_state.value.phase != AppPhase.READY) {
            _state.update { it.copy(error = "Connect to Codex Web before sharing the host screen") }
            return
        }
        val session = deploymentSession
        if (session == null) {
            _state.update { it.copy(error = "Codex Web session is unavailable. Reconnect and try again.") }
            return
        }
        closeTerminal()
        screenShareJob?.cancel()
        _state.update {
            it.copy(
                screenShare = ScreenShareUiState(
                    deploymentUrl = session.deploymentUrl,
                    origin = session.origin,
                ),
                error = null,
            )
        }
        loadScreenShareCapability(session)
    }

    fun retryScreenShare() {
        val current = _state.value.screenShare ?: return
        if (current.webSocketUrl == null) {
            openScreenShare()
        } else {
            reconnectScreenShare()
        }
    }

    fun reconnectScreenShare() {
        _state.update { state ->
            val screenShare = state.screenShare ?: return@update state
            state.copy(
                screenShare = screenShare.copy(
                    status = ScreenShareStatus.CONNECTING,
                    width = null,
                    height = null,
                    reason = null,
                    connectionRequestId = screenShare.connectionRequestId + 1,
                    useTakeover = false,
                ),
            )
        }
    }

    fun takeOverScreenShare() {
        _state.update { state ->
            val screenShare = state.screenShare ?: return@update state
            if (screenShare.takeoverWebSocketUrl == null) return@update state
            state.copy(
                screenShare = screenShare.copy(
                    status = ScreenShareStatus.CONNECTING,
                    width = null,
                    height = null,
                    reason = null,
                    connectionRequestId = screenShare.connectionRequestId + 1,
                    useTakeover = true,
                ),
            )
        }
    }

    fun onScreenShareFrameMessage(raw: String) {
        val message = parseScreenShareFrameMessage(raw) ?: return
        _state.update { state ->
            val screenShare = state.screenShare ?: return@update state
            state.copy(screenShare = screenShare.reduceFrameMessage(message))
        }
    }

    fun onScreenShareViewDetached() {
        _state.update { state ->
            val screenShare = state.screenShare ?: return@update state
            when (screenShare.status) {
                ScreenShareStatus.DISABLED,
                ScreenShareStatus.UNSUPPORTED,
                ScreenShareStatus.ERROR,
                ScreenShareStatus.LOADING,
                -> state
                else -> state.copy(
                    screenShare = screenShare.copy(
                        status = ScreenShareStatus.CONNECTING,
                        width = null,
                        height = null,
                        reason = null,
                    ),
                )
            }
        }
    }

    fun closeScreenShare() {
        screenShareJob?.cancel()
        screenShareJob = null
        _state.update { it.copy(screenShare = null) }
    }

    private fun loadScreenShareCapability(session: DeploymentSession) {
        screenShareJob = viewModelScope.launch {
            try {
                when (val capability = gateway.screenShareCapability(session)) {
                    ScreenShareCapability.Disabled -> updateScreenShare(session) {
                        it.copy(
                            status = ScreenShareStatus.DISABLED,
                            reason = "Screen share is disabled on the server. Set CODEX_WEB_SCREEN_SHARE=1 in the server environment to enable it.",
                        )
                    }
                    is ScreenShareCapability.Unsupported -> updateScreenShare(session) {
                        it.copy(status = ScreenShareStatus.UNSUPPORTED, reason = capability.reason)
                    }
                    is ScreenShareCapability.Available -> {
                        val webSocketUrl = buildScreenShareWebSocketUrl(
                            bridgeUrl = session.bridgeUrl,
                            wsPath = capability.wsPath,
                        )
                        val takeoverWebSocketUrl = buildScreenShareWebSocketUrl(
                            bridgeUrl = session.bridgeUrl,
                            wsPath = capability.wsPath,
                            takeover = true,
                        )
                        updateScreenShare(session) {
                            it.copy(
                                status = ScreenShareStatus.CONNECTING,
                                display = capability.display.takeIf(String::isNotBlank),
                                wsPath = capability.wsPath,
                                webSocketUrl = webSocketUrl,
                                takeoverWebSocketUrl = takeoverWebSocketUrl,
                                reason = null,
                                connectionRequestId = 1,
                                useTakeover = false,
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SessionExpiredException) {
                closeScreenShare()
                returnToLogin("Your Codex Web login expired. Sign in again.", session.deploymentUrl)
            } catch (error: Exception) {
                updateScreenShare(session) {
                    it.copy(
                        status = ScreenShareStatus.ERROR,
                        reason = friendlyMessage(error),
                    )
                }
            }
        }
    }

    private fun updateScreenShare(
        session: DeploymentSession,
        transform: (ScreenShareUiState) -> ScreenShareUiState,
    ) {
        _state.update { state ->
            val screenShare = state.screenShare
                ?.takeIf { it.deploymentUrl == session.deploymentUrl }
                ?: return@update state
            state.copy(screenShare = transform(screenShare))
        }
    }

    private fun startTerminalProcess(
        repo: AppServerRepository,
        processHandle: String,
        cwd: String,
    ) {
        val emulator = terminalEmulator ?: return
        val inputChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val resizeChannel = Channel<TerminalSize>(Channel.CONFLATED)
        terminalInputChannel = inputChannel
        terminalResizeChannel = resizeChannel
        terminalUnsubscribe = repo.subscribeTerminal(processHandle) { event ->
            if (_state.value.terminal?.processHandle != processHandle) return@subscribeTerminal
            when (event) {
                is TerminalProcessEvent.Output -> terminalOutputChannel.trySend(event.bytes)
                is TerminalProcessEvent.Exited -> {
                    terminalOutputChannel.trySend(
                        "\r\n[process exited with code ${event.exitCode}]\r\n".toByteArray(),
                    )
                    terminalInputChannel?.close()
                    terminalResizeChannel?.close()
                    terminalInputJob?.cancel()
                    terminalResizeJob?.cancel()
                    updateTerminal(processHandle) {
                        it.copy(status = TerminalStatus.EXITED, exitCode = event.exitCode)
                    }
                }
            }
        }
        terminalOutputJob = viewModelScope.launch(Dispatchers.Default) {
            for (chunk in terminalOutputChannel) {
                emulator.writeInput(chunk)
            }
        }
        viewModelScope.launch {
            try {
                repo.spawnTerminal(processHandle, cwd, terminalSize.normalized())
                val terminal = _state.value.terminal?.takeIf { it.processHandle == processHandle }
                if (terminal == null || terminal.status == TerminalStatus.ERROR) {
                    runCatching { repo.killTerminal(processHandle) }
                    return@launch
                }
                if (terminal.status != TerminalStatus.STARTING) return@launch
                _state.update { state ->
                    val terminal = state.terminal ?: return@update state
                    state.copy(terminal = terminal.copy(status = TerminalStatus.RUNNING))
                }
                terminalInputJob = viewModelScope.launch {
                    for (chunk in inputChannel) {
                        val error = runCatching {
                            repo.writeTerminalInput(processHandle, chunk)
                        }.exceptionOrNull() ?: continue
                        updateTerminal(processHandle) {
                            it.copy(
                                status = TerminalStatus.ERROR,
                                error = error.message ?: "Terminal input failed",
                            )
                        }
                        return@launch
                    }
                }
                terminalResizeJob = viewModelScope.launch {
                    for (size in resizeChannel) {
                        runCatching { repo.resizeTerminal(processHandle, size) }
                    }
                }
                val buffered = terminalInputBuffer.toByteArray()
                terminalInputBuffer.reset()
                if (buffered.isNotEmpty()) inputChannel.trySend(buffered)
                resizeChannel.trySend(terminalSize.normalized())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateTerminal(processHandle) { terminal ->
                    if (terminal.status != TerminalStatus.STARTING) {
                        terminal
                    } else {
                        terminal.copy(
                            status = TerminalStatus.ERROR,
                            error = error.message ?: "Could not start terminal",
                        )
                    }
                }
            }
        }
    }

    private fun ensureTerminalEmulator(): TerminalEmulator {
        terminalEmulator?.let { return it }
        return TerminalEmulatorFactory.create(
            looper = Looper.getMainLooper(),
            initialRows = 24,
            initialCols = 80,
            defaultForeground = TERMINAL_FOREGROUND,
            defaultBackground = TERMINAL_BACKGROUND,
            onKeyboardInput = ::sendTerminalInput,
            onResize = { dimensions ->
                val size = TerminalSize(dimensions.columns, dimensions.rows).normalized()
                terminalSize = size
                if (_state.value.terminal?.status == TerminalStatus.RUNNING) {
                    terminalResizeChannel?.trySend(size)
                }
            },
        ).also { emulator ->
            emulator.applyColorScheme(
                ansiColors = TERMINAL_ANSI_COLORS,
                defaultForeground = TERMINAL_FOREGROUND.toArgb(),
                defaultBackground = TERMINAL_BACKGROUND.toArgb(),
            )
            terminalEmulator = emulator
        }
    }

    private fun updateTerminal(
        processHandle: String,
        transform: (TerminalUiState) -> TerminalUiState,
    ) {
        _state.update { state ->
            val terminal = state.terminal?.takeIf { it.processHandle == processHandle }
                ?: return@update state
            state.copy(terminal = transform(terminal))
        }
    }

    private fun drainTerminalOutput() {
        while (terminalOutputChannel.tryReceive().isSuccess) Unit
    }

    fun selectProject(path: String) {
        val normalized = normalizeProjectPath(path)
        if (normalized.isBlank()) return
        preferences.lastProjectPath = normalized
        repository?.updateWorkspaceCwd(normalized)
        _state.update {
            it.copy(
                cwdInput = normalized,
                projectBrowserPath = normalized,
                projectBrowserError = null,
            )
        }
    }

    fun newChatInProject(path: String) {
        repository?.startNewChat()
        selectProject(path)
        _state.update {
            it.withThreadComposerDraft(null).copy(
                error = null,
                goalPromptMode = false,
                activeComposerPanel = null,
                composerPanelLoading = false,
            )
        }
    }

    fun updateModel(modelId: String) {
        val repository = repository ?: return
        if (repository.state.value.models.none { it.id == modelId }) return
        preferences.lastModelId = modelId
        repository.selectModel(modelId)
        preferences.lastReasoningEffort = repository.state.value.selectedReasoningEffort
    }

    fun updateReasoningEffort(effort: String) {
        val repository = repository ?: return
        val model = repository.state.value.models
            .firstOrNull { it.id == repository.state.value.selectedModelId }
            ?: return
        if (effort !in model.reasoningEfforts) return
        preferences.lastReasoningEffort = effort
        repository.selectReasoningEffort(effort)
    }

    fun updatePermissionProfile(profile: PermissionProfile, rememberForFutureSessions: Boolean = false) {
        val repository = repository ?: return
        repository.selectPermissionProfile(profile)
        val threadId = repository.state.value.selectedThreadId
        viewModelScope.launch {
            try {
                if (threadId != null) repository.updateThreadPermissions(threadId, profile)
                if (rememberForFutureSessions) repository.persistDefaultPermissionProfile(profile)
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not update permissions") }
            }
        }
    }

    fun beginLogin() {
        val raw = _state.value.urlInput
        val normalized = try {
            normalizeDeploymentUrl(raw).toString().trimEnd('/')
        } catch (error: Exception) {
            _state.update { it.copy(error = error.message) }
            return
        }
        preferences.deploymentUrl = normalized
        _state.update {
            it.copy(
                phase = AppPhase.AUTHENTICATING,
                urlInput = normalized,
                authUrl = "$normalized/login?next=%2Fchat",
                error = null,
            )
        }
    }

    fun cancelLogin() {
        if (_state.value.phase == AppPhase.AUTHENTICATING) {
            _state.update { it.copy(phase = AppPhase.SIGNED_OUT) }
        }
    }

    fun checkLoginCookie(pageUrl: String?) {
        if (connecting || checkingLoginCookie || _state.value.phase != AppPhase.AUTHENTICATING) return
        val base = (_state.value.urlInput.ifBlank { pageUrl.orEmpty() }).trim()
        if (base.isBlank()) return
        val cookie = cookieFor(base)
        if (cookie == null || !hasWebAuthSessionCookie(cookie)) return

        CookieManager.getInstance().flush()
        checkingLoginCookie = true
        viewModelScope.launch {
            try {
                val session = gateway.resolve(base, cookie)
                if (_state.value.phase == AppPhase.AUTHENTICATING) {
                    connectWithCookie(base, cookie, session)
                }
            } catch (_: SessionExpiredException) {
                // The login page may still be open and may have a stale cookie. Keep it visible.
            } catch (error: Exception) {
                _state.update {
                    it.copy(error = "Could not verify sign-in: ${friendlyMessage(error)}")
                }
            } finally {
                checkingLoginCookie = false
            }
        }
    }

    fun retryConnection() {
        val url = _state.value.urlInput.trim()
        val cookie = cookieFor(url)
        if (url.isBlank()) {
            _state.update { it.copy(error = "Enter a Codex Web URL") }
            return
        }
        val activeRepository = repository
        if (activeRepository != null && _state.value.phase == AppPhase.READY) {
            if (cookie == null || !hasWebAuthSessionCookie(cookie)) {
                returnToLogin("Your Codex Web login expired. Sign in again.", url)
            } else {
                startReconnect(activeRepository)
            }
        } else if (cookie == null || !hasWebAuthSessionCookie(cookie)) {
            beginLogin()
        } else {
            connectWithCookie(url, cookie)
        }
    }

    fun selectThread(threadId: String) {
        val repo = repository ?: return
        if (_state.value.snapshot.selectedThreadId == threadId) return
        _state.update {
            it.copy(
                openingThreadId = threadId,
                error = null,
                goalPromptMode = false,
                activeComposerPanel = null,
                composerPanelLoading = false,
            )
                .withThreadComposerDraft(threadId)
        }
        viewModelScope.launch {
            try {
                repo.openThread(threadId)
                repo.state.value.selectedThread?.cwd
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::selectProject)
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not open thread") }
            } finally {
                _state.update { it.copy(openingThreadId = null) }
            }
        }
    }

    fun newChat() {
        repository?.startNewChat()
        _state.update {
            it.withThreadComposerDraft(null).copy(
                error = null,
                goalPromptMode = false,
                activeComposerPanel = null,
                composerPanelLoading = false,
            )
        }
    }

    fun refreshThreads() {
        val repo = repository ?: return
        viewModelScope.launch {
            try {
                repo.refreshThreads()
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not refresh threads") }
            }
        }
    }

    fun browseProjectDirectory(path: String) {
        val repo = repository ?: return
        val snapshot = _state.value.snapshot
        val normalized = normalizeProjectPath(path)
            .ifBlank { normalizeProjectPath(_state.value.cwdInput) }
            .ifBlank { normalizeProjectPath(snapshot.workspaceCwd) }
            .ifBlank { normalizeProjectPath(snapshot.homeDirectory) }
            .ifBlank { "/" }
        _state.update {
            it.copy(
                projectBrowserPath = normalized,
                projectBrowserLoading = true,
                projectBrowserError = null,
            )
        }
        viewModelScope.launch {
            try {
                val directories = repo.readDirectories(normalized)
                _state.update {
                    it.copy(
                        projectBrowserDirectories = directories,
                        projectBrowserLoading = false,
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        projectBrowserDirectories = emptyList(),
                        projectBrowserLoading = false,
                        projectBrowserError = error.message ?: "Could not read server folder",
                    )
                }
            }
        }
    }

    fun loadEarlierTurns() {
        val repo = repository ?: return
        val threadId = _state.value.snapshot.selectedThreadId ?: return
        if (_state.value.loadingEarlierTurns) return
        _state.update { it.copy(loadingEarlierTurns = true, error = null) }
        viewModelScope.launch {
            try {
                repo.loadEarlierTurns(threadId)
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not load earlier messages") }
            } finally {
                _state.update { it.copy(loadingEarlierTurns = false) }
            }
        }
    }

    fun sendMessage() {
        val repo = repository ?: return
        val current = _state.value
        val submittedDraft = current.composerText
        val originDraftKey = current.composerDraftKey
        val text = submittedDraft.trim()
        if (text.isEmpty() || _state.value.sending) return
        val snapshot = current.snapshot
        if (snapshot.connection != "connected") {
            _state.update { it.copy(error = "Connection lost. Reconnecting to app-server…") }
            return
        }
        val isGoalPrompt = current.goalPromptMode
        val command = if (isGoalPrompt) null else parseComposerCommand(text)
        val commandCanRunDuringTurn = when (command) {
            ComposerCommand.GoalPrompt,
            is ComposerCommand.GoalSet,
            ComposerCommand.GoalPause,
            ComposerCommand.GoalResume,
            ComposerCommand.GoalClear,
            ComposerCommand.GoalEdit,
            -> true
            else -> false
        }
        if (snapshot.selectedActiveTurnId != null && (isGoalPrompt || !commandCanRunDuringTurn)) return
        val cwd = _state.value.cwdInput.ifBlank { snapshot.workspaceCwd }
        if (!isGoalPrompt && command == null && cwd.isBlank()) {
            _state.update { it.copy(error = "Working directory is required") }
            return
        }
        if (
            (isGoalPrompt || command is ComposerCommand.GoalSet) &&
            snapshot.selectedThreadId == null &&
            cwd.isBlank()
        ) {
            _state.update { it.copy(error = "Working directory is required to start a goal") }
            return
        }
        val collaborationMode = if (current.planMode) "plan" else "default"
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                if (isGoalPrompt) {
                    repo.setGoal(
                        objective = text,
                        status = "active",
                        cwd = cwd,
                        model = snapshot.selectedModelId,
                        effort = snapshot.selectedReasoningEffort,
                        mode = collaborationMode,
                        createThread = true,
                    )
                } else when (command) {
                    null -> repo.sendMessage(
                        text = text,
                        cwd = cwd,
                        model = snapshot.selectedModelId,
                        effort = snapshot.selectedReasoningEffort,
                        mode = collaborationMode,
                    )
                    ComposerCommand.GoalPrompt -> {
                        _state.update { it.copy(goalPromptMode = true) }
                    }
                    is ComposerCommand.GoalSet -> repo.setGoal(
                        objective = command.objective,
                        status = "active",
                        cwd = cwd,
                        model = snapshot.selectedModelId,
                        effort = snapshot.selectedReasoningEffort,
                        mode = collaborationMode,
                        createThread = true,
                    )
                    ComposerCommand.GoalPause -> {
                        val threadId = snapshot.selectedThreadId
                            ?: throw RpcException("Open a thread before pausing its goal")
                        repo.setGoal(status = "paused", mode = collaborationMode)
                        snapshot.selectedActiveTurnId?.let { turnId ->
                            repo.interrupt(threadId, turnId)
                        }
                    }
                    ComposerCommand.GoalResume -> {
                        if (snapshot.selectedThreadId == null) {
                            throw RpcException("Open a thread before resuming its goal")
                        }
                        repo.setGoal(status = "active", mode = collaborationMode)
                    }
                    ComposerCommand.GoalClear -> {
                        val threadId = snapshot.selectedThreadId
                            ?: throw RpcException("Open a thread before clearing its goal")
                        if (!repo.clearGoal(threadId)) {
                            throw RpcException("No goal to clear")
                        }
                    }
                    ComposerCommand.GoalEdit -> {
                        val threadId = snapshot.selectedThreadId
                            ?: throw RpcException("No goal set. Use /goal <objective> to start one.")
                        if (repo.getGoal(threadId) == null) {
                            throw RpcException("No goal set. Use /goal <objective> to start one.")
                        }
                        _state.update { it.copy(goalEditorOpen = true) }
                    }
                    ComposerCommand.Plan -> {
                        _state.update { it.copy(planMode = true, goalPromptMode = false) }
                    }
                    ComposerCommand.Compact -> {
                        val threadId = snapshot.selectedThreadId
                            ?: throw RpcException("Start a conversation before compacting context")
                        repo.compactThread(threadId)
                    }
                    ComposerCommand.Mcp -> {
                        _state.update {
                            it.copy(
                                activeComposerPanel = ComposerPanel.MCP,
                                composerPanelLoading = true,
                            )
                        }
                        loadMcpServers(repo)
                    }
                    ComposerCommand.Review -> {
                        _state.update {
                            it.copy(
                                activeComposerPanel = ComposerPanel.REVIEW,
                                composerPanelLoading = false,
                            )
                        }
                    }
                    ComposerCommand.Reasoning -> {
                        _state.update {
                            it.copy(
                                activeComposerPanel = ComposerPanel.REASONING,
                                composerPanelLoading = false,
                            )
                        }
                    }
                    ComposerCommand.Model -> {
                        _state.update {
                            it.copy(
                                activeComposerPanel = ComposerPanel.MODEL,
                                composerPanelLoading = false,
                            )
                        }
                    }
                    ComposerCommand.Status -> {
                        _state.update {
                            it.copy(
                                activeComposerPanel = ComposerPanel.STATUS,
                                composerPanelLoading = true,
                            )
                        }
                        loadRateLimits(repo)
                    }
                    ComposerCommand.Memories -> {
                        _state.update {
                            it.copy(
                                activeComposerPanel = ComposerPanel.MEMORY,
                                composerPanelLoading = true,
                            )
                        }
                        loadMemorySettings(repo)
                    }
                    ComposerCommand.Help -> {
                        _state.update {
                            it.copy(
                                activeComposerPanel = ComposerPanel.HELP,
                                composerPanelLoading = false,
                            )
                        }
                    }
                    ComposerCommand.Clear -> {
                        val threadId = snapshot.selectedThreadId
                        if (threadId != null) {
                            val turnIds = snapshot.selectedTurns.mapTo(mutableSetOf()) { it.id }
                            _state.update {
                                val hidden = it.hiddenTurnIdsByThread[threadId].orEmpty() + turnIds
                                it.copy(
                                    hiddenTurnIdsByThread = it.hiddenTurnIdsByThread +
                                        (threadId to hidden),
                                )
                            }
                        }
                    }
                    ComposerCommand.Cost -> {
                        _state.update {
                            it.copy(
                                activeComposerPanel = ComposerPanel.COST,
                                composerPanelLoading = false,
                            )
                        }
                    }
                }
                _state.update {
                    it.afterComposerSend(originDraftKey, submittedDraft)
                        .copy(goalPromptMode = command == ComposerCommand.GoalPrompt)
                }
                runCatching { repo.refreshThreads() }
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not send message") }
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    fun dismissGoalEditor() {
        _state.update { it.copy(goalEditorOpen = false) }
    }

    fun updateGoalObjective(objective: String) {
        val repo = repository ?: return
        val current = _state.value
        val snapshot = current.snapshot
        val threadId = snapshot.selectedThreadId ?: return
        val goal = snapshot.selectedGoal ?: return
        val updatedObjective = objective.trim()
        if (updatedObjective.isBlank() || current.sending) return
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                repo.setGoal(
                    objective = updatedObjective,
                    status = editedGoalStatus(goal.status),
                    cwd = _state.value.cwdInput.ifBlank { snapshot.workspaceCwd },
                    model = snapshot.selectedModelId,
                    effort = snapshot.selectedReasoningEffort,
                    mode = if (current.planMode) "plan" else "default",
                )
                _state.update { it.copy(goalEditorOpen = false) }
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not update goal") }
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    fun dismissComposerPanel() {
        _state.update { it.copy(activeComposerPanel = null, composerPanelLoading = false) }
    }

    fun dismissGoalPrompt() {
        _state.update { it.copy(goalPromptMode = false) }
    }

    fun setPlanMode(enabled: Boolean) {
        _state.update { it.copy(planMode = enabled) }
    }

    fun startReviewUncommitted() {
        runReview { repo, threadId -> repo.startReviewUncommitted(threadId) }
    }

    fun startReviewBaseBranch(branch: String) {
        val trimmed = branch.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "Enter a base branch to compare against") }
            return
        }
        runReview { repo, threadId -> repo.startReviewBaseBranch(threadId, trimmed) }
    }

    fun saveMemorySettings(useMemories: Boolean, generateMemories: Boolean) {
        val repo = repository ?: return
        val threadId = _state.value.snapshot.selectedThreadId
        if (_state.value.composerPanelLoading) return
        _state.update { it.copy(composerPanelLoading = true, error = null) }
        viewModelScope.launch {
            try {
                repo.updateMemorySettings(threadId, useMemories, generateMemories)
                _state.update {
                    it.copy(
                        activeComposerPanel = null,
                        composerPanelLoading = false,
                        memorySettings = MemorySettings(useMemories, generateMemories),
                    )
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        composerPanelLoading = false,
                        error = error.message ?: "Could not update memory settings",
                    )
                }
            }
        }
    }

    private fun runReview(start: suspend (AppServerRepository, String) -> Unit) {
        val repo = repository ?: return
        val threadId = _state.value.snapshot.selectedThreadId
        if (threadId == null) {
            _state.update { it.copy(error = "Start a conversation before reviewing changes") }
            return
        }
        if (_state.value.composerPanelLoading) return
        _state.update { it.copy(composerPanelLoading = true, error = null) }
        viewModelScope.launch {
            try {
                start(repo, threadId)
                _state.update {
                    it.copy(activeComposerPanel = null, composerPanelLoading = false)
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        composerPanelLoading = false,
                        error = error.message ?: "Could not start review",
                    )
                }
            }
        }
    }

    private fun loadMcpServers(repo: AppServerRepository) {
        viewModelScope.launch {
            try {
                val servers = repo.listMcpServers(_state.value.snapshot.selectedThreadId)
                _state.update { it.copy(mcpServers = servers, composerPanelLoading = false) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        composerPanelLoading = false,
                        error = error.message ?: "Could not load MCP servers",
                    )
                }
            }
        }
    }

    private fun loadRateLimits(repo: AppServerRepository) {
        viewModelScope.launch {
            try {
                val limits = repo.readRateLimits()
                _state.update { it.copy(rateLimits = limits, composerPanelLoading = false) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        rateLimits = null,
                        composerPanelLoading = false,
                        error = error.message ?: "Could not load rate limits",
                    )
                }
            }
        }
    }

    private fun loadMemorySettings(repo: AppServerRepository) {
        viewModelScope.launch {
            try {
                val settings = repo.readMemorySettings()
                _state.update {
                    it.copy(memorySettings = settings, composerPanelLoading = false)
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        memorySettings = it.memorySettings ?: MemorySettings(),
                        composerPanelLoading = false,
                        error = error.message ?: "Could not load memory settings",
                    )
                }
            }
        }
    }

    fun interrupt() {
        val repo = repository ?: return
        val snapshot = _state.value.snapshot
        val threadId = snapshot.selectedThreadId ?: return
        val turnId = snapshot.selectedActiveTurnId ?: return
        viewModelScope.launch {
            try {
                repo.interrupt(threadId, turnId)
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not interrupt turn") }
            }
        }
    }

    fun approve(request: PendingRequest, decision: ApprovalDecision) {
        viewModelScope.launch {
            try {
                repository?.respondToApproval(request, decision)
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Approval response failed") }
            }
        }
    }

    fun answerUserInput(request: PendingRequest, answers: Map<String, List<String>>) {
        viewModelScope.launch {
            try {
                repository?.answerUserInput(request, answers)
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Answering request failed") }
            }
        }
    }

    fun cancelMcpRequest(request: PendingRequest) {
        viewModelScope.launch {
            try {
                repository?.cancelMcpRequest(request)
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not cancel request") }
            }
        }
    }

    fun rejectUnsupportedRequest(request: PendingRequest) {
        viewModelScope.launch {
            try {
                repository?.rejectUnsupportedRequest(request)
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not reject request") }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
        repository?.clearError()
    }

    fun signOut() {
        closeTerminal()
        closeScreenShare()
        val url = _state.value.urlInput
        val cookie = cookieFor(url)
        val pushInstance = PushDistributors.ensureInstance(getApplication())
        reconnectJob?.cancel()
        reconnectJob = null
        repositoryCollector?.cancel()
        repository?.close()
        repository = null
        deploymentSession = null
        runCatching { PushDistributors.unregister(getApplication(), pushInstance) }
        preferences.notificationsEnabled = false
        preferences.pushStatus = null
        preferences.pushError = null
        viewModelScope.launch {
            runCatching { PushRegistrationClient.unregister(getApplication(), pushInstance, cookie) }
            runCatching { gateway.logout(url, cookie) }
        }
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        _state.value = AppUiState(
            phase = AppPhase.SIGNED_OUT,
            urlInput = url,
            cwdInput = "",
        )
        refreshPushDistributors()
    }

    fun discardWebViewCookies() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }

    override fun onCleared() {
        closeTerminal()
        closeScreenShare()
        reconnectJob?.cancel()
        repositoryCollector?.cancel()
        repository?.close()
        preferences.unregisterChangeListener(pushPreferenceListener)
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        super.onCleared()
    }

    private fun connectWithCookie(
        rawUrl: String,
        cookie: String,
        resolvedSession: DeploymentSession? = null,
    ) {
        if (connecting) return
        connecting = true
        reconnectJob?.cancel()
        reconnectJob = null
        _state.update {
            it.copy(
                phase = AppPhase.CONNECTING,
                urlInput = rawUrl.trim(),
                error = null,
                reconnecting = false,
                reconnectAttempt = 0,
            )
        }
        viewModelScope.launch {
            val oldCollector = repositoryCollector
            val oldRepository = repository
            repositoryCollector = null
            repository = null
            oldCollector?.cancel()
            oldRepository?.close()

            try {
                val normalized = normalizeDeploymentUrl(rawUrl).toString().trimEnd('/')
                preferences.deploymentUrl = normalized
                val session = resolvedSession ?: gateway.resolve(normalized, cookie)
                deploymentSession = session
                val repo = AppServerRepository(viewModelScope)
                repository = repo
                repositoryCollector = viewModelScope.launch {
                    var wasConnected = false
                    repo.state.collect { snapshot ->
                        if (snapshot.connection == "connected") {
                            wasConnected = true
                        }
                        _state.update {
                            val selectedProject = snapshot.selectedThread?.cwd
                                ?.takeIf { cwd -> cwd.isNotBlank() }
                                ?.let(::normalizeProjectPath)
                            val terminal = it.terminal?.let { terminal ->
                                if (
                                    snapshot.connection == "disconnected" &&
                                    (terminal.status == TerminalStatus.STARTING ||
                                        terminal.status == TerminalStatus.RUNNING)
                                ) {
                                    terminal.copy(
                                        status = TerminalStatus.ERROR,
                                        error = "Connection lost. Restart the terminal after reconnecting.",
                                    )
                                } else {
                                    terminal
                                }
                            }
                            it.copy(
                                snapshot = snapshot,
                                terminal = terminal,
                                cwdInput = selectedProject
                                    ?: it.cwdInput.ifBlank { snapshot.workspaceCwd },
                            ).withAdoptedSelectedThreadDraft()
                        }
                        if (
                            wasConnected &&
                            snapshot.connection == "disconnected" &&
                            repository === repo
                        ) {
                            wasConnected = false
                            startReconnect(repo)
                        }
                    }
                }
                repo.connect(session)
                preferences.lastModelId
                    ?.takeIf { preferred -> repo.state.value.models.any { it.id == preferred } }
                    ?.let(repo::selectModel)
                if (repo.state.value.selectedThreadId == null) {
                    preferences.lastReasoningEffort
                        ?.let(repo::selectReasoningEffort)
                }
                _state.update {
                    it.copy(
                        phase = AppPhase.READY,
                        reconnecting = false,
                        reconnectAttempt = 0,
                        cwdInput = it.cwdInput.ifBlank { session.user.cwd.ifBlank { session.homeDirectory } },
                    )
                }
                if (preferences.notificationsEnabled) registerPushNotifications()
                openPendingNotificationThread()
            } catch (error: SessionExpiredException) {
                deploymentSession = null
                closeScreenShare()
                _state.update {
                    it.copy(
                        phase = AppPhase.SIGNED_OUT,
                        error = "Your Codex Web login expired. Sign in again.",
                    )
                }
            } catch (error: Exception) {
                deploymentSession = null
                closeScreenShare()
                repositoryCollector?.cancel()
                repository?.close()
                repository = null
                repositoryCollector = null
                _state.update {
                    it.copy(
                        phase = AppPhase.SIGNED_OUT,
                        error = friendlyMessage(error),
                    )
                }
            } finally {
                connecting = false
            }
        }
    }

    private fun startReconnect(repo: AppServerRepository) {
        if (repository !== repo || _state.value.phase != AppPhase.READY) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            var attempt = 0
            while (isActive && repository === repo && _state.value.phase == AppPhase.READY) {
                attempt += 1
                _state.update {
                    it.copy(reconnecting = true, reconnectAttempt = attempt)
                }
                try {
                    val rawUrl = _state.value.urlInput.trim()
                    val cookie = cookieFor(rawUrl)
                    if (cookie == null || !hasWebAuthSessionCookie(cookie)) {
                        returnToLogin("Your Codex Web login expired. Sign in again.", rawUrl)
                        return@launch
                    }
                    val normalized = normalizeDeploymentUrl(rawUrl).toString().trimEnd('/')
                    val session = gateway.resolve(normalized, cookie)
                    deploymentSession = session
                    val selectedThreadId = _state.value.snapshot.selectedThreadId
                    repo.connect(
                        session = session,
                        preferredThreadId = selectedThreadId,
                        openMostRecentThread = false,
                    )
                    preferences.deploymentUrl = normalized
                    preferences.lastModelId
                        ?.takeIf { preferred -> repo.state.value.models.any { it.id == preferred } }
                        ?.let(repo::selectModel)
                    if (repo.state.value.selectedThreadId == null) {
                        preferences.lastReasoningEffort
                            ?.let(repo::selectReasoningEffort)
                    }
                    _state.update {
                        it.copy(
                            reconnecting = false,
                            reconnectAttempt = 0,
                            error = null,
                        )
                    }
                    if (preferences.notificationsEnabled) registerPushNotifications()
                    return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SessionExpiredException) {
                    returnToLogin("Your Codex Web login expired. Sign in again.", _state.value.urlInput)
                    return@launch
                } catch (error: RpcException) {
                    if (error.code == 401) {
                        returnToLogin("Your Codex Web login expired. Sign in again.", _state.value.urlInput)
                        return@launch
                    }
                    repo.markDisconnected(friendlyMessage(error))
                } catch (error: Exception) {
                    repo.markDisconnected(friendlyMessage(error))
                }
                delay(reconnectDelayMillis(attempt))
            }
        }
    }

    private fun returnToLogin(message: String, url: String) {
        deploymentSession = null
        closeScreenShare()
        repositoryCollector?.cancel()
        repositoryCollector = null
        repository?.close()
        repository = null
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        _state.update {
            it.copy(
                phase = AppPhase.SIGNED_OUT,
                urlInput = url.trim(),
                error = message,
            )
        }
    }

    private fun registerPushNotifications() {
        val packageName = preferences.pushDistributor
            ?: _state.value.pushSelectedDistributor
            ?: _state.value.pushDistributors.firstOrNull()?.packageName
        if (packageName == null) {
            preferences.pushError = "Install a UnifiedPush distributor such as Sunup or ntfy first."
            syncPushPreferenceState()
            return
        }
        preferences.pushDistributor = packageName
        preferences.pushStatus = "Waiting for the UnifiedPush distributor…"
        preferences.pushError = null
        syncPushPreferenceState()
        runCatching {
            PushDistributors.save(getApplication(), packageName)
            PushDistributors.register(
                getApplication(),
                PushDistributors.ensureInstance(getApplication()),
                preferences.pushVapidPublicKey,
            )
        }.onFailure { error ->
            preferences.pushStatus = null
            preferences.pushError = error.message ?: "Could not start UnifiedPush registration"
            syncPushPreferenceState()
        }
    }

    private fun syncPushPreferenceState() {
        _state.update {
            it.copy(
                pushEnabled = preferences.notificationsEnabled,
                pushSelectedDistributor = preferences.pushDistributor ?: it.pushSelectedDistributor,
                pushStatus = preferences.pushStatus,
                pushError = preferences.pushError,
            )
        }
    }

    private fun openPendingNotificationThread() {
        val threadId = pendingNotificationThreadId ?: return
        if (_state.value.phase != AppPhase.READY || repository == null) return
        pendingNotificationThreadId = null
        selectThread(threadId)
    }

    private fun cookieFor(url: String): String? {
        if (url.isBlank()) return null
        return runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
    }

    private fun friendlyMessage(error: Exception): String {
        val message = error.message ?: error::class.simpleName ?: "Connection failed"
        return when (error) {
            is IOException -> message
            else -> message
        }
    }
}
