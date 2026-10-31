package io.github.alextherajunction.codexweb.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alextherajunction.codexweb.AppUiState
import io.github.alextherajunction.codexweb.protocol.AppServerSnapshot
import io.github.alextherajunction.codexweb.protocol.ApprovalDecision
import io.github.alextherajunction.codexweb.protocol.ChatItem
import io.github.alextherajunction.codexweb.protocol.PendingRequest
import io.github.alextherajunction.codexweb.protocol.ThreadInfo
import io.github.alextherajunction.codexweb.protocol.TurnInfo
import io.github.alextherajunction.codexweb.ui.components.ChatItemRow
import io.github.alextherajunction.codexweb.ui.components.ContextChips
import io.github.alextherajunction.codexweb.ui.components.DiagnosticsDialog
import io.github.alextherajunction.codexweb.ui.components.GoalSummaryCard
import io.github.alextherajunction.codexweb.ui.components.PendingRequestCard
import io.github.alextherajunction.codexweb.ui.components.PlanSummaryCard
import io.github.alextherajunction.codexweb.ui.components.TurnErrorCard
import io.github.alextherajunction.codexweb.ui.components.UserInputDialog
import io.github.alextherajunction.codexweb.ui.components.WorkingDirectoryDialog
import io.github.alextherajunction.codexweb.ui.components.WorkingIndicator
import io.github.alextherajunction.codexweb.ui.theme.CodexAmber
import io.github.alextherajunction.codexweb.ui.theme.CodexDanger
import io.github.alextherajunction.codexweb.ui.theme.CodexLime
import io.github.alextherajunction.codexweb.ui.theme.CodexMuted
import io.github.alextherajunction.codexweb.ui.theme.CodexOutline
import io.github.alextherajunction.codexweb.ui.theme.CodexSurface
import io.github.alextherajunction.codexweb.ui.theme.CodexSurfaceHigh
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: AppUiState,
    onThreadSelected: (String) -> Unit,
    onNewChat: () -> Unit,
    onRefreshThreads: () -> Unit,
    onModelSelected: (String) -> Unit,
    onComposerChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onCwdChange: (String) -> Unit,
    onApprove: (PendingRequest, ApprovalDecision) -> Unit,
    onAnswerUserInput: (PendingRequest, Map<String, List<String>>) -> Unit,
    onCancelMcp: (PendingRequest) -> Unit,
    onRejectUnsupported: (PendingRequest) -> Unit,
    onSignOut: () -> Unit,
    onReconnect: () -> Unit,
) {
    val snapshot = state.snapshot
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showModelMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showCwdDialog by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var userInputRequest by remember { mutableStateOf<PendingRequest?>(null) }

    val selectedModel = snapshot.models.firstOrNull { it.id == snapshot.selectedModelId }
    val activeTurnId = snapshot.selectedActiveTurnId
    val firstPending = snapshot.pendingRequests.firstOrNull()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(336.dp),
                drawerContainerColor = CodexSurface,
            ) {
                ThreadDrawer(
                    state = state,
                    onClose = { scope.launch { drawerState.close() } },
                    onNewChat = {
                        onNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onThreadSelected = {
                        onThreadSelected(it)
                        scope.launch { drawerState.close() }
                    },
                    onRefreshThreads = onRefreshThreads,
                    onSignOut = onSignOut,
                )
            }
        },
    ) {
        Scaffold(
            containerColor = Color(0xFF0E100D),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF12150F),
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Open threads")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = snapshot.selectedThread?.title ?: "New chat",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = threadSubtitle(snapshot.selectedThread, snapshot.connection),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (snapshot.connection == "connected") CodexMuted else CodexDanger,
                            )
                        }
                    },
                    actions = {
                        Box {
                            TextButton(onClick = { showModelMenu = true }) {
                                Text(
                                    text = selectedModel?.displayName ?: "Model",
                                    maxLines = 1,
                                    color = CodexLime,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            DropdownMenu(
                                expanded = showModelMenu,
                                onDismissRequest = { showModelMenu = false },
                            ) {
                                if (snapshot.models.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No models from the server") },
                                        onClick = {},
                                        enabled = false,
                                    )
                                } else {
                                    snapshot.models.forEach { model ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(model.displayName)
                                                    if (model.description.isNotBlank()) {
                                                        Text(
                                                            model.description,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = CodexMuted,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                            },
                                            leadingIcon = {
                                                if (model.id == snapshot.selectedModelId) {
                                                    Icon(Icons.Rounded.Check, null, tint = CodexLime)
                                                }
                                            },
                                            onClick = {
                                                onModelSelected(model.id)
                                                showModelMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false },
                            ) {
                                if (snapshot.connection != "connected") {
                                    DropdownMenuItem(
                                        text = { Text("Reconnect app-server") },
                                        leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                        onClick = {
                                            showOverflow = false
                                            onReconnect()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Refresh threads") },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                    onClick = {
                                        showOverflow = false
                                        onRefreshThreads()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Server working directory") },
                                    leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                                    onClick = {
                                        showOverflow = false
                                        showCwdDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Diagnostics (${snapshot.diagnostics.size})") },
                                    leadingIcon = { Icon(Icons.Rounded.BugReport, null) },
                                    onClick = {
                                        showOverflow = false
                                        showDiagnostics = true
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Sign out") },
                                    leadingIcon = { Icon(Icons.Rounded.Logout, null) },
                                    onClick = {
                                        showOverflow = false
                                        onSignOut()
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                ContextChips(
                    cwd = state.cwdInput.ifBlank { snapshot.workspaceCwd },
                    usage = snapshot.selectedUsage,
                    model = selectedModel?.displayName,
                )
                Box(Modifier.weight(1f)) {
                    Conversation(
                        snapshot = snapshot,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (firstPending != null) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        PendingRequestCard(
                            request = firstPending,
                            index = 0,
                            total = snapshot.pendingRequests.size,
                            onApprove = { onApprove(firstPending, it) },
                            onAnswer = { userInputRequest = firstPending },
                            onCancelMcp = { onCancelMcp(firstPending) },
                            onReject = { onRejectUnsupported(firstPending) },
                        )
                    }
                }
                Composer(
                    value = state.composerText,
                    cwd = state.cwdInput.ifBlank { snapshot.workspaceCwd },
                    sending = state.sending,
                    activeTurnId = activeTurnId,
                    onValueChange = onComposerChange,
                    onSend = onSend,
                    onInterrupt = onInterrupt,
                    onCwdClick = { showCwdDialog = true },
                )
            }
        }
    }

    if (showCwdDialog) {
        WorkingDirectoryDialog(
            current = state.cwdInput.ifBlank { snapshot.workspaceCwd },
            onDismiss = { showCwdDialog = false },
            onConfirm = onCwdChange,
        )
    }
    if (showDiagnostics) {
        DiagnosticsDialog(
            diagnostics = snapshot.diagnostics,
            onDismiss = { showDiagnostics = false },
        )
    }
    userInputRequest?.let { request ->
        UserInputDialog(
            request = request,
            onDismiss = { userInputRequest = null },
            onSubmit = { answers ->
                onAnswerUserInput(request, answers)
                userInputRequest = null
            },
        )
    }
}

@Composable
private fun ThreadDrawer(
    state: AppUiState,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onThreadSelected: (String) -> Unit,
    onRefreshThreads: () -> Unit,
    onSignOut: () -> Unit,
) {
    val snapshot = state.snapshot
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(CodexLime, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "›_",
                    color = Color(0xFF12150F),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Codex Web", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (snapshot.connection == "connected") "app-server connected" else snapshot.connection,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (snapshot.connection == "connected") CodexLime else CodexDanger,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close threads")
            }
        }
        Button(
            onClick = onNewChat,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CodexLime, contentColor = Color(0xFF12150F)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New chat", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "THREADS",
                style = MaterialTheme.typography.labelMedium,
                color = CodexMuted,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefreshThreads, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.Refresh, "Refresh threads", modifier = Modifier.size(17.dp), tint = CodexMuted)
            }
        }
        if (snapshot.threads.isEmpty()) {
            Column(Modifier.padding(20.dp)) {
                Text("No threads yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Start a chat and it will appear here from thread/list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CodexMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(snapshot.threads, key = { it.id }) { thread ->
                    ThreadRow(
                        thread = thread,
                        selected = thread.id == snapshot.selectedThreadId,
                        onClick = { onThreadSelected(thread.id) },
                    )
                }
            }
        }
        HorizontalDivider(color = CodexOutline)
        Column(Modifier.padding(18.dp)) {
            Text(
                snapshot.userEmail.ifBlank { "Signed in" },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (snapshot.codexAccount.isNotBlank()) {
                Text(
                    snapshot.codexAccount,
                    style = MaterialTheme.typography.labelMedium,
                    color = CodexMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                snapshot.deploymentUrl,
                style = MaterialTheme.typography.labelMedium,
                color = CodexMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onSignOut, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Icon(Icons.Rounded.Logout, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun ThreadRow(
    thread: ThreadInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clickable(onClick = onClick),
        color = if (selected) CodexSurfaceHigh else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(7.dp)
                    .background(threadStatusColor(thread), CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    thread.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                )
                if (thread.name != null && thread.preview.isNotBlank()) {
                    Text(
                        thread.preview,
                        style = MaterialTheme.typography.labelMedium,
                        color = CodexMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        threadStateLabel(thread),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (thread.isActive) CodexAmber else CodexMuted,
                    )
                    if (thread.cwd.isNotBlank()) {
                        Text(
                            " · ${thread.cwd.substringAfterLast('/')}",
                            style = MaterialTheme.typography.labelMedium,
                            color = CodexMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Conversation(
    snapshot: AppServerSnapshot,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val rows = remember(snapshot.selectedTurns) {
        snapshot.selectedTurns.flatMap { turn ->
            turn.items.map { turn to it }
        }
    }
    val headerCount = (if (snapshot.selectedPlan != null) 1 else 0) +
        (if (snapshot.selectedGoal != null) 1 else 0)
    val lastTextLength = rows.lastOrNull()?.second?.let { it.text.length + it.detail.length } ?: 0
    LaunchedEffect(rows.size, lastTextLength, snapshot.selectedActiveTurnId) {
        if (rows.isNotEmpty()) {
            val target = (headerCount + rows.size - 1).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    if (snapshot.selectedThreadId == null && rows.isEmpty()) {
        WelcomePanel(snapshot, modifier)
        return
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 10.dp,
            bottom = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        snapshot.selectedPlan?.let { plan ->
            item(key = "plan-summary") { PlanSummaryCard(plan) }
        }
        snapshot.selectedGoal?.let { goal ->
            item(key = "goal-summary") { GoalSummaryCard(goal) }
        }
        items(rows, key = { (turn, item) -> "${turn.id}:${item.id}" }) { (_, item) ->
            ChatItemRow(item)
        }
        snapshot.selectedTurns.forEach { turn ->
            if (turn.error != null) {
                item(key = "error-${turn.id}") { TurnErrorCard(turn.error) }
            }
        }
        if (snapshot.selectedActiveTurnId != null) {
            item(key = "working") { WorkingIndicator() }
        }
        if (rows.isEmpty() && snapshot.selectedThreadId != null) {
            item(key = "empty") {
                Column(Modifier.padding(vertical = 48.dp)) {
                    Text("No messages yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Send a message to continue this thread.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CodexMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomePanel(snapshot: AppServerSnapshot, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(CodexLime, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "›_",
                fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.titleLarge.fontSize,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF12150F),
            )
        }
        Spacer(Modifier.height(22.dp))
        Text("What should Codex work on?", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Messages run through the app-server behind ${snapshot.deploymentUrl.ifBlank { "your deployment" }}. " +
                "The working directory is on the server, not on this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = CodexMuted,
        )
        Spacer(Modifier.height(20.dp))
        Surface(
            color = CodexSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CodexOutline),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Terminal, null, tint = CodexLime, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    snapshot.workspaceCwd.ifBlank { "Set a server working directory" },
                    style = MaterialTheme.typography.labelMedium,
                    color = CodexMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    cwd: String,
    sending: Boolean,
    activeTurnId: String?,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onCwdClick: () -> Unit,
) {
    Surface(
        color = Color(0xFF12150F),
        border = BorderStroke(1.dp, CodexOutline.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCwdClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                    Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(15.dp), tint = CodexMuted)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        cwd.ifBlank { "Server directory" },
                        style = MaterialTheme.typography.labelMedium,
                        color = CodexMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(240.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Codex…") },
                    minLines = 1,
                    maxLines = 6,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CodexLime.copy(alpha = 0.7f),
                        unfocusedBorderColor = CodexOutline,
                        focusedContainerColor = CodexSurface,
                        unfocusedContainerColor = CodexSurface,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                when {
                    activeTurnId != null -> {
                        FilledIconButton(
                            onClick = onInterrupt,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = CodexDanger.copy(alpha = 0.16f),
                                contentColor = CodexDanger,
                            ),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = "Stop turn")
                        }
                    }
                    sending -> {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = CodexLime,
                            )
                        }
                    }
                    else -> {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = value.isNotBlank(),
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = CodexLime,
                                contentColor = Color(0xFF12150F),
                                disabledContainerColor = CodexSurfaceHigh,
                                disabledContentColor = CodexMuted,
                            ),
                        ) {
                            Icon(Icons.Rounded.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}

private fun threadStatusColor(thread: ThreadInfo): Color = when {
    thread.activeFlags.isNotEmpty() -> CodexAmber
    thread.status == "active" -> CodexLime
    thread.status == "systemError" -> CodexDanger
    else -> CodexOutline
}

private fun threadStateLabel(thread: ThreadInfo): String = when {
    "waitingOnApproval" in thread.activeFlags -> "Waiting for approval"
    "waitingOnUserInput" in thread.activeFlags -> "Waiting for input"
    thread.status == "active" -> "Working"
    thread.status == "systemError" -> "System error"
    thread.status == "notLoaded" -> "Not loaded"
    else -> relativeTime(thread.updatedAt)
}

private fun relativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "Idle"
    val now = System.currentTimeMillis() / 1000
    val delta = (now - epochSeconds).coerceAtLeast(0)
    return when {
        delta < 60 -> "Just now"
        delta < 3_600 -> "${delta / 60}m ago"
        delta < 86_400 -> "${delta / 3_600}h ago"
        delta < 604_800 -> "${delta / 86_400}d ago"
        else -> "${delta / 604_800}w ago"
    }
}

private fun threadSubtitle(thread: ThreadInfo?, connection: String): String {
    if (connection != "connected") return "Disconnected from app-server"
    if (thread == null) return "New conversation"
    val flag = thread.activeFlags.firstOrNull()
    if (flag != null) {
        return when (flag) {
            "waitingOnApproval" -> "Waiting for approval"
            "waitingOnUserInput" -> "Waiting for your input"
            else -> flag
        }
    }
    return when (thread.status) {
        "active" -> "Codex is working"
        "systemError" -> "System error"
        else -> "app-server · ${thread.cwd}"
    }
}
