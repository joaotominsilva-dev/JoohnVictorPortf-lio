package com.joohn.baixavideos.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.joohn.baixavideos.data.Prefs
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.DownloadTask
import com.joohn.baixavideos.engine.Downloads
import com.joohn.baixavideos.engine.MediaSaver
import com.joohn.baixavideos.engine.Reframe
import com.joohn.baixavideos.engine.Trim
import com.joohn.baixavideos.engine.VideoInfo
import com.joohn.baixavideos.engine.VideoQuality

/** Download pronto para entrar na fila (pode esperar a resposta de um pedido de permissão). */
data class PendingStart(
    val url: String,
    val mode: DownloadMode,
    val quality: VideoQuality,
    val trim: Trim?,
    val info: VideoInfo?,
    val reframe: Reframe? = null,
) {
    fun enqueue(context: Context): DownloadTask = Downloads.enqueue(context, url, mode, quality, trim, info, reframe)
}

/** Guarda o download enquanto o pedido de permissão está aberto (sobrevive à rotação). */
interface PendingStartHolder {
    var pendingStart: PendingStart?
}

/** Pede as permissões que faltam (notificação; escrita no Android 9 ou anterior) e então baixa. */
@Composable
fun rememberDownloadStarter(holder: PendingStartHolder, onStarted: (DownloadTask) -> Unit): (PendingStart) -> Unit {
    val context = LocalContext.current
    val started by rememberUpdatedState(onStarted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Prefs.askedNotificationPermission = true
        holder.pendingStart?.let { started(it.enqueue(context)) }
        holder.pendingStart = null
    }
    return remember(context, launcher, holder) {
        { start: PendingStart ->
            val needed = buildList {
                if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotificationPermission &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (MediaSaver.needsLegacyPermission(context)) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (needed.isEmpty()) {
                started(start.enqueue(context))
            } else {
                holder.pendingStart = start
                launcher.launch(needed.toTypedArray())
            }
        }
    }
}
