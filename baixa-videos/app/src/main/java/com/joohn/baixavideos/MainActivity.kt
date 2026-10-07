package com.joohn.baixavideos

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.joohn.baixavideos.ui.HomeScreen
import com.joohn.baixavideos.ui.SettingsScreen
import com.joohn.baixavideos.ui.theme.BaixaVideosTheme
import com.joohn.baixavideos.util.AppVisibility

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // A faixa azul do topo fica atrás da barra de status, então os ícones são sempre claros.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            BaixaVideosTheme {
                BackHandler(enabled = vm.screen != Screen.HOME) { vm.screen = Screen.HOME }
                when (vm.screen) {
                    Screen.HOME -> HomeScreen(vm, onOpenSettings = { vm.screen = Screen.SETTINGS })
                    Screen.SETTINGS -> SettingsScreen(onBack = { vm.screen = Screen.HOME })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
    }

    override fun onStop() {
        AppVisibility.onStop()
        super.onStop()
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        vm.onSharedText(text)
    }
}
