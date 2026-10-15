package com.joohn.baixavideos

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.joohn.baixavideos.ui.DownloadDraft
import com.joohn.baixavideos.ui.PendingStart
import com.joohn.baixavideos.ui.PendingStartHolder
import com.joohn.baixavideos.ui.ShareSheet
import com.joohn.baixavideos.ui.theme.CharisTheme
import com.joohn.baixavideos.util.AppVisibility
import com.joohn.baixavideos.util.Links

class ShareViewModel(app: Application) : AndroidViewModel(app), PendingStartHolder {
    val draft = DownloadDraft(app, viewModelScope)
    var sharedText by mutableStateOf<String?>(null)
        private set
    override var pendingStart: PendingStart? = null

    fun onShared(text: String?) {
        sharedText = text
        draft.updateText(Links.extractUrl(text) ?: "")
    }
}

/**
 * Recebe o "Compartilhar" de outros apps e abre, por cima deles, a janela com as opções de
 * download (formato, qualidade e corte). Nada começa sem a pessoa escolher.
 */
class ShareActivity : ComponentActivity() {
    val vm: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.onShared(sharedText(intent))
        setContent {
            CharisTheme {
                ShareSheet(
                    draft = vm.draft,
                    holder = vm,
                    sharedText = vm.sharedText,
                    onClose = ::finish,
                    onStarted = {
                        Toast.makeText(this, "Baixando em segundo plano. Acompanhe no Charis Downloader.", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onOpenApp = ::openApp,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.onShared(sharedText(intent))
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
    }

    override fun onStop() {
        AppVisibility.onStop()
        super.onStop()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        vm.draft.link?.let { intent.putExtra(MainActivity.EXTRA_URL, it) }
        startActivity(intent)
        finish()
    }

    private fun sharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    }
}
