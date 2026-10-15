package com.joohn.baixavideos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joohn.baixavideos.MainViewModel
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.DownloadTask
import com.joohn.baixavideos.engine.Engine
import com.joohn.baixavideos.ui.components.Headline
import com.joohn.baixavideos.ui.components.RoundIconButton
import com.joohn.baixavideos.ui.components.SmallPill
import com.joohn.baixavideos.ui.components.TopBar
import com.joohn.baixavideos.ui.components.well
import com.joohn.baixavideos.ui.theme.Charis
import com.joohn.baixavideos.ui.trim.TrimCard
import java.util.Calendar

@Composable
fun HomeScreen(vm: MainViewModel, onStarted: (DownloadTask) -> Unit) {
    val draft = vm.draft
    val context = LocalContext.current
    val engine by Engine.state.collectAsStateWithLifecycle()
    val update by Engine.update.collectAsStateWithLifecycle()
    val start = rememberDownloadStarter(vm) { task ->
        draft.reset()
        onStarted(task)
    }

    Column(
        Modifier
            .fillMaxSize()
            .splitBackground()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.background(Charis.Charcoal)) {
            Spacer(Modifier.statusBarsPadding())
            TopBar(today())
            Headline(
                "Olá! O que vamos baixar?",
                "Cole um link, pesquise no YouTube ou compartilhe de outro app.",
                Modifier.padding(top = 6.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeTabs(draft, onDark = true, modifier = Modifier.weight(1f))
                RoundIconButton(Icons.Filled.ContentPaste, "Colar link", onClick = { pasteInto(context, draft) })
            }
            EngineStatus(engine, update, onRetry = { Engine.start(context) })
        }
        Column(
            Modifier
                .fillMaxWidth()
                .well()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinkCard(draft)
            if (draft.link == null && draft.text.isNotBlank()) {
                // Texto que não é link: a área de baixo vira a lista da busca no YouTube.
                SearchResultsCard(draft)
            } else {
                if (draft.link != null && draft.search is SearchState.Results) {
                    SmallPill(
                        "Voltar aos resultados",
                        onClick = draft::backToResults,
                        filled = false,
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                    )
                }
                InfoCard(draft)
                if (draft.mode == DownloadMode.VIDEO) QualityCard(draft)
                TrimCard(draft)
                DownloadButton(draft, onClick = { draft.toStart()?.let(start) })
            }
            BottomSpace()
        }
    }
}

/** Aviso discreto do motor: só aparece enquanto ele prepara, atualiza ou falha. */
@Composable
private fun EngineStatus(engine: Engine.State, update: Engine.UpdateState, onRetry: () -> Unit) {
    val (text, busy) = when (engine) {
        Engine.State.Starting -> "Preparando o motor · só demora na primeira vez" to true
        is Engine.State.Failed -> "O motor não iniciou · toque para tentar de novo" to false
        is Engine.State.Ready -> when (update) {
            Engine.UpdateState.Checking -> "Procurando atualização do motor" to true
            is Engine.UpdateState.Downloading -> "Atualizando o motor para ${update.version}" to true
            is Engine.UpdateState.WaitingIdle -> "A atualização ${update.version} espera os downloads acabarem" to false
            else -> return
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = engine is Engine.State.Failed, onClick = onRetry)
            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(color = Charis.Sage, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (engine is Engine.State.Failed) Color(0xFFE59A86) else Charis.Sage),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Charis.OnDarkSoft)
    }
}

/** "Qua, 15 out", como o cabeçalho da referência. */
private fun today(): String {
    val days = listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")
    val months = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
    val now = Calendar.getInstance()
    return "${days[now.get(Calendar.DAY_OF_WEEK) - 1]}, ${now.get(Calendar.DAY_OF_MONTH)} ${months[now.get(Calendar.MONTH)]}"
}
