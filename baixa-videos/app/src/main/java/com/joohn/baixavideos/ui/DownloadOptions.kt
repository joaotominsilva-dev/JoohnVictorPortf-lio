package com.joohn.baixavideos.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.VideoQuality
import com.joohn.baixavideos.ui.components.CardLabel
import com.joohn.baixavideos.ui.components.CreamCard
import com.joohn.baixavideos.ui.components.PillTabs
import com.joohn.baixavideos.ui.components.PrimaryButton
import com.joohn.baixavideos.ui.components.SmallPill
import com.joohn.baixavideos.ui.components.Thumb
import com.joohn.baixavideos.ui.components.hatched
import com.joohn.baixavideos.ui.theme.Charis
import com.joohn.baixavideos.util.Formats
import com.joohn.baixavideos.util.Links

val ModeOptions = listOf(DownloadMode.VIDEO to "Vídeo", DownloadMode.AUDIO to "Áudio MP3")

@Composable
fun ModeTabs(draft: DownloadDraft, onDark: Boolean, modifier: Modifier = Modifier) {
    PillTabs(ModeOptions, draft.mode, draft::selectMode, onDark = onDark, modifier = modifier)
}

/** Cola o que estiver na área de transferência no rascunho. */
fun pasteInto(context: android.content.Context, draft: DownloadDraft) {
    val text = FileActions.readClipboard(context)
    if (text.isNullOrBlank()) {
        Toast.makeText(context, "A área de transferência está vazia.", Toast.LENGTH_SHORT).show()
    } else {
        draft.updateText(Links.extractUrl(text) ?: text.trim())
    }
}

@Composable
fun LinkCard(draft: DownloadDraft, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    CreamCard(modifier.fillMaxWidth()) {
        CardLabel("Link")
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft.text,
                onValueChange = draft::updateText,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Charis.Ink),
                cursorBrush = SolidColor(Charis.SageDeep),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Link do vídeo" },
                decorationBox = { field ->
                    Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                        if (draft.text.isEmpty()) {
                            Text(
                                "Cole o link do vídeo aqui",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Charis.InkSoft,
                                maxLines = 1,
                            )
                        }
                        field()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            if (draft.text.isEmpty()) {
                SmallPill("Colar", onClick = { pasteInto(context, draft) })
            } else {
                SmallPill("Limpar", onClick = { draft.updateText("") }, filled = false)
            }
        }
    }
}

/** Prévia do link: miniatura, título e duração (ou o estado de leitura). */
@Composable
fun InfoCard(draft: DownloadDraft, modifier: Modifier = Modifier, color: Color = Charis.Cream) {
    CreamCard(modifier.fillMaxWidth(), color = color, padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        when (val state = draft.info) {
            InfoState.Empty -> Placeholder { Text("Os detalhes do vídeo aparecem aqui", style = MaterialTheme.typography.bodySmall, color = Charis.InkSoft) }
            InfoState.Loading -> Placeholder {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Charis.SageDeep, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Lendo o vídeo…", style = MaterialTheme.typography.bodySmall, color = Charis.InkSoft)
                }
            }
            is InfoState.Failed -> Column {
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Você ainda pode tentar baixar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Charis.InkSoft,
                )
                Spacer(Modifier.height(8.dp))
                SmallPill("Ler de novo", onClick = draft::retryInfo, filled = false)
            }
            is InfoState.Loaded -> {
                val info = state.info
                val platform = Links.platformOf(info.url)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumb(
                        url = info.thumbnail,
                        badge = platform.badge,
                        audio = draft.mode == DownloadMode.AUDIO,
                        modifier = Modifier.size(width = 88.dp, height = 64.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            info.title ?: Links.hostOf(info.url) ?: info.url,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(
                            platform.label,
                            info.uploader,
                            info.durationSec?.let { Formats.clock(it.toLong()) },
                        ).joinToString(" · ")
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = Charis.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Placeholder(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .hatched(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun QualityPills(
    draft: DownloadDraft,
    modifier: Modifier = Modifier,
    idleColor: Color = Charis.Cream2,
) {
    Row(modifier.selectableGroup().fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VideoQuality.entries.forEach { quality ->
            val selected = draft.quality == quality
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Charis.Charcoal else idleColor)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { draft.selectQuality(quality) })
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    quality.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Charis.Cream else Charis.Ink,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun QualityCard(draft: DownloadDraft, modifier: Modifier = Modifier) {
    CreamCard(modifier.fillMaxWidth()) {
        CardLabel("Qualidade")
        Spacer(Modifier.height(8.dp))
        QualityPills(draft)
    }
}

fun downloadLabel(draft: DownloadDraft): String {
    val trim = draft.trim
    return when {
        trim != null -> "Baixar trecho de ${Formats.clock(trim.lengthSec)}"
        draft.mode == DownloadMode.AUDIO -> "Baixar áudio"
        else -> "Baixar vídeo"
    }
}

@Composable
fun DownloadButton(draft: DownloadDraft, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PrimaryButton(
        text = downloadLabel(draft),
        onClick = onClick,
        enabled = draft.canDownload,
        icon = Icons.Filled.Download,
        modifier = modifier,
    )
}
