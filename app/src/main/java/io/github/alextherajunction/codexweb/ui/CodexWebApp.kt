package io.github.alextherajunction.codexweb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.alextherajunction.codexweb.AppPhase
import io.github.alextherajunction.codexweb.AppUiState
import io.github.alextherajunction.codexweb.CodexWebViewModel
import io.github.alextherajunction.codexweb.protocol.ApprovalDecision
import io.github.alextherajunction.codexweb.protocol.PendingRequest
import io.github.alextherajunction.codexweb.ui.screens.AuthWebViewScreen
import io.github.alextherajunction.codexweb.ui.screens.MainScreen
import io.github.alextherajunction.codexweb.ui.screens.SetupScreen
import io.github.alextherajunction.codexweb.ui.theme.CodexInk

@Composable
fun CodexWebApp(viewModel: CodexWebViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = CodexInk,
    ) {
        when (state.phase) {
            AppPhase.STARTUP -> StartupScreen()
            AppPhase.SIGNED_OUT -> SetupScreen(
                state = state,
                onUrlChange = viewModel::updateUrl,
                onContinue = viewModel::beginLogin,
                onRetry = viewModel::retryConnection,
            )
            AppPhase.AUTHENTICATING -> AuthWebViewScreen(
                url = state.authUrl,
                onCookieAvailable = viewModel::checkLoginCookie,
                onCancel = viewModel::cancelLogin,
                onClearCookies = viewModel::discardWebViewCookies,
            )
            AppPhase.CONNECTING -> ConnectingScreen(state)
            AppPhase.READY -> MainScreen(
                state = state,
                onThreadSelected = viewModel::selectThread,
                onNewChat = viewModel::newChat,
                onNewChatInProject = viewModel::newChatInProject,
                onProjectSelected = viewModel::selectProject,
                onBrowseProject = viewModel::browseProjectDirectory,
                onRefreshThreads = viewModel::refreshThreads,
                onLoadEarlierTurns = viewModel::loadEarlierTurns,
                onModelSelected = viewModel::updateModel,
                onReasoningEffortSelected = viewModel::updateReasoningEffort,
                onPermissionSelected = viewModel::updatePermissionProfile,
                onComposerChange = viewModel::updateComposer,
                onSend = viewModel::sendMessage,
                onInterrupt = viewModel::interrupt,
                onCwdChange = viewModel::updateCwd,
                onApprove = viewModel::approve,
                onAnswerUserInput = viewModel::answerUserInput,
                onCancelMcp = viewModel::cancelMcpRequest,
                onRejectUnsupported = viewModel::rejectUnsupportedRequest,
                onRefreshPushDistributors = viewModel::refreshPushDistributors,
                onSelectPushDistributor = viewModel::selectPushDistributor,
                onPushNotificationsEnabled = viewModel::setPushNotificationsEnabled,
                onRetryPushRegistration = viewModel::retryPushRegistration,
                onDismissGoalEditor = viewModel::dismissGoalEditor,
                onSaveGoal = viewModel::updateGoalObjective,
                onDismissGoalPrompt = viewModel::dismissGoalPrompt,
                onDismissComposerPanel = viewModel::dismissComposerPanel,
                onPlanModeChange = viewModel::setPlanMode,
                onStartReviewUncommitted = viewModel::startReviewUncommitted,
                onStartReviewBaseBranch = viewModel::startReviewBaseBranch,
                onSaveMemorySettings = viewModel::saveMemorySettings,
                onSignOut = viewModel::signOut,
                onReconnect = viewModel::retryConnection,
            )
        }
        Box(Modifier.fillMaxSize()) {
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding(),
            )
        }
    }
}

@Composable
fun StartupScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ConnectingScreen(state: AppUiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SetupLikeLoading(state.urlInput)
    }
}

@Composable
private fun SetupLikeLoading(url: String) {
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        androidx.compose.foundation.layout.Spacer(Modifier)
        androidx.compose.material3.Text(
            text = "Connecting to app-server",
            style = MaterialTheme.typography.titleMedium,
        )
        androidx.compose.material3.Text(
            text = url,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
