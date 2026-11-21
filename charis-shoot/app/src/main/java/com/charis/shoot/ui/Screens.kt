@file:OptIn(ExperimentalMaterial3Api::class)

package com.charis.shoot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.charis.shoot.CharisApp
import com.charis.shoot.Filter
import com.charis.shoot.Screen
import com.charis.shoot.SeparateWhat
import com.charis.shoot.culling.Sensitivity
import com.charis.shoot.drive.Photo
import com.charis.shoot.edit.Adjust
import com.charis.shoot.edit.Editor
import kotlinx.coroutines.launch

val LABELS = listOf(
    "red" to Charis.Vermelho,
    "yellow" to Charis.Amarelo,
    "green" to Charis.Verde,
    "blue" to Charis.Azul,
    "purple" to Charis.Roxo,
)

fun labelColor(c: String): Color? = LABELS.firstOrNull { it.first == c }?.second

@Composable
fun Root(app: CharisApp) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = app.screen) {
            Screen.Login -> LoginScreen(app)
            Screen.Folders -> FoldersScreen(app)
            Screen.Grid -> GridScreen(app)
            is Screen.Viewer -> ViewerScreen(app, s)
            is Screen.Editor -> EditorScreen(app, s)
        }
    }
}

@Composable
private fun ThemeToggle(app: CharisApp) {
    IconButton(onClick = { app.toggleTheme() }) {
        Icon(
            if (app.dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
            contentDescription = "Alternar modo escuro e claro (Tema Charis v1)",
        )
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar") }
}

@Composable
private fun charisBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
)

@Composable
private fun Wordmark(size: Int = 40) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "Charis", fontSize = size.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground, fontFamily = InterCharis,
        )
        Text(
            " Shoot", fontSize = size.sp, fontWeight = FontWeight.Normal,
            color = Charis.Salvia, fontFamily = InterCharis,
        )
    }
}

// ---------------------------------------------------------------- Login

