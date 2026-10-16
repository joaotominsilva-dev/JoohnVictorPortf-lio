package com.joohn.baixavideos.ui.trim

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.joohn.baixavideos.ui.DownloadDraft
import com.joohn.baixavideos.ui.InfoState
import com.joohn.baixavideos.ui.components.CharisSwitch
import com.joohn.baixavideos.ui.components.CreamCard
import com.joohn.baixavideos.ui.theme.Charis
import com.joohn.baixavideos.util.Formats
import kotlin.math.abs
import kotlin.math.roundToLong

private enum class Handle { START, END }

/**
 * Cartão "Cortar trecho": liga o corte, mostra a prévia em vídeo do trecho, a linha do tempo com
 * as duas alças e os tempos exatos (tocáveis para digitar).
 */
@Composable
fun TrimCard(
    draft: DownloadDraft,
    controller: PreviewController?,
    modifier: Modifier = Modifier,
    cardColor: Color = Charis.Cream,
    boxColor: Color = Charis.Cream2,
) {
    val loading = draft.info is InfoState.Loading
    val subtitle = when {
        draft.link == null -> "Cole um link para cortar"
        loading -> "Lendo o vídeo…"
        else -> "Baixa só a parte escolhida"
    }
    CreamCard(modifier.fillMaxWidth(), color = cardColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Cortar trecho", style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Charis.InkSoft)
            }
            CharisSwitch(
                checked = draft.trimOn,
                onCheckedChange = { draft.setTrimEnabled(it) },
                enabled = draft.link != null && !loading,
                modifier = Modifier.semantics { contentDescription = "Cortar trecho" },
            )
        }
        AnimatedVisibility(visible = draft.trimOn) {
            TrimEditor(draft, controller, boxColor)
        }
    }
}

