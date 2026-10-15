package com.joohn.baixavideos

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.joohn.baixavideos.data.Prefs
import com.joohn.baixavideos.ui.DownloadDraft
import com.joohn.baixavideos.ui.PendingStart
import com.joohn.baixavideos.ui.PendingStartHolder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class Tab { HOME, DOWNLOADS, SETTINGS }

class MainViewModel(app: Application) : AndroidViewModel(app), PendingStartHolder {
    val draft = DownloadDraft(app, viewModelScope)
    var tab by mutableStateOf(Tab.HOME)
    var showWelcome by mutableStateOf(!Prefs.welcomeSeen)
        private set
    override var pendingStart: PendingStart? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun finishWelcome() {
        Prefs.welcomeSeen = true
        showWelcome = false
    }

    /** Link vindo da janela de compartilhar ("Abrir o Charis Downloader"). */
    fun openWith(url: String) {
        if (showWelcome) finishWelcome()
        draft.updateText(url)
        tab = Tab.HOME
    }

    fun showDownloads() {
        if (showWelcome) finishWelcome()
        tab = Tab.DOWNLOADS
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }
}
