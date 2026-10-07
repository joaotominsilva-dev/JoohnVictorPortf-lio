package com.joohn.baixavideos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.DownloadTask
import com.joohn.baixavideos.engine.MediaSaver
import com.joohn.baixavideos.engine.TaskStatus
import com.joohn.baixavideos.ui.components.BrandPanel
import com.joohn.baixavideos.ui.theme.Brand
import com.joohn.baixavideos.ui.theme.MonoLabel
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
    val c = Brand.colors
    val context = LocalContext.current
    BrandPanel(modifier = modifier, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Thumbnail(task)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title ?: Links.hostOf(task.url) ?: task.url,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val meta = listOfNotNull(
                    task.platform.label.uppercase(),
                    task.uploader?.let { "@" + it.removePrefix("@") },
                    if (task.mode == DownloadMode.AUDIO) "MP3" else task.quality.label.uppercase(),
                ).joinToString(" · ")
                Text(meta, style = MonoLabel, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (task.status.isActive) {
                IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancelar download", tint = c.inkSoft)
                }
            } else {
                TaskMenu(task, onRemove = onRemove, onDeleteFiles = onDeleteFiles)
            }
        }
        Spacer(Modifier.height(10.dp))
        when (task.status) {
            TaskStatus.DONE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.green, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    val count = if (task.files.size > 1) "${task.files.size} ARQUIVOS · " else ""
                    Text(
                        "SALVO · $count${Formats.bytes(task.totalSize)} · ${MediaSaver.publicPath.uppercase()}",
                        style = MonoLabel,
                        color = c.green,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { task.files.firstOrNull()?.let { FileActions.open(context, it) } },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Abrir")
                    }
                    OutlinedButton(
                        onClick = { FileActions.share(context, task.files) },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Compartilhar")
                    }
                }
            }
            TaskStatus.FAILED -> {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = c.rec, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(task.error ?: "Falha no download.", style = MaterialTheme.typography.bodyMedium, color = c.rec)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onShowDetails) { Text("Detalhes") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onRetry, shape = RoundedCornerShape(6.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tentar de novo")
                    }
                }
            }
            TaskStatus.CANCELED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CANCELADO", style = MonoLabel.copy(fontWeight = FontWeight.Bold), color = c.inkSoft)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("Baixar de novo") }
                }
            }
            else -> ActiveStatus(task)
        }
    }
}

@Composable
private fun ActiveStatus(task: DownloadTask) {
    val c = Brand.colors
    val stage = (task.stage ?: if (task.status == TaskStatus.QUEUED) "Na fila" else "Preparando").uppercase()
    val numbers = listOfNotNull(
        Formats.percent(task.progress),
        Formats.speed(task.speedBps),
        Formats.timecode(task.etaSeconds)?.let { "ETA $it" },
    ).joinToString(" · ")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stage,
            style = MonoLabel.copy(fontWeight = FontWeight.Bold),
            color = c.blue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (numbers.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(numbers, style = MonoLabel, color = c.inkSoft, maxLines = 1)
        }
    }
    Spacer(Modifier.height(8.dp))
    val barModifier = Modifier
        .fillMaxWidth()
        .height(6.dp)
        .clip(RoundedCornerShape(3.dp))
    val progress = task.progress
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = barModifier, color = c.blue, trackColor = c.line)
    } else {
        LinearProgressIndicator(modifier = barModifier, color = c.blue, trackColor = c.line)
    }
}

@Composable
private fun Thumbnail(task: DownloadTask) {
    val c = Brand.colors
    Box(
        Modifier
            .size(width = 104.dp, height = 64.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(c.panelStrong),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (task.mode == DownloadMode.AUDIO) Icons.Filled.MusicNote else Icons.Filled.Movie,
            contentDescription = null,
            tint = c.inkSoft.copy(alpha = 0.6f),
        )
        if (task.thumbnail != null) {
            AsyncImage(
                model = task.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Text(
            task.platform.badge,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            style = MonoLabel,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        if (task.mode == DownloadMode.AUDIO) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(2.dp))
                    .padding(2.dp)
                    .size(12.dp),
            )
        }
    }
}

@Composable
private fun TaskMenu(task: DownloadTask, onRemove: () -> Unit, onDeleteFiles: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Mais opções", tint = Brand.colors.inkSoft)
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
