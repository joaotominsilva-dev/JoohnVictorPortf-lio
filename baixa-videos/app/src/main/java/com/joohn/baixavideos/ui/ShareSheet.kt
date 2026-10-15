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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.DownloadTask
import com.joohn.baixavideos.ui.components.CharisLogo
import com.joohn.baixavideos.ui.components.CreamCard
import com.joohn.baixavideos.ui.components.PrimaryButton
import com.joohn.baixavideos.ui.theme.Charis
import com.joohn.baixavideos.ui.trim.TrimCard
import kotlinx.coroutines.launch

/** Janela de opções que sobe por cima do app que compartilhou o link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    draft: DownloadDraft,
    holder: PendingStartHolder,
    sharedText: String?,
    onClose: () -> Unit,
    onStarted: (DownloadTask) -> Unit,
    onOpenApp: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }
    val start = rememberDownloadStarter(holder) { task -> hideThen { onStarted(task) } }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = Charis.Cream,
        contentColor = Charis.Ink,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Charis.SageLight),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharisLogo(size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Text("Opções de download", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { hideThen(onClose) }) { Text("Fechar", color = Charis.InkSoft) }
            }
            if (draft.link == null) {
                CreamCard(Modifier.fillMaxWidth(), color = Charis.Cream2) {
                    Text("Não encontrei um link no que foi compartilhado.", style = MaterialTheme.typography.titleSmall)
                    if (!sharedText.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            sharedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Charis.InkSoft,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                PrimaryButton("Fechar", onClick = { hideThen(onClose) })
            } else {
                InfoCard(draft, color = Charis.Cream2)
                ModeTabs(draft, onDark = false)
                if (draft.mode == DownloadMode.VIDEO) QualityPills(draft, idleColor = Charis.Cream2)
                TrimCard(draft, cardColor = Charis.Cream2, boxColor = Charis.Cream)
                DownloadButton(draft, onClick = { draft.toStart()?.let(start) })
                TextButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) {
                    Text("Abrir o Charis Downloader", color = Charis.SageDeep, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
