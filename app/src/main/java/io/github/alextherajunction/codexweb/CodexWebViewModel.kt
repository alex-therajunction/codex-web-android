package io.github.alextherajunction.codexweb

import android.app.Application
import android.content.Intent
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.alextherajunction.codexweb.data.AppPreferences
import io.github.alextherajunction.codexweb.data.DeploymentSession
import io.github.alextherajunction.codexweb.data.SessionExpiredException
import io.github.alextherajunction.codexweb.data.WebSessionGateway
import io.github.alextherajunction.codexweb.data.hasWebAuthSessionCookie
import io.github.alextherajunction.codexweb.data.normalizeDeploymentUrl
import io.github.alextherajunction.codexweb.protocol.AppServerRepository
import io.github.alextherajunction.codexweb.protocol.AppServerSnapshot
import io.github.alextherajunction.codexweb.protocol.ApprovalDecision
import io.github.alextherajunction.codexweb.protocol.PendingRequest
import io.github.alextherajunction.codexweb.protocol.PermissionProfile
import io.github.alextherajunction.codexweb.protocol.RpcException
import io.github.alextherajunction.codexweb.protocol.ServerDirectory
import io.github.alextherajunction.codexweb.protocol.normalizeProjectPath
import io.github.alextherajunction.codexweb.push.PushDistributor
import io.github.alextherajunction.codexweb.push.PushDistributors
import io.github.alextherajunction.codexweb.push.PushNotifier
import io.github.alextherajunction.codexweb.push.PushRegistrationClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

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
    val pushEnabled: Boolean = false,
    val pushDistributors: List<PushDistributor> = emptyList(),
    val pushSelectedDistributor: String? = null,
    val pushStatus: String? = null,
    val pushError: String? = null,
    val snapshot: AppServerSnapshot = AppServerSnapshot(),
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
    private var connecting = false
    private var checkingLoginCookie = false
    private var pendingNotificationThreadId: String? = null
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
        _state.update { it.withThreadComposerDraft(null).copy(error = null) }
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

    fun updatePermissionProfile(profile: PermissionProfile) {
        val repository = repository ?: return
        repository.selectPermissionProfile(profile)
        val threadId = repository.state.value.selectedThreadId ?: return
        viewModelScope.launch {
            try {
                repository.updateThreadPermissions(threadId, profile)
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
            it.copy(openingThreadId = threadId, error = null)
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
        _state.update { it.withThreadComposerDraft(null).copy(error = null) }
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
        if (snapshot.selectedActiveTurnId != null) return
        val cwd = _state.value.cwdInput.ifBlank { snapshot.workspaceCwd }
        if (cwd.isBlank()) {
            _state.update { it.copy(error = "Working directory is required") }
            return
        }
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                repo.sendMessage(
                    text = text,
                    cwd = cwd,
                    model = snapshot.selectedModelId,
                    effort = snapshot.selectedReasoningEffort,
                )
                _state.update { it.afterComposerSend(originDraftKey, submittedDraft) }
                runCatching { repo.refreshThreads() }
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not send message") }
            } finally {
                _state.update { it.copy(sending = false) }
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
        val url = _state.value.urlInput
        val cookie = cookieFor(url)
        val pushInstance = PushDistributors.ensureInstance(getApplication())
        reconnectJob?.cancel()
        reconnectJob = null
        repositoryCollector?.cancel()
        repository?.close()
        repository = null
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
                            it.copy(
                                snapshot = snapshot,
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
                _state.update {
                    it.copy(
                        phase = AppPhase.SIGNED_OUT,
                        error = "Your Codex Web login expired. Sign in again.",
                    )
                }
            } catch (error: Exception) {
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