@Composable
fun LoginScreen(app: CharisApp) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(Charis.Grafite)
                    .border(1.dp, Charis.SalviaEscuro, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).border(7.dp, Charis.Creme, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Charis.Salvia))
                }
            }
            Spacer(Modifier.height(24.dp))
            Wordmark()
            Spacer(Modifier.height(8.dp))
            Text(
                "Culling das suas fotos direto do Google Drive. Nada fica no celular.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { app.login() },
                enabled = !app.loggingIn,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (app.loggingIn) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Entrar com Google", fontWeight = FontWeight.SemiBold)
            }
            app.loginError?.let { err ->
                Spacer(Modifier.height(20.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(err, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(10.dp))
                        Text("Pacote: com.charis.shoot", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer {
                            Text("SHA-1: ${app.sha1}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            ThemeToggle(app)
        }
    }
}

// ---------------------------------------------------------------- Pastas

@Composable
fun FoldersScreen(app: CharisApp) {
    BackHandler(enabled = app.crumbs.size > (if (app.shared) 0 else 1)) { app.folderBack() }
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = charisBarColors(),
                title = {
                    Column {
                        Wordmark(22)
                        if (app.userEmail.isNotEmpty()) Text(
                            app.userEmail, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    ThemeToggle(app)
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Atualizar") },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                            onClick = { menu = false; app.loadFolders() },
                        )
                        DropdownMenuItem(
                            text = { Text("Sair da conta") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
                            onClick = { menu = false; app.logout() },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(app.snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !app.shared, onClick = { app.openRoot(false) }, label = { Text("Meu Drive") })
                FilterChip(selected = app.shared, onClick = { app.openRoot(true) }, label = { Text("Compartilhados comigo") })
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(app.crumbs) { i, c ->
                    if (i > 0) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        c.name,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { app.goToCrumb(i) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        fontWeight = if (i == app.crumbs.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (i == app.crumbs.lastIndex) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (app.canCull) {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.crumbs.last().name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Abrir as fotos desta pasta", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { app.startCulling() }, shape = RoundedCornerShape(12.dp)) { Text("Culling") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (app.foldersLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            if (!app.foldersLoading && app.folders.isEmpty()) {
                Text(
                    "Nenhuma subpasta aqui.", modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(app.folders, key = { it.id }) { f ->
                    ListItem(
                        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).clickable { app.openFolder(f) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Rounded.Folder, null, tint = Charis.Salvia) },
                        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Grade de culling

@Composable
private fun DriveImage(app: CharisApp, p: Photo, size: Int, modifier: Modifier, scale: ContentScale) {
    val ctx = LocalContext.current
    val url = p.thumb(size)
    if (url == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Text(p.name.substringAfterLast('.').uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val req = remember(url, app.token) {
        ImageRequest.Builder(ctx)
            .data(url)
            .apply { app.token?.let { addHeader("Authorization", "Bearer $it") } }
            .memoryCacheKey("${p.id}_$size")
            .diskCacheKey("${p.id}_$size")
            .crossfade(true)
            .build()
    }
    AsyncImage(model = req, contentDescription = p.name, modifier = modifier, contentScale = scale)
}

@Composable
fun GridScreen(app: CharisApp) {
    BackHandler { app.screen = Screen.Folders }
    var menu by remember { mutableStateOf(false) }
    var suggest by remember { mutableStateOf(false) }
    var separate by remember { mutableStateOf(false) }
    val visible = app.visible()
    val picks = app.photos.count { it.isPick }
    val rejects = app.photos.count { it.isReject }
    val analyzed = app.photos.count { it.analyzed }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = charisBarColors(),
                navigationIcon = { BackButton { app.screen = Screen.Folders } },
                title = {
                    Column {
                        Text(app.folder?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${app.photos.size} fotos · $picks escolhidas · $rejects rejeitadas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    ThemeToggle(app)
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Atualizar lista") }, onClick = { menu = false; app.loadPhotos() })
                        DropdownMenuItem(text = { Text("Analisar tudo de novo") }, onClick = { menu = false; app.analyze(true) })
                        HorizontalDivider()
                        Text(
                            "Sensibilidade a desfoque", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        Sensitivity.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label) },
                                leadingIcon = { RadioButton(selected = app.sensitivity == s, onClick = null) },
                                onClick = { menu = false; app.changeSensitivity(s) },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(app.snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (app.working) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(progress = { app.progress }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(app.progressText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { app.cancelWork() }) { Text("Parar") }
                    }
                }
            } else if (app.photosLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilledTonalButton(onClick = { app.analyze(false) }, enabled = !app.working && app.photos.isNotEmpty()) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (analyzed < app.photos.size) "Analisar (${app.photos.size - analyzed})" else "Analisado")
                    }
                }
                item {
                    FilledTonalButton(onClick = { suggest = true }, enabled = !app.working && analyzed > 0) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Sugestão automática")
                    }
                }
                item {
                    FilledTonalButton(onClick = { separate = true }, enabled = !app.working && app.photos.isNotEmpty()) {
                        Icon(Icons.Rounded.DriveFileMove, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Separar no Drive")
                    }
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Filter.entries) { f ->
                    FilterChip(selected = app.filter == f, onClick = { app.filter = f }, label = { Text(f.label) })
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { p ->
                    PhotoCell(app, p) {
                        val ids = visible.map { it.id }
                        app.screen = Screen.Viewer(ids, ids.indexOf(p.id).coerceAtLeast(0))
                    }
                }
            }
        }
    }

    if (suggest) {
        val (pick, rej) = app.suggestionCount()
        AlertDialog(
            onDismissRequest = { suggest = false },
            title = { Text("Sugestão automática") },
            text = {
                Text(
                    "Nas fotos analisadas e ainda sem marcação: $rej viram Rejeitadas (desfocadas, olhos fechados " +
                        "ou repetidas da mesma série) e $pick viram Escolhidas. Suas marcações manuais não mudam."
                )
            },
            confirmButton = { Button(onClick = { suggest = false; app.applySuggestion() }) { Text("Aplicar") } },
            dismissButton = { TextButton(onClick = { suggest = false }) { Text("Cancelar") } },
        )
    }
    if (separate) SeparateDialog(app) { separate = false }
}

@Composable
private fun Badge(icon: ImageVector, bg: Color, fg: Color = Charis.Grafite) {
    Box(Modifier.size(22.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(14.dp), tint = fg)
    }
}

@Composable
private fun PhotoCell(app: CharisApp, p: Photo, onClick: () -> Unit) {
    val ins = app.insights
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
    ) {
        DriveImage(
            app, p, 400,
            Modifier.fillMaxSize().alpha(if (p.isReject) 0.4f else 1f),
            ContentScale.Crop,
        )
        Row(Modifier.align(Alignment.TopStart).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (p.isPick) Badge(Icons.Rounded.Check, Charis.Creme)
            if (p.isReject) Badge(Icons.Rounded.Close, Charis.Vermelho, Charis.Creme)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (p.id in ins.blurry) Badge(Icons.Rounded.BlurOn, Charis.Aviso)
            if (p.eyes == "closed") Badge(Icons.Rounded.VisibilityOff, Charis.Aviso)
            if (ins.isDuplicate(p.id)) Badge(Icons.Rounded.ContentCopy, Charis.Salvia)
            if (p.id in ins.best) Badge(Icons.Rounded.Verified, Charis.Salvia)
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = if (p.rating > 0 || p.color.isNotEmpty()) 0.45f else 0f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (p.rating > 0) Text("★".repeat(p.rating), color = Charis.Creme, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            labelColor(p.color)?.let { Box(Modifier.size(10.dp).clip(CircleShape).background(it)) }
        }
    }
}

@Composable
private fun SeparateDialog(app: CharisApp, onClose: () -> Unit) {
    var what by remember { mutableStateOf(SeparateWhat.PICKS) }
    var move by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("Charis Selecionadas") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Separar no Drive") },
        text = {
            Column {
                Text("Quais fotos", style = MaterialTheme.typography.labelLarge)
                SeparateWhat.entries.forEach { w ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            what = w
                            name = when (w) {
                                SeparateWhat.PICKS -> "Charis Selecionadas"
                                SeparateWhat.REJECTS -> "Charis Rejeitadas"
                                SeparateWhat.STARRED -> "Charis Estrelas"
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = what == w, onClick = null, modifier = Modifier.padding(8.dp))
                        Text("${w.label} (${app.separateCount(w)})")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Como", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !move, onClick = { move = false }, label = { Text("Copiar") })
                    FilterChip(selected = move, onClick = { move = true }, label = { Text("Mover") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Subpasta de destino") }, shape = RoundedCornerShape(12.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onClose(); app.separate(what, move, name) }) { Text(if (move) "Mover" else "Copiar") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------- Visualizador

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(app: CharisApp, s: Screen.Viewer) {
    BackHandler { app.screen = Screen.Grid }
    val ids = s.ids
    if (ids.isEmpty()) { app.screen = Screen.Grid; return }
    val pager = rememberPagerState(initialPage = s.start.coerceIn(0, ids.lastIndex)) { ids.size }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    val current = app.byId(ids[pager.currentPage.coerceIn(0, ids.lastIndex)])

    fun advance() {
        if (pager.currentPage < ids.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = charisBarColors(),
                navigationIcon = { BackButton { app.screen = Screen.Grid } },
                title = {
                    Column {
                        Text(current?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${pager.currentPage + 1} de ${ids.size}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    ThemeToggle(app)
                    if (current != null) IconButton(onClick = { app.screen = Screen.Editor(current.id, s.copy(start = pager.currentPage)) }) {
                        Icon(Icons.Rounded.Edit, "Editar")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(app.snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = !zoomed,
                key = { ids[it] },
            ) { page ->
                val p = app.byId(ids[page])
                if (p != null) ZoomableImage(app, p, onZoom = { zoomed = it })
            }
            LaunchedEffect(pager.currentPage) { zoomed = false }
            if (current != null) ViewerControls(app, current, ::advance)
        }
    }
}

@Composable
private fun ZoomableImage(app: CharisApp, p: Photo, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = if (scale > 1f) Modifier.pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale <= 1f) Offset.Zero else offset + pan
            onZoom(scale > 1f)
        }
    } else Modifier
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    onZoom(scale > 1f)
                })
            }
            .then(transform),
        contentAlignment = Alignment.Center,
    ) {
        DriveImage(
            app, p, 2048,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
            ContentScale.Fit,
        )
    }
}

@Composable
private fun InfoChip(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun ViewerControls(app: CharisApp, p: Photo, advance: () -> Unit) {
    val ins = app.insights
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!p.analyzed) InfoChip("Ainda não analisada", Charis.Salvia)
            p.sharpness?.let {
                if (p.id in ins.blurry) InfoChip("Desfocada", Charis.Aviso) else InfoChip("Nítida ($it)", Charis.Verde)
            }
            if (p.eyes == "closed") InfoChip("Olhos fechados", Charis.Aviso)
            ins.groupOf[p.id]?.let { g ->
                val n = ins.groupSize[g] ?: 0
                InfoChip(if (p.id in ins.best) "Melhor da série de $n" else "Série de $n", Charis.Salvia)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            (1..5).forEach { r ->
                IconButton(onClick = { app.setRating(p.id, r) }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (r <= p.rating) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        "$r estrelas", tint = if (r <= p.rating) Charis.Amarelo else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            LABELS.forEach { (key, c) ->
                Box(
                    Modifier.padding(3.dp).size(24.dp).clip(CircleShape).background(c)
                        .then(
                            if (p.color == key) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .clickable { app.setColor(p.id, key) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { app.setFlag(p.id, Photo.FLAG_REJECT); if (!p.isReject) advance() },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = if (p.isReject) ButtonDefaults.outlinedButtonColors(
                    containerColor = Charis.Vermelho, contentColor = Charis.Creme
                ) else ButtonDefaults.outlinedButtonColors(),
            ) {
                Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(6.dp)); Text("Rejeitar")
            }
            Button(
                onClick = { app.setFlag(p.id, Photo.FLAG_PICK); if (!p.isPick) advance() },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = if (p.isPick) ButtonDefaults.buttonColors(
                    containerColor = Charis.Creme, contentColor = Charis.Grafite
                ) else ButtonDefaults.buttonColors(),
            ) {
                Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Escolher")
            }
        }
    }
}

// ---------------------------------------------------------------- Edição

@Composable
fun EditorScreen(app: CharisApp, s: Screen.Editor) {
    BackHandler { app.screen = s.back }
    val p = app.byId(s.photoId)
    if (p == null) { app.screen = s.back; return }
    var loaded by remember { mutableStateOf<CharisApp.Loaded?>(null) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var adj by remember { mutableStateOf(Adjust()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(p.id) {
        try {
            val l = app.loadForEdit(p)
            loaded = l
            preview = Editor.scaled(l.full, 1400)
        } catch (e: Exception) {
            error = e.message ?: "Não foi possível abrir a foto."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = charisBarColors(),
                navigationIcon = { BackButton { app.screen = s.back } },
                title = { Text("Editar", maxLines = 1) },
                actions = {
                    TextButton(onClick = { adj = Adjust() }, enabled = !adj.isIdentity) { Text("Restaurar") }
                    Button(
                        onClick = {
                            val l = loaded ?: return@Button
                            saving = true
                            scope.launch {
                                try {
                                    val name = app.saveEdit(p, l, adj)
                                    app.toast("Salvo em Charis Editadas: $name")
                                } catch (e: Exception) {
                                    app.toast("Não salvou: ${e.message}")
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = loaded != null && !saving && !adj.isIdentity,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Salvar no Drive")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(app.snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                val bmp = preview
                when {
                    error != null -> Text(error!!, textAlign = TextAlign.Center)
                    bmp == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Baixando do Drive…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        val img = remember(bmp) { bmp.asImageBitmap() }
                        Image(
                            img, contentDescription = p.name, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.colorMatrix(ColorMatrix(adj.matrix())),
                        )
                    }
                }
            }
            if (loaded?.fromPreview == true) Text(
                "Arquivo RAW: a edição usa a prévia do Drive (até 3000 px) e salva um JPEG.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                AdjustSlider("Exposição", adj.exposure, -2f..2f) { adj = adj.copy(exposure = it) }
                AdjustSlider("Contraste", adj.contrast, -1f..1f) { adj = adj.copy(contrast = it) }
                AdjustSlider("Saturação", adj.saturation, -1f..1f) { adj = adj.copy(saturation = it) }
                AdjustSlider("Temperatura", adj.temperature, -1f..1f) { adj = adj.copy(temperature = it) }
                AdjustSlider("Tonalidade", adj.tint, -1f..1f) { adj = adj.copy(tint = it) }
            }
        }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(104.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(
            "%+.1f".format(value), modifier = Modifier.width(44.dp).clickable { onChange(0f) },
            textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium,
        )
    }
}
