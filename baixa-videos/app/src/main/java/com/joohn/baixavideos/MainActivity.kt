package com.joohn.baixavideos

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.joohn.baixavideos.ui.MainScreen
import com.joohn.baixavideos.ui.WelcomeScreen
import com.joohn.baixavideos.ui.theme.CharisTheme
import com.joohn.baixavideos.util.AppVisibility

class MainActivity : ComponentActivity() {
    val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            CharisTheme {
                // Ícones da barra de status e de navegação acompanham o fundo de cada tela:
                // boas-vindas é creme; Início e Downloads terminam no bloco sálvia; Ajustes é escuro.
                val lightTop = vm.showWelcome
                val lightBottom = vm.showWelcome || vm.tab != Tab.SETTINGS
                LaunchedEffect(lightTop, lightBottom) {
                    enableEdgeToEdge(
                        statusBarStyle = style(lightTop),
                        navigationBarStyle = style(lightBottom),
                    )
                }
                if (vm.showWelcome) WelcomeScreen(onStart = vm::finishWelcome) else MainScreen(vm)
            }
        }
    }

    private fun style(light: Boolean) =
        if (light) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT) else SystemBarStyle.dark(Color.TRANSPARENT)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
    }

    override fun onStop() {
        AppVisibility.onStop()
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_URL)?.let { vm.openWith(it) }
        if (intent.getBooleanExtra(EXTRA_SHOW_DOWNLOADS, false)) vm.showDownloads()
    }

    companion object {
        const val EXTRA_URL = "com.joohn.baixavideos.extra.URL"
        const val EXTRA_SHOW_DOWNLOADS = "com.joohn.baixavideos.extra.SHOW_DOWNLOADS"
    }
}
