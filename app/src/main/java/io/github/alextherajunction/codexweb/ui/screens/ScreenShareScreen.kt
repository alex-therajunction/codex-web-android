package io.github.alextherajunction.codexweb.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.JavaScriptReplyProxy
import io.github.alextherajunction.codexweb.R
import io.github.alextherajunction.codexweb.ScreenShareStatus
import io.github.alextherajunction.codexweb.ScreenShareUiState
import io.github.alextherajunction.codexweb.data.ScreenShareFrameMessage
import io.github.alextherajunction.codexweb.data.parseScreenShareFrameMessage
import io.github.alextherajunction.codexweb.ui.theme.CodexInk
import io.github.alextherajunction.codexweb.ui.theme.CodexLime
import io.github.alextherajunction.codexweb.ui.theme.CodexMuted
import io.github.alextherajunction.codexweb.ui.theme.CodexSurface
import io.github.alextherajunction.codexweb.ui.theme.CodexSurfaceHigh
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenShareScreen(
    state: ScreenShareUiState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    onTakeOver: () -> Unit,
    onFrameMessage: (String) -> Unit,
    onViewDetached: () -> Unit,
) {
    val context = LocalContext.current
    val inputMethodManager = remember(context) {
        context.getSystemService(InputMethodManager::class.java)
    }
    val currentOnFrameMessage by rememberUpdatedState(onFrameMessage)
    val currentOnViewDetached by rememberUpdatedState(onViewDetached)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var readyGeneration by remember { mutableIntStateOf(0) }
    var sentCommandKey by remember { mutableStateOf<String?>(null) }

    fun hideKeyboard() {
        val view = webView ?: return
        runCatching {
            view.evaluateJavascript(
                "window.codexScreenKeyboardHide && window.codexScreenKeyboardHide();",
                null,
            )
        }
        inputMethodManager?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun showKeyboard() {
        val view = webView ?: return
        view.requestFocus()
        view.evaluateJavascript(
            "window.codexScreenKeyboard && window.codexScreenKeyboard();",
        ) {
            inputMethodManager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun resetZoom() {
        val view = webView ?: return
        view.evaluateJavascript(
            "window.codexScreenResetZoom && window.codexScreenResetZoom();",
            null,
        )
    }

    fun disconnectAndClose() {
        runCatching {
            webView?.evaluateJavascript(
                "window.codexScreenDisconnect && window.codexScreenDisconnect();",
                null,
            )
        }
        hideKeyboard()
        onClose()
    }

    BackHandler(onBack = ::disconnectAndClose)

    LaunchedEffect(state.status) {
        if (state.status != ScreenShareStatus.CONNECTED) hideKeyboard()
    }

    LaunchedEffect(state.connectionRequestId, state.requestedWebSocketUrl, readyGeneration) {
        val view = webView ?: return@LaunchedEffect
        val url = state.requestedWebSocketUrl ?: return@LaunchedEffect
        if (readyGeneration == 0) return@LaunchedEffect
        val commandKey = "${state.connectionRequestId}:$readyGeneration"
        if (sentCommandKey == commandKey) return@LaunchedEffect
        val command = buildJsonObject {
            put("type", "screen-frame/connect")
            put("wsUrl", url)
        }
        view.evaluateJavascript("window.codexScreenConnect($command);", null)
        sentCommandKey = commandKey
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
                title = {
                    Column {
                        Text("Screen share", maxLines = 1)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .background(statusColor(state.status), CircleShape),
                            )
                            Text(
                                text = statusLabel(state),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.status == ScreenShareStatus.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    CodexMuted
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = ::showKeyboard,
                        enabled = state.status == ScreenShareStatus.CONNECTED,
                    ) {
                        Icon(Icons.Rounded.Keyboard, contentDescription = "Show keyboard")
                    }
                    IconButton(
                        onClick = ::resetZoom,
                        enabled = state.status == ScreenShareStatus.CONNECTED,
                    ) {
                        Icon(Icons.Rounded.FitScreen, contentDescription = "Reset zoom")
                    }
                    IconButton(
                        onClick = {
                            onRetry()
                            sentCommandKey = null
                        },
                        enabled = state.status != ScreenShareStatus.LOADING,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Retry screen share")
                    }
                    IconButton(onClick = ::disconnectAndClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close screen share")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF171717)),
        ) {
            if (state.webSocketUrl != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        val wrapper = context.resources.openRawResource(R.raw.screen_share_host)
                            .bufferedReader()
                            .use { it.readText() }
                        val hostUrl = "${state.deploymentUrl.trimEnd('/')}$HOST_PATH"
                        val host = runCatching { Uri.parse(state.deploymentUrl).host }.getOrNull()
                        val listener = object : WebViewCompat.WebMessageListener {
                            override fun onPostMessage(
                                view: WebView,
                                message: WebMessageCompat,
                                sourceOrigin: Uri,
                                isMainFrame: Boolean,
                                replyProxy: JavaScriptReplyProxy,
                            ) {
                                if (!isMainFrame) return
                                val raw = message.data ?: return
                                if (parseScreenShareFrameMessage(raw) == ScreenShareFrameMessage.Ready) {
                                    readyGeneration += 1
                                }
                                currentOnFrameMessage(raw)
                            }
                        }
                        WebView(viewContext).apply {
                            setBackgroundColor(Color(0xFF171717).toArgb())
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setOnTouchListener { view, event ->
                                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                    view.requestFocus()
                                }
                                false
                            }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.setSupportZoom(false)
                            settings.builtInZoomControls = false
                            settings.displayZoomControls = false
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): WebResourceResponse? {
                                    val target = request?.url?.toString() ?: return null
                                    if (request.method != "GET" || target != hostUrl) return null
                                    return WebResourceResponse(
                                        "text/html",
                                        "UTF-8",
                                        ByteArrayInputStream(wrapper.toByteArray(Charsets.UTF_8)),
                                    )
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val target = request?.url ?: return false
                                    val targetHost = target.host.orEmpty()
                                    val local = target.scheme == "http" || target.scheme == "https"
                                    return if (local && (host.isNullOrBlank() || targetHost == host)) {
                                        false
                                    } else {
                                        runCatching {
                                            viewContext.startActivity(Intent(Intent.ACTION_VIEW, target))
                                        }
                                        true
                                    }
                                }
                            }
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                                WebViewCompat.addWebMessageListener(
                                    this,
                                    MESSAGE_OBJECT_NAME,
                                    setOf(state.origin),
                                    listener,
                                )
                                loadUrl(hostUrl)
                            } else {
                                currentOnFrameMessage(WEB_MESSAGE_UNSUPPORTED)
                            }
                            webView = this
                            post { requestFocus() }
                        }
                    },
                    onRelease = { view ->
                        hideKeyboard()
                        runCatching {
                            view.evaluateJavascript(
                                "window.codexScreenDisconnect && window.codexScreenDisconnect();",
                                null,
                            )
                        }
                        runCatching { WebViewCompat.removeWebMessageListener(view, MESSAGE_OBJECT_NAME) }
                        view.stopLoading()
                        view.destroy()
                        if (webView === view) webView = null
                        currentOnViewDetached()
                    },
                )
            }

            Overlay(state, onRetry, onReconnect, onTakeOver)
        }
    }
}

