package com.joohn.baixavideos.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.joohn.baixavideos.engine.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mantém o app vivo (e a CPU acordada) enquanto há downloads, com a notificação de progresso.
 * Os downloads em si rodam em [Downloads]; o serviço só acompanha e encerra quando a fila esvazia.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0
    private var lastNotifyAt = 0L
    private var lastActiveCount = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_PROGRESS, Notifications.progress(this, Downloads.tasks.value), type,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível entrar em primeiro plano", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (watcher == null) watcher = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        Downloads.tasks.collect { tasks ->
            val active = tasks.count { it.status.isActive }
            if (active == 0) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelfResult(lastStartId)
                return@collect
            }
            val now = System.currentTimeMillis()
            if (active != lastActiveCount || now - lastNotifyAt >= 1_000) {
                lastActiveCount = active
                lastNotifyAt = now
                Notifications.notifySafely(this, Notifications.ID_PROGRESS, Notifications.progress(this, tasks))
            }
        }
    }

    // Android 15+: serviços "dataSync" têm limite de tempo; ao estourar, o sistema pede para parar.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BaixaVideos:download").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    companion object {
        private const val TAG = "DownloadService"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                // Ex.: app em segundo plano no Android 12+. O download continua mesmo assim.
                Log.w(TAG, "Serviço de download não iniciado", e)
            }
        }
    }
}
