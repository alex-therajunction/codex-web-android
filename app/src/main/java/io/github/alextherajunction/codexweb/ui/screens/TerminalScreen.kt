package io.github.alextherajunction.codexweb.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alextherajunction.codexweb.TerminalStatus
import io.github.alextherajunction.codexweb.TerminalUiState
import io.github.alextherajunction.codexweb.ui.theme.CodexInk
import io.github.alextherajunction.codexweb.ui.theme.CodexLime
import io.github.alextherajunction.codexweb.ui.theme.CodexMuted
import io.github.alextherajunction.codexweb.ui.theme.CodexSurface
import io.github.alextherajunction.codexweb.ui.theme.CodexSurfaceHigh
import org.connectbot.terminal.Terminal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    terminal: TerminalUiState,
    onClose: () -> Unit,
    onRestart: () -> Unit,
    onSendInput: (ByteArray) -> Unit,
) {
    var keyboardEnabled by remember(terminal.processHandle) { mutableStateOf(false) }
    val keyboardFocus = remember { FocusRequester() }
    val active = terminal.status == TerminalStatus.STARTING || terminal.status == TerminalStatus.RUNNING

    BackHandler(onBack = onClose)

    LaunchedEffect(terminal.status) {
        if (terminal.status == TerminalStatus.EXITED || terminal.status == TerminalStatus.ERROR) {
            keyboardEnabled = false
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = CodexInk,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF12150F),
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close terminal")
                    }
                },
                title = {
                    Column {
                        Text("Terminal", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${terminal.cwd} · ${terminalStatusLabel(terminal)}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (terminal.status == TerminalStatus.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                CodexMuted
                            },
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            keyboardEnabled = !keyboardEnabled
                            if (keyboardEnabled) keyboardFocus.requestFocus()
                        },
                        enabled = active,
                    ) {
                        Icon(
                            Icons.Rounded.Keyboard,
                            contentDescription = if (keyboardEnabled) "Hide keyboard" else "Show keyboard",
                            tint = if (keyboardEnabled) CodexLime else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = onRestart) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Restart terminal")
                    }
                },
            )
        },
        bottomBar = {
            TerminalKeyBar(enabled = active, onSendInput = onSendInput)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(CodexInk),
        ) {
            terminal.error?.let { message ->
                Surface(color = Color(0xFF3A201E), contentColor = MaterialTheme.colorScheme.error) {
                    Text(
                        text = message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Terminal(
                    terminalEmulator = terminal.emulator,
                    modifier = Modifier.fillMaxSize(),
                    initialFontSize = 12.sp,
                    backgroundColor = CodexInk,
                    foregroundColor = Color(0xFFF0F3EA),
                    keyboardEnabled = keyboardEnabled,
                    showSoftKeyboard = keyboardEnabled,
                    focusRequester = keyboardFocus,
                    onTerminalTap = {
                        if (active) {
                            keyboardEnabled = true
                            keyboardFocus.requestFocus()
                        }
                    },
                )
                if (terminal.status == TerminalStatus.STARTING) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        color = CodexSurface.copy(alpha = 0.94f),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Starting shell in ${terminal.cwd}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (terminal.status == TerminalStatus.EXITED) {
                    TerminalExitOverlay(
                        exitCode = terminal.exitCode,
                        onRestart = onRestart,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalExitOverlay(
    exitCode: Int?,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CodexSurfaceHigh.copy(alpha = 0.96f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Process exited${exitCode?.let { " with code $it" }.orEmpty()}",
                color = CodexMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRestart) { Text("Restart") }
        }
    }
}

@Composable
private fun TerminalKeyBar(
    enabled: Boolean,
    onSendInput: (ByteArray) -> Unit,
) {
    Surface(color = CodexSurface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TerminalKey("Esc", byteArrayOf(0x1B), enabled, onSendInput)
            TerminalKey("Tab", byteArrayOf(0x09), enabled, onSendInput)
            TerminalKey("Ctrl+C", byteArrayOf(0x03), enabled, onSendInput)
            TerminalKey("Ctrl+D", byteArrayOf(0x04), enabled, onSendInput)
            TerminalKey("Ctrl+L", byteArrayOf(0x0C), enabled, onSendInput)
            TerminalKey("←", byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()), enabled, onSendInput)
            TerminalKey("↑", byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()), enabled, onSendInput)
            TerminalKey("↓", byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()), enabled, onSendInput)
            TerminalKey("→", byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte()), enabled, onSendInput)
        }
    }
}

@Composable
private fun TerminalKey(
    label: String,
    bytes: ByteArray,
    enabled: Boolean,
    onSendInput: (ByteArray) -> Unit,
) {
    TextButton(
        onClick = { onSendInput(bytes) },
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun terminalStatusLabel(terminal: TerminalUiState): String = when (terminal.status) {
    TerminalStatus.STARTING -> "starting PTY"
    TerminalStatus.RUNNING -> "PTY running"
    TerminalStatus.EXITED -> "exited${terminal.exitCode?.let { " ($it)" }.orEmpty()}"
    TerminalStatus.ERROR -> "terminal error"
}
