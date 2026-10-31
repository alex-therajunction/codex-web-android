package io.github.alextherajunction.codexweb

import android.app.Application
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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val sending: Boolean = false,
    val openingThreadId: String? = null,
    val loadingEarlierTurns: Boolean = false,
    val cwdInput: String = "",
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
        AppUiState(urlInput = preferences.deploymentUrl.orEmpty()),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var repository: AppServerRepository? = null
    private var repositoryCollector: Job? = null
    private var connecting = false
    private var checkingLoginCookie = false

    init {
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
        _state.update { it.copy(composerText = value) }
    }

    fun updateCwd(value: String) {
        _state.update { it.copy(cwdInput = value) }
    }

    fun updateModel(modelId: String) {
        val repository = repository ?: return
        if (repository.state.value.models.none { it.id == modelId }) return
        preferences.lastModelId = modelId
        repository.selectModel(modelId)
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
        if (cookie == null || !hasWebAuthSessionCookie(cookie)) {
            beginLogin()
        } else {
            connectWithCookie(url, cookie)
        }
    }

    fun selectThread(threadId: String) {
        val repo = repository ?: return
        if (_state.value.snapshot.selectedThreadId == threadId) return
        _state.update { it.copy(openingThreadId = threadId, error = null) }
        viewModelScope.launch {
            try {
                repo.openThread(threadId)
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not open thread") }
            } finally {
                _state.update { it.copy(openingThreadId = null) }
            }
        }
    }

    fun newChat() {
        repository?.startNewChat()
        _state.update { it.copy(composerText = "", error = null) }
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
        val text = _state.value.composerText.trim()
        if (text.isEmpty() || _state.value.sending) return
        val snapshot = _state.value.snapshot
        if (snapshot.selectedActiveTurnId != null) return
        val cwd = _state.value.cwdInput.ifBlank { snapshot.workspaceCwd }
        if (cwd.isBlank()) {
            _state.update { it.copy(error = "Working directory is required") }
            return
        }
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                repo.sendMessage(text, cwd, snapshot.selectedModelId)
                _state.update { it.copy(composerText = "") }
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
        repositoryCollector?.cancel()
        repository?.close()
        repository = null
        viewModelScope.launch {
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
    }

    fun discardWebViewCookies() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }

    override fun onCleared() {
        repositoryCollector?.cancel()
        repository?.close()
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
        _state.update {
            it.copy(
                phase = AppPhase.CONNECTING,
                urlInput = rawUrl.trim(),
                error = null,
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
                    repo.state.collect { snapshot ->
                        _state.update {
                            it.copy(
                                snapshot = snapshot,
                                cwdInput = it.cwdInput.ifBlank { snapshot.workspaceCwd },
                            )
                        }
                    }
                }
                repo.connect(session)
                preferences.lastModelId
                    ?.takeIf { preferred -> repo.state.value.models.any { it.id == preferred } }
                    ?.let(repo::selectModel)
                _state.update {
                    it.copy(
                        phase = AppPhase.READY,
                        cwdInput = it.cwdInput.ifBlank { session.user.cwd.ifBlank { session.homeDirectory } },
                    )
                }
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
