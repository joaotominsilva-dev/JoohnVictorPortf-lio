package com.joohn.baixavideos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joohn.baixavideos.MainViewModel
import com.joohn.baixavideos.PendingStart
import com.joohn.baixavideos.data.Prefs
import com.joohn.baixavideos.data.Settings
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.DownloadTask
import com.joohn.baixavideos.engine.Downloads
import com.joohn.baixavideos.engine.Engine
import com.joohn.baixavideos.engine.MediaSaver
import com.joohn.baixavideos.engine.VideoQuality
import com.joohn.baixavideos.ui.components.BrandHeader
import com.joohn.baixavideos.ui.components.BrandPanel
import com.joohn.baixavideos.ui.components.SectionLabel
import com.joohn.baixavideos.ui.components.TopStrip
import com.joohn.baixavideos.ui.components.viewfinder
import com.joohn.baixavideos.ui.theme.ArchivoExpanded
import com.joohn.baixavideos.ui.theme.Brand
import com.joohn.baixavideos.ui.theme.MonoLabel
import com.joohn.baixavideos.util.Links
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val c = Brand.colors
    val tasks by Downloads.tasks.collectAsStateWithLifecycle()
    val engine by Engine.state.collectAsStateWithLifecycle()
    val update by Engine.update.collectAsStateWithLifecycle()
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var details by remember { mutableStateOf<DownloadTask?>(null) }
    var confirmDelete by remember { mutableStateOf<DownloadTask?>(null) }
    val recording = tasks.any { it.status.isActive }
    val startDownload = rememberDownloadStarter(vm)

    fun submit() {
        val link = Links.extractUrl(vm.url)
        if (link == null) {
            scope.launch { snackbar.showSnackbar("Cole um link válido, começando com https://") }
            return
        }
        focus.clearFocus()
        val current = Prefs.settings.value
        startDownload(PendingStart(link, current.mode, current.quality))
        vm.url = ""
    }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm.autoStartToken) {
        if (vm.autoStartToken > vm.handledAutoStartToken) {
            vm.handledAutoStartToken = vm.autoStartToken
            submit()
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(c.paper)) {
                TopStrip(recording = recording)
                BrandHeader(
                    title = "BAIXA VÍDEOS",
                    subtitle = "INSTAGRAM · YOUTUBE · TIKTOK",
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Ajustes", tint = c.ink)
                        }
                    },
                )
                HorizontalDivider(color = c.line)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = c.paper,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "input") {
                InputPanel(
                    url = vm.url,
                    onUrlChange = { vm.url = it },
                    settings = settings,
                    onSubmit = ::submit,
                )
            }
            item(key = "engine") {
                EngineStatus(engine = engine, update = update, onRetry = { Engine.start(context) })
            }
            item(key = "section") {
                SectionLabel("DOWNLOADS", modifier = Modifier.padding(top = 8.dp)) {
                    if (tasks.any { !it.status.isActive }) {
                        TextButton(onClick = { Downloads.clearFinished() }) { Text("Limpar lista") }
                    }
                }
            }
            if (tasks.isEmpty()) {
                item(key = "empty") { EmptyState() }
            }
            items(tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onCancel = { Downloads.cancel(task.id) },
                    onRetry = { Downloads.retry(context, task.id) },
                    onRemove = { Downloads.remove(task.id) },
                    onDeleteFiles = { confirmDelete = task },
                    onShowDetails = { details = task },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    details?.let { task -> ErrorDetailsDialog(task, onDismiss = { details = null }) }
    confirmDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Apagar do aparelho?") },
            text = { Text("O arquivo sai da pasta Download/BaixaVideos e da galeria. Isso não pode ser desfeito.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (!Downloads.deleteFiles(task.id)) {
                        scope.launch { snackbar.showSnackbar("Não consegui apagar. Apague pelo app de arquivos ou pela galeria.") }
                    }
                }) { Text("Apagar", color = c.rec) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }
}

