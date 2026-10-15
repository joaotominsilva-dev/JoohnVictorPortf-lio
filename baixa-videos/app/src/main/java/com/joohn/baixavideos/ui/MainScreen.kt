package com.joohn.baixavideos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joohn.baixavideos.MainViewModel
import com.joohn.baixavideos.Tab
import com.joohn.baixavideos.engine.Downloads
import com.joohn.baixavideos.ui.theme.CardLabelStyle
import com.joohn.baixavideos.ui.theme.Charis
import kotlinx.coroutines.launch

/** Espaço reservado no fim de cada tela para a barra de navegação flutuante. */
val NavBarSpace = 96.dp

/** Metade de cima grafite e metade de baixo sálvia: o bloco de cartões nunca deixa um buraco escuro embaixo. */
fun Modifier.splitBackground(): Modifier = drawBehind {
    drawRect(Charis.Charcoal, size = Size(size.width, size.height / 2))
    drawRect(Charis.SageLight, topLeft = Offset(0f, size.height / 2), size = Size(size.width, size.height / 2))
}

@Composable
fun MainScreen(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tasks by Downloads.tasks.collectAsStateWithLifecycle()
    val active = tasks.count { it.status.isActive }

    BackHandler(enabled = vm.tab != Tab.HOME) { vm.tab = Tab.HOME }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // Um aviso de uma aba não acompanha a pessoa para a próxima.
    LaunchedEffect(vm.tab) { snackbar.currentSnackbarData?.dismiss() }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Charis.Charcoal),
    ) {
        when (vm.tab) {
            Tab.HOME -> HomeScreen(vm, onStarted = {
                scope.launch {
                    val result = snackbar.showSnackbar(
                        "Download iniciado",
                        actionLabel = "Ver",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.tab = Tab.DOWNLOADS
                }
            })
            Tab.DOWNLOADS -> DownloadsScreen(onMessage = ::message)
            Tab.SETTINGS -> SettingsScreen()
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        ) {
            SnackbarHost(snackbar)
            CharisNavBar(
                tab = vm.tab,
                onSelect = { vm.tab = it },
                activeDownloads = active,
                onDarkScreen = vm.tab == Tab.SETTINGS,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun CharisNavBar(
    tab: Tab,
    onSelect: (Tab) -> Unit,
    activeDownloads: Int,
    onDarkScreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val container = if (onDarkScreen) Charis.Charcoal2 else Charis.Charcoal
    Row(
        modifier
            .fillMaxWidth()
            .shadow(14.dp, CircleShape)
            .clip(CircleShape)
            .background(container)
            .border(1.dp, Charis.DarkLine, CircleShape)
            .padding(5.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavItem("Início", Icons.Filled.Home, tab == Tab.HOME, Modifier.weight(1f)) { onSelect(Tab.HOME) }
        NavItem("Downloads", Icons.Filled.Download, tab == Tab.DOWNLOADS, Modifier.weight(1f), badge = activeDownloads) {
            onSelect(Tab.DOWNLOADS)
        }
        NavItem("Ajustes", Icons.Filled.Tune, tab == Tab.SETTINGS, Modifier.weight(1f)) { onSelect(Tab.SETTINGS) }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val content = if (selected) Charis.Charcoal else Charis.OnDarkSoft
    Box(
        modifier
            .clip(CircleShape)
            .background(if (selected) Charis.Sage else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
        }
        if (badge > 0) {
            Text(
                badge.toString(),
                style = CardLabelStyle.copy(letterSpacing = 0.sp),
                color = Charis.Charcoal,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 6.dp)
                    .clip(CircleShape)
                    .background(Charis.Cream)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** Espaço final das telas: barra de navegação do sistema + barra flutuante do app. */
@Composable
fun BottomSpace(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(NavBarSpace),
    )
}
