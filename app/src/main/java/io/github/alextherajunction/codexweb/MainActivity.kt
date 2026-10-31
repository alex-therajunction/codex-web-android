package io.github.alextherajunction.codexweb

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.alextherajunction.codexweb.ui.CodexWebApp
import io.github.alextherajunction.codexweb.ui.theme.CodexTheme

class MainActivity : ComponentActivity() {
    private val viewModel: CodexWebViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            CodexTheme {
                CodexWebApp(viewModel)
            }
        }
    }
}