/** Pede as permissões que faltam (notificação; escrita no Android 9 ou anterior) e então baixa. */
@Composable
private fun rememberDownloadStarter(vm: MainViewModel): (PendingStart) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Prefs.askedNotificationPermission = true
        vm.pendingStart?.let { Downloads.enqueue(context, it.url, it.mode, it.quality) }
        vm.pendingStart = null
    }
    return remember<(PendingStart) -> Unit>(context, launcher, vm) {
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
                Downloads.enqueue(context, start.url, start.mode, start.quality)
            } else {
                vm.pendingStart = start
                launcher.launch(needed.toTypedArray())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputPanel(url: String, onUrlChange: (String) -> Unit, settings: Settings, onSubmit: () -> Unit) {
    val c = Brand.colors
    val context = LocalContext.current
    val detected = Links.extractUrl(url)
    BrandPanel(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .viewfinder(c.ink.copy(alpha = 0.75f), length = 14.dp, stroke = 2.dp)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LINK DO VÍDEO", style = MonoLabel.copy(fontWeight = FontWeight.Bold), color = c.inkSoft)
                Spacer(Modifier.weight(1f))
                if (detected != null) {
                    Text(
                        "● ${Links.platformOf(detected).label.uppercase()}",
                        style = MonoLabel.copy(fontWeight = FontWeight.Bold),
                        color = c.blue,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Cole o link aqui") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, tint = c.inkSoft) },
                trailingIcon = {
                    if (url.isEmpty()) {
                        IconButton(onClick = {
                            val text = FileActions.readClipboard(context)
                            if (text.isNullOrBlank()) {
                                android.widget.Toast.makeText(context, "A área de transferência está vazia.", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                onUrlChange(Links.extractUrl(text) ?: text.trim())
                            }
                        }) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = "Colar link", tint = c.blue)
                        }
                    } else {
                        IconButton(onClick = { onUrlChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Limpar", tint = c.inkSoft)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.blue,
                    unfocusedBorderColor = c.lineDark,
                    focusedContainerColor = c.paper,
                    unfocusedContainerColor = c.paper,
                    cursorColor = c.blue,
                ),
            )
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = DownloadMode.entries
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.mode == mode,
                        onClick = { Prefs.update { it.copy(mode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        icon = {
                            Icon(
                                if (mode == DownloadMode.VIDEO) Icons.Filled.Movie else Icons.Filled.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    ) {
                        Text(if (mode == DownloadMode.VIDEO) "Vídeo MP4" else "Áudio MP3")
                    }
                }
            }
            if (settings.mode == DownloadMode.VIDEO) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("QUALIDADE", style = MonoLabel, color = c.inkSoft)
                    VideoQuality.entries.forEach { quality ->
                        FilterChip(
                            selected = settings.quality == quality,
                            onClick = { Prefs.update { it.copy(quality = quality) } },
                            label = { Text(quality.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = c.blue,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onSubmit,
                enabled = detected != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(6.dp),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (settings.mode == DownloadMode.AUDIO) "BAIXAR ÁUDIO" else "BAIXAR VÍDEO",
                    fontFamily = ArchivoExpanded,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                )
            }
            if (url.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Dica: no app do vídeo, toque em Compartilhar e escolha Baixa Vídeos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun EngineStatus(engine: Engine.State, update: Engine.UpdateState, onRetry: () -> Unit) {
    val c = Brand.colors
    val (color, text, busy) = when (engine) {
        Engine.State.Starting -> Triple(c.gold, "PREPARANDO O MOTOR · SÓ DEMORA NA PRIMEIRA VEZ", true)
        is Engine.State.Failed -> Triple(c.rec, "O MOTOR NÃO INICIOU · TOQUE PARA TENTAR DE NOVO", false)
        is Engine.State.Ready -> when (update) {
            Engine.UpdateState.Checking -> Triple(c.gold, "MOTOR yt-dlp ${engine.version} · PROCURANDO ATUALIZAÇÃO", true)
            is Engine.UpdateState.Downloading -> Triple(c.gold, "ATUALIZANDO O MOTOR PARA ${update.version}", true)
            is Engine.UpdateState.WaitingIdle -> Triple(c.gold, "ATUALIZAÇÃO ${update.version} ESPERANDO OS DOWNLOADS", false)
            else -> Triple(c.green, "MOTOR yt-dlp ${engine.version} · PRONTO", false)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = engine is Engine.State.Failed, onClick = onRetry)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = color)
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MonoLabel, color = c.inkSoft)
    }
}

@Composable
private fun EmptyState() {
    val c = Brand.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .viewfinder(c.lineDark, length = 12.dp, stroke = 1.5.dp)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NENHUM DOWNLOAD AINDA", style = MonoLabel.copy(fontWeight = FontWeight.Bold), color = c.inkSoft)
        Spacer(Modifier.height(10.dp))
        Text(
            "Copie o link de um vídeo e cole acima. Ou abra o vídeo no Instagram, YouTube ou TikTok, " +
                "toque em Compartilhar e escolha Baixa Vídeos.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.inkSoft,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Os arquivos ficam em Download/BaixaVideos e aparecem na galeria.",
            style = MaterialTheme.typography.bodySmall,
            color = c.inkSoft.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
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
                    style = MonoLabel.copy(fontSize = 12.sp, letterSpacing = 0.sp),
                )
                Spacer(Modifier.height(12.dp))
                Text(task.url, style = MonoLabel.copy(fontSize = 11.sp, letterSpacing = 0.sp), color = Brand.colors.blue)
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
