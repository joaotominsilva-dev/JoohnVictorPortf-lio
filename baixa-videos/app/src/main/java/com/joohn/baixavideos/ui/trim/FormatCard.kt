package com.joohn.baixavideos.ui.trim

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.joohn.baixavideos.engine.Framing
import com.joohn.baixavideos.ui.DownloadDraft
import com.joohn.baixavideos.ui.InfoState
import com.joohn.baixavideos.ui.components.CreamCard
import com.joohn.baixavideos.ui.theme.Charis

/**
 * Cartão "Formato do vídeo": mantém o original ou recorta para vertical (9:16) ou horizontal
 * (16:9), com a prévia mostrando a parte que fica e o ajuste de posição.
 */
@Composable
fun FormatCard(
    draft: DownloadDraft,
    controller: PreviewController?,
    modifier: Modifier = Modifier,
    cardColor: Color = Charis.Cream,
    boxColor: Color = Charis.Cream2,
) {
    val loading = draft.info is InfoState.Loading
    val enabled = draft.link != null && !loading
    val subtitle = when {
        draft.link == null -> "Cole um link para escolher"
        draft.framing == Framing.VERTICAL -> "Em pé, para Reels, TikTok e Status"
        draft.framing == Framing.HORIZONTAL -> "Deitado, para YouTube e TV"
        else -> "Do jeito que o site entrega"
    }
    CreamCard(modifier.fillMaxWidth(), color = cardColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Formato do vídeo", style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Charis.InkSoft)
            }
        }
        Spacer(Modifier.height(10.dp))
        FramingPills(draft.framing, enabled, boxColor, onSelect = draft::selectFraming)
        val reframe = draft.reframe ?: return@CreamCard
        val aspect = sourceAspect(draft, controller)
        Spacer(Modifier.height(12.dp))
        if (aspect != null && reframe.window(aspect) == null) {
            Text(
                "Este vídeo já está na ${reframe.framing.word}. Ele sai como está.",
                style = MaterialTheme.typography.bodySmall,
                color = Charis.InkSoft,
            )
            return@CreamCard
        }
        if (draft.trimOn) {
            Text(
                "A prévia do corte, logo abaixo, mostra a parte que fica.",
                style = MaterialTheme.typography.bodySmall,
                color = Charis.InkSoft,
            )
        } else {
            PreviewBox(
                info = draft.loadedInfo,
                controller = controller,
                label = "PRÉVIA DO FORMATO",
                caption = null,
                onPlay = { controller?.playRange(0, draft.durationSec ?: Long.MAX_VALUE / 1000) },
                guide = frameGuide(draft, controller),
            )
        }
        Spacer(Modifier.height(6.dp))
        val horizontal = reframe.movesHorizontally(aspect)
        val (from, to) = if (horizontal) "Esquerda" to "Direita" else "Topo" to "Base"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(from, style = MaterialTheme.typography.labelMedium, color = Charis.InkSoft)
            Slider(
                value = draft.framePosition,
                onValueChange = draft::setFramePosition,
                colors = SliderDefaults.colors(
                    thumbColor = Charis.Charcoal,
                    activeTrackColor = Charis.Sage,
                    inactiveTrackColor = boxColor,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = "Posição do recorte, de $from a $to" },
            )
            Text(to, style = MaterialTheme.typography.labelMedium, color = Charis.InkSoft)
        }
    }
}

@Composable
private fun FramingPills(selected: Framing, enabled: Boolean, idleColor: Color, onSelect: (Framing) -> Unit) {
    Row(
        Modifier
            .selectableGroup()
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Framing.entries.forEach { framing ->
            val isSelected = selected == framing
            val content = if (isSelected) Charis.Cream else Charis.Ink
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Charis.Charcoal else idleColor)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(framing) })
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ShapeIcon(framing, content)
                Spacer(Modifier.height(4.dp))
                Text(framing.label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
                Text(
                    framing.ratio ?: "como veio",
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Retângulo com a proporção do formato (o original vira dois quadros sobrepostos). */
@Composable
private fun ShapeIcon(framing: Framing, color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = Stroke(1.6.dp.toPx())
        val radius = CornerRadius(2.dp.toPx())
        fun box(w: Float, h: Float) = drawRoundRect(
            color,
            Offset((size.width - w) / 2, (size.height - h) / 2),
            Size(w, h),
            radius,
            style = stroke,
        )
        when (framing) {
            Framing.VERTICAL -> box(size.width * 0.56f, size.height)
            Framing.HORIZONTAL -> box(size.width, size.height * 0.56f)
            Framing.ORIGINAL -> {
                box(size.width * 0.56f, size.height * 0.8f)
                box(size.width * 0.9f, size.height * 0.5f)
            }
        }
    }
}
