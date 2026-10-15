package com.joohn.baixavideos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.DownloadTask
import com.joohn.baixavideos.engine.TaskStatus
import com.joohn.baixavideos.ui.components.CreamCard
import com.joohn.baixavideos.ui.components.SmallPill
import com.joohn.baixavideos.ui.components.StatusChip
import com.joohn.baixavideos.ui.components.Thumb
import com.joohn.baixavideos.ui.theme.Charis
import com.joohn.baixavideos.util.Formats
import com.joohn.baixavideos.util.Links

@Composable
fun TaskCard(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFiles: () -> Unit,
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    CreamCard(modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(
                url = task.thumbnail,
                badge = task.platform.badge,
                audio = task.mode == DownloadMode.AUDIO,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title ?: Links.hostOf(task.url) ?: task.url,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    describe(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = Charis.InkSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusFor(task)
            if (!task.status.isActive) TaskMenu(task, onRemove = onRemove, onDeleteFiles = onDeleteFiles)
        }
        when (task.status) {
            TaskStatus.DONE -> {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallPill(
                        "Abrir",
                        onClick = { task.files.firstOrNull()?.let { FileActions.open(context, it) } },
                        container = Charis.SageDeep,
                        icon = Icons.Filled.PlayArrow,
                        modifier = Modifier.weight(1f),
                    )
                    SmallPill(
                        "Compartilhar",
                        onClick = { FileActions.share(context, task.files) },
                        filled = false,
                        icon = Icons.Filled.Share,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            TaskStatus.FAILED -> {
                Spacer(Modifier.height(8.dp))
                Text(task.error ?: "Falha no download.", style = MaterialTheme.typography.bodySmall, color = Charis.Danger)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallPill("Detalhes", onClick = onShowDetails, filled = false, modifier = Modifier.weight(1f))
                    SmallPill("Tentar de novo", onClick = onRetry, icon = Icons.Filled.Refresh, modifier = Modifier.weight(1f))
                }
            }
            TaskStatus.CANCELED -> {
                Spacer(Modifier.height(8.dp))
                SmallPill("Baixar de novo", onClick = onRetry, filled = false, modifier = Modifier.fillMaxWidth())
            }
            else -> ActiveStatus(task, onCancel)
        }
    }
}

private fun describe(task: DownloadTask): String = listOfNotNull(
    if (task.mode == DownloadMode.AUDIO) "MP3" else "MP4 · ${task.quality.label}",
    task.trim?.let { "trecho ${Formats.clock(it.startSec)} a ${Formats.clock(it.endSec)}" },
    task.totalSize.takeIf { task.status == TaskStatus.DONE && it > 0 }?.let(Formats::bytes),
).joinToString(" · ")

@Composable
private fun StatusFor(task: DownloadTask) {
    when (task.status) {
        TaskStatus.DONE -> StatusChip("Salvo", Charis.Charcoal, Charis.Cream)
        TaskStatus.FAILED -> StatusChip("Falhou", Charis.DangerSoft, Charis.Danger)
        TaskStatus.CANCELED -> StatusChip("Cancelado", Charis.Cream2, Charis.InkSoft)
        else -> Formats.percent(task.progress)?.let { StatusChip(it, Charis.SageLight, Charis.Ink) }
    }
}

@Composable
private fun ActiveStatus(task: DownloadTask, onCancel: () -> Unit) {
    val stage = task.stage ?: if (task.status == TaskStatus.QUEUED) "Na fila" else "Preparando"
    val numbers = listOfNotNull(
        Formats.speed(task.speedBps),
        Formats.timecode(task.etaSeconds)?.let { "faltam $it" },
    ).joinToString(" · ")
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stage, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (numbers.isNotEmpty()) {
                Text(numbers, style = MaterialTheme.typography.bodySmall, color = Charis.InkSoft, maxLines = 1)
            }
        }
        SmallPill("Cancelar", onClick = onCancel, filled = false)
    }
    Spacer(Modifier.height(8.dp))
    val bar = Modifier
        .fillMaxWidth()
        .height(6.dp)
        .clip(RoundedCornerShape(3.dp))
    val progress = task.progress
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = bar,
            color = Charis.SageDeep,
            trackColor = Charis.Cream2,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(modifier = bar, color = Charis.SageDeep, trackColor = Charis.Cream2, gapSize = 0.dp)
    }
}

@Composable
private fun TaskMenu(task: DownloadTask, onRemove: () -> Unit, onDeleteFiles: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Mais opções", tint = Charis.InkSoft)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Copiar link") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = {
                    open = false
                    FileActions.copyText(context, "Link", task.url)
                },
            )
            DropdownMenuItem(
                text = { Text("Abrir link original") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                onClick = {
                    open = false
                    FileActions.openLink(context, task.url)
                },
            )
            DropdownMenuItem(
                text = { Text("Remover da lista") },
                leadingIcon = { Icon(Icons.Filled.Clear, contentDescription = null) },
                onClick = {
                    open = false
                    onRemove()
                },
            )
            if (task.status == TaskStatus.DONE) {
                DropdownMenuItem(
                    text = { Text("Apagar do aparelho") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        open = false
                        onDeleteFiles()
                    },
                )
            }
        }
    }
}
