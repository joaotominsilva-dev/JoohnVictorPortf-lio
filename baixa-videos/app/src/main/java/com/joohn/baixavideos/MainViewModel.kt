package com.joohn.baixavideos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.joohn.baixavideos.data.Prefs
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.VideoQuality
import com.joohn.baixavideos.util.Links
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class Screen { HOME, SETTINGS }

/** Download aguardando a resposta de um pedido de permissão. */
data class PendingStart(val url: String, val mode: DownloadMode, val quality: VideoQuality)

class MainViewModel : ViewModel() {
    var url by mutableStateOf("")
    var screen by mutableStateOf(Screen.HOME)
    var pendingStart: PendingStart? = null

    /** Muda de valor quando um link compartilhado deve começar a baixar sozinho. */
    var autoStartToken by mutableIntStateOf(0)
        private set
    var handledAutoStartToken = 0

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun onSharedText(text: String?) {
        val link = Links.extractUrl(text)
        if (link == null) {
            _messages.tryEmit("Não encontrei um link no conteúdo compartilhado.")
            return
        }
        url = link
        screen = Screen.HOME
        if (Prefs.settings.value.autoStartOnShare) autoStartToken++
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }
}