@Composable
private fun Overlay(
    state: ScreenShareUiState,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    onTakeOver: () -> Unit,
) {
    val show = state.status != ScreenShareStatus.CONNECTED
    if (!show) return
    val dark = state.status != ScreenShareStatus.CONNECTING
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        color = Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (dark) CodexSurfaceHigh.copy(alpha = 0.97f) else CodexSurface.copy(alpha = 0.92f),
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (state.status) {
                        ScreenShareStatus.LOADING,
                        ScreenShareStatus.CONNECTING,
                        -> {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(
                                if (state.status == ScreenShareStatus.LOADING) {
                                    "Checking screen-share availability..."
                                } else {
                                    "Connecting to host desktop..."
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        ScreenShareStatus.DISABLED -> MessagePanel(
                            title = "Screen share is disabled",
                            message = state.reason.orEmpty(),
                            action = "Retry",
                            onAction = onRetry,
                        )
                        ScreenShareStatus.UNSUPPORTED -> MessagePanel(
                            title = "Screen share is unsupported",
                            message = state.reason.orEmpty(),
                            action = "Retry",
                            onAction = onRetry,
                        )
                        ScreenShareStatus.ALREADY_ACTIVE -> MessagePanel(
                            title = "Screen share is already active in another client",
                            message = state.reason.orEmpty(),
                            action = "Take over",
                            onAction = onTakeOver,
                            prominent = true,
                        )
                        ScreenShareStatus.TAKEN_OVER -> MessagePanel(
                            title = "Screen share was taken over by another client",
                            message = state.reason.orEmpty(),
                            action = "Reconnect",
                            onAction = onReconnect,
                        )
                        ScreenShareStatus.DISCONNECTED -> MessagePanel(
                            title = "Disconnected",
                            message = state.reason.orEmpty(),
                            action = "Reconnect",
                            onAction = onReconnect,
                        )
                        ScreenShareStatus.ERROR -> MessagePanel(
                            title = "Screen share failed",
                            message = state.reason.orEmpty(),
                            action = "Retry",
                            onAction = onRetry,
                        )
                        ScreenShareStatus.CONNECTED -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagePanel(
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit,
    prominent: Boolean = false,
) {
    Icon(Icons.Rounded.DesktopWindows, contentDescription = null, tint = CodexLime)
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (message.isNotBlank()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (prominent) MaterialTheme.colorScheme.error else CodexMuted,
        )
    }
    Button(onClick = onAction) { Text(action) }
}

private fun statusLabel(state: ScreenShareUiState): String = when (state.status) {
    ScreenShareStatus.LOADING -> "Checking availability..."
    ScreenShareStatus.CONNECTING -> "Connecting to host desktop..."
    ScreenShareStatus.CONNECTED -> {
        if (state.width != null && state.height != null) {
            "${state.width} × ${state.height}"
        } else {
            "Connected"
        }
    }
    ScreenShareStatus.ALREADY_ACTIVE -> "Already active in another client"
    ScreenShareStatus.TAKEN_OVER -> "Taken over by another client"
    ScreenShareStatus.DISABLED -> "Disabled on server"
    ScreenShareStatus.UNSUPPORTED -> "Unsupported on server"
    ScreenShareStatus.DISCONNECTED -> state.reason ?: "Disconnected"
    ScreenShareStatus.ERROR -> state.reason ?: "Screen share failed"
}

private fun statusColor(status: ScreenShareStatus): Color = when (status) {
    ScreenShareStatus.CONNECTED -> CodexLime
    ScreenShareStatus.CONNECTING,
    ScreenShareStatus.LOADING,
    -> Color(0xFFE5C07B)
    ScreenShareStatus.ERROR -> Color(0xFFE06C75)
    else -> Color(0xFF6F7782)
}

private const val MESSAGE_OBJECT_NAME = "codexScreenShare"
private const val HOST_PATH = "/__codex_android/screen-share-host"
private const val WEB_MESSAGE_UNSUPPORTED =
    "{\"type\":\"screen-frame/error\",\"message\":\"This Android WebView does not support screen sharing. Update Android System WebView and try again.\"}"
