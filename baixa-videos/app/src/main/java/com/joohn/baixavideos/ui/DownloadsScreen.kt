package com.joohn.baixavideos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joohn.baixavideos.engine.DownloadTask
import com.joohn.baixavideos.engine.Downloads
import com.joohn.baixavideos.engine.MediaSaver
import com.joohn.baixavideos.engine.TaskStatus
import com.joohn.baixavideos.ui.components.Headline
import com.joohn.baixavideos.ui.components.PillTabs
import com.joohn.baixavideos.ui.components.RoundIconButton
import com.joohn.baixavideos.ui.components.TopBar
import com.joohn.baixavideos.ui.components.hatched
import com.joohn.baixavideos.ui.theme.Charis

private enum class Filter(val label: String) {
    ALL("Todos"), RUNNING("Baixando"), SAVED("Salvos");

    fun matches(task: DownloadTask) = when (this) {
        ALL -> true
        RUNNING -> task.status.isActive
        SAVED -> task.status == TaskStatus.DONE
    }
}

@Composable
fun DownloadsScreen(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val tasks by Downloads.tasks.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var details by remember { mutableStateOf<DownloadTask?>(null) }
    var confirmDelete by remember { mutableStateOf<DownloadTask?>(null) }

    val running = tasks.count { it.status.isActive }
    val saved = tasks.count { it.status == TaskStatus.DONE }
    val shown = tasks.filter(filter::matches)
    val summary = when {
        running > 0 -> "$running baixando · $saved salvos em ${MediaSaver.publicPath}"
        saved > 0 -> "$saved salvos em ${MediaSaver.publicPath}"
        else -> "Tudo fica em ${MediaSaver.publicPath} e aparece na galeria."
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .splitBackground(),
    ) {
        item(key = "header") {
            Column(Modifier.background(Charis.Charcoal)) {
                Spacer(Modifier.statusBarsPadding())
                TopBar("Downloads")
                Headline("Seus downloads", summary, Modifier.padding(top = 6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillTabs(
                        Filter.entries.map { it to it.label },
                        filter,
                        { filter = it },
                        onDark = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (tasks.any { !it.status.isActive }) {
                        RoundIconButton(Icons.Filled.DeleteSweep, "Limpar lista", onClick = { Downloads.clearFinished() })
                    }
                }
            }
        }
        item(key = "well-top") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Charis.SageLight),
            )
        }
        items(shown, key = { it.id }) { task ->
            Box(
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .background(Charis.SageLight)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                TaskCard(
                    task = task,
                    onCancel = { Downloads.cancel(task.id) },
                    onRetry = { Downloads.retry(context, task.id) },
                    onRemove = { Downloads.remove(task.id) },
                    onDeleteFiles = { confirmDelete = task },
                    onShowDetails = { details = task },
                )
            }
        }
        // Espaços vazios listrados, como os horários livres da referência.
        val empty = (3 - shown.size).coerceAtLeast(0)
        items(empty, key = { "empty-$it" }) { index ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Charis.SageLight)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 76.dp)
                        .hatched(corner = 20.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (index == 0 && shown.isEmpty()) emptyText(filter) else "Sem downloads",
                        style = MaterialTheme.typography.bodySmall,
                        color = Charis.InkSoft,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        item(key = "bottom") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Charis.SageLight),
            ) { BottomSpace() }
        }
    }

    details?.let { task -> ErrorDetailsDialog(task, onDismiss = { details = null }) }
    confirmDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Apagar do aparelho?") },
            text = { Text("O arquivo sai da pasta ${MediaSaver.publicPath} e da galeria. Isso não pode ser desfeito.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (!Downloads.deleteFiles(task.id)) {
                        onMessage("Não consegui apagar. Apague pelo app de arquivos ou pela galeria.")
                    }
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }
}

private fun emptyText(filter: Filter) = when (filter) {
    Filter.ALL -> "Nada por aqui ainda. Cole um link no Início ou compartilhe um vídeo de outro app."
    Filter.RUNNING -> "Nenhum download em andamento."
    Filter.SAVED -> "Nenhum arquivo salvo ainda."
}

@Composable
private fun ErrorDetailsDialog(task: DownloadTask, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detalhes do erro") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(task.error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    task.errorDetails ?: "Sem detalhes.",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
                Spacer(Modifier.height(12.dp))
                Text(task.url, style = MaterialTheme.typography.bodySmall, color = Charis.Sage)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = {
            TextButton(onClick = {
                FileActions.copyText(context, "Erro", "${task.url}\n\n${task.errorDetails.orEmpty()}")
            }) { Text("Copiar") }
        },
    )
}
