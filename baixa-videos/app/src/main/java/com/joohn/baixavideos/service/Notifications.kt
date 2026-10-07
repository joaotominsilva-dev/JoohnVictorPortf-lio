package com.joohn.baixavideos.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.joohn.baixavideos.MainActivity
import com.joohn.baixavideos.R
import com.joohn.baixavideos.engine.DownloadTask
import com.joohn.baixavideos.engine.TaskStatus
import com.joohn.baixavideos.util.AppVisibility
import com.joohn.baixavideos.util.Formats

object Notifications {
    const val ID_PROGRESS = 1001
    private const val CHANNEL_PROGRESS = "downloads_progress"
    private const val CHANNEL_RESULT = "downloads_result"
    private const val BRAND_BLUE = 0xFF1B4F8C.toInt()

    fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_PROGRESS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Downloads em andamento")
                    .setDescription("Progresso dos downloads enquanto o app está em segundo plano")
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_RESULT, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Downloads concluídos")
                    .setDescription("Aviso quando um download termina ou falha")
                    .build(),
            ),
        )
    }

    fun progress(context: Context, tasks: List<DownloadTask>): Notification {
        val active = tasks.filter { it.status.isActive }
        val first = active.firstOrNull()
        val title = when {
            active.size > 1 -> "Baixando ${active.size} itens"
            first?.title != null -> first.title
            else -> "Baixando vídeo"
        }
        val detail = first?.let { task ->
            listOfNotNull(task.stage, Formats.percent(task.progress), Formats.speed(task.speedBps)).joinToString(" · ")
        }.orEmpty()
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(BRAND_BLUE)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .apply {
                val progress = first?.progress
                if (progress != null) setProgress(1000, (progress * 1000).toInt(), false) else setProgress(0, 0, true)
            }
            .build()
    }

    /** Avisa o fim de um download, mas só quando o app não está na tela. */
    fun finished(context: Context, task: DownloadTask) {
        if (AppVisibility.isVisible) return
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(BRAND_BLUE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
        when (task.status) {
            TaskStatus.DONE -> {
                builder.setContentTitle("Download concluído")
                    .setContentText(task.title ?: task.files.firstOrNull()?.name ?: "Arquivo salvo")
                task.files.firstOrNull()?.let { file ->
                    val view = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse(file.uri), file.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    val pending = PendingIntent.getActivity(
                        context, task.id.hashCode(), view,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    builder.addAction(0, "Abrir", pending)
                }
            }
            TaskStatus.FAILED -> builder.setContentTitle("Falha no download")
                .setContentText(task.error ?: task.title ?: task.url)
                .setStyle(NotificationCompat.BigTextStyle().bigText(task.error ?: task.url))
            else -> return
        }
        notifySafely(context, task.id.hashCode(), builder.build())
    }

    @SuppressLint("MissingPermission")
    fun notifySafely(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