@Composable
private fun TrimEditor(draft: DownloadDraft, controller: PreviewController?, boxColor: Color) {
    val info = draft.loadedInfo

    // Abre mostrando o quadro do início do trecho.
    LaunchedEffect(controller) { controller?.showFrame(draft.trimStart, dragging = false) }

    val duration = draft.durationSec
    var editing by remember { mutableStateOf<Handle?>(null) }

    Column(Modifier.padding(top = 12.dp)) {
        val length = (draft.trimEnd - draft.trimStart).coerceAtLeast(0L)
        PreviewBox(
            info = info,
            controller = controller,
            label = "PRÉVIA DO CORTE",
            caption = if (length > 0) "trecho ${Formats.clock(length)}" else null,
            onPlay = { controller?.playRange(draft.trimStart, draft.trimEnd) },
            guide = frameGuide(draft, controller),
        )
        if (duration != null) {
            Spacer(Modifier.height(10.dp))
            TrimTimeline(
                durationSec = duration,
                start = draft.trimStart,
                end = draft.trimEnd,
                playheadMs = controller?.positionMs,
                onMove = { handle, seconds, dragging ->
                    if (handle == Handle.START) {
                        draft.setTrimRange(seconds.coerceAtMost(draft.trimEnd - 1), draft.trimEnd)
                        controller?.showFrame(draft.trimStart, dragging)
                    } else {
                        draft.setTrimRange(draft.trimStart, seconds.coerceAtLeast(draft.trimStart + 1))
                        controller?.showFrame(draft.trimEnd, dragging)
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeBox("Início", Formats.clock(draft.trimStart), boxColor, Modifier.weight(1f)) { editing = Handle.START }
            val endLabel = if (duration == null && draft.trimEnd == 0L) "--:--" else Formats.clock(draft.trimEnd)
            TimeBox("Fim", endLabel, boxColor, Modifier.weight(1f)) { editing = Handle.END }
            val length = (draft.trimEnd - draft.trimStart).coerceAtLeast(0L)
            TimeBox("Trecho", if (length > 0) Formats.clock(length) else "--:--", boxColor, Modifier.weight(1f), onClick = null)
        }
        draft.trimProblem?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Charis.Danger)
        }
    }

    editing?.let { handle ->
        TimeDialog(
            title = if (handle == Handle.START) "Início do trecho" else "Fim do trecho",
            initialSeconds = if (handle == Handle.START) draft.trimStart else draft.trimEnd,
            maxSeconds = duration,
            onDismiss = { editing = null },
            onConfirm = { seconds ->
                editing = null
                if (handle == Handle.START) {
                    draft.setTrimRange(seconds, draft.trimEnd)
                    controller?.showFrame(draft.trimStart, dragging = false)
                } else {
                    draft.setTrimRange(draft.trimStart, seconds)
                    controller?.showFrame(draft.trimEnd, dragging = false)
                }
            },
        )
    }
}

/** Linha do tempo com a área escolhida em sálvia, as duas alças e o cursor da prévia. */
@Composable
private fun TrimTimeline(
    durationSec: Long,
    start: Long,
    end: Long,
    playheadMs: Long?,
    onMove: (Handle, Long, dragging: Boolean) -> Unit,
) {
    val currentStart by rememberUpdatedState(start)
    val currentEnd by rememberUpdatedState(end)
    val move by rememberUpdatedState(onMove)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                contentDescription = "Trecho de ${Formats.clock(start)} a ${Formats.clock(end)}. Use Início e Fim para ajustar."
            }
            .pointerInput(durationSec) {
                val pad = 8.dp.toPx()
                fun secondsAt(x: Float): Long {
                    val fraction = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                    return (fraction * durationSec).roundToLong()
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val t = secondsAt(down.position.x)
                    val handle = if (abs(t - currentStart) <= abs(t - currentEnd)) Handle.START else Handle.END
                    down.consume()
                    move(handle, t, true)
                    var last = t
                    drag(down.id) { change ->
                        last = secondsAt(change.position.x)
                        move(handle, last, true)
                        change.consume()
                    }
                    move(handle, last, false)
                }
            },
    ) {
        val pad = 8.dp.toPx()
        val width = size.width - 2 * pad
        fun x(seconds: Float) = pad + width * (seconds / durationSec).coerceIn(0f, 1f)
        val trackTop = 7.dp.toPx()
        val trackHeight = size.height - 2 * trackTop
        val radius = CornerRadius(9.dp.toPx())

        drawRoundRect(Charis.Cream2, Offset(pad, trackTop), Size(width, trackHeight), radius)
        clipRect(pad, trackTop, pad + width, trackTop + trackHeight) { hatch(Charis.SageDeep.copy(alpha = 0.18f)) }
        val xs = x(start.toFloat())
        val xe = x(end.toFloat())
        drawRoundRect(Charis.Sage, Offset(xs, trackTop), Size((xe - xs).coerceAtLeast(1f), trackHeight), CornerRadius(6.dp.toPx()))
        playheadMs?.let {
            val xp = x(it / 1000f)
            drawLine(Charis.Ink.copy(alpha = 0.45f), Offset(xp, 3.dp.toPx()), Offset(xp, size.height - 3.dp.toPx()), 4.dp.toPx())
            drawLine(Charis.Cream, Offset(xp, 3.dp.toPx()), Offset(xp, size.height - 3.dp.toPx()), 2.dp.toPx())
        }
        listOf(xs, xe).forEach { hx ->
            val w = 14.dp.toPx()
            drawRoundRect(Charis.Charcoal, Offset(hx - w / 2, 0f), Size(w, size.height), CornerRadius(6.dp.toPx()))
            drawLine(
                Charis.Cream,
                Offset(hx, size.height / 2 - 7.dp.toPx()),
                Offset(hx, size.height / 2 + 7.dp.toPx()),
                2.dp.toPx(),
            )
        }
    }
}

private fun DrawScope.hatch(color: Color) {
    val step = 12.dp.toPx()
    var x = -size.height
    while (x < size.width + step) {
        drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), 4.5.dp.toPx())
        x += step
    }
}

@Composable
private fun TimeBox(label: String, value: String, color: Color, modifier: Modifier, onClick: (() -> Unit)?) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = Charis.InkSoft)
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
    }
}

/** Digitar um tempo exato: minutos e segundos (os minutos podem passar de 59). */
@Composable
private fun TimeDialog(
    title: String,
    initialSeconds: Long,
    maxSeconds: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var minutes by remember { mutableStateOf((initialSeconds / 60).toString()) }
    var seconds by remember { mutableStateOf((initialSeconds % 60).toString().padStart(2, '0')) }
    val total = (minutes.toLongOrNull() ?: 0L) * 60 + (seconds.toLongOrNull() ?: 0L)
    val error = when {
        (seconds.toLongOrNull() ?: 0L) > 59 -> "Os segundos vão de 0 a 59."
        maxSeconds != null && total > maxSeconds -> "O vídeo tem ${Formats.clock(maxSeconds)}."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { v -> minutes = v.filter(Char::isDigit).take(4) },
                        label = { Text("Minutos") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Text(":", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { v -> seconds = v.filter(Char::isDigit).take(2) },
                        label = { Text("Segundos") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(total) }, enabled = error == null) { Text("Usar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
