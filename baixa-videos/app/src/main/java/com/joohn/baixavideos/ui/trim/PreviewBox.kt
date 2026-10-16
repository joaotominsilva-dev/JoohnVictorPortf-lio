package com.joohn.baixavideos.ui.trim

import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import coil.compose.AsyncImage
import com.joohn.baixavideos.engine.CropWindow
import com.joohn.baixavideos.engine.VideoInfo
import com.joohn.baixavideos.ui.DownloadDraft
import com.joohn.baixavideos.ui.theme.CardLabelStyle
import com.joohn.baixavideos.ui.theme.Charis
import com.joohn.baixavideos.util.Formats

/**
 * Player da prévia, um só para o corte e para o formato: existe enquanto um dos dois está ligado
 * e o site deu um endereço para tocar.
 */
@Composable
fun rememberPreview(draft: DownloadDraft): PreviewController? {
    val info = draft.loadedInfo
    val needed = draft.trimOn || draft.reframe != null
    val context = LocalContext.current
    val controller = remember(info, needed) {
        if (needed) info?.takeIf { it.preview.isNotEmpty() }?.let { PreviewController(context, it, draft::onPlayerDuration) } else null
    }
    DisposableEffect(controller) { onDispose { controller?.release() } }
    LifecycleStartEffect(controller) { onStopOrDispose { controller?.pause() } }
    if (controller != null) LaunchedEffect(controller) { controller.track() }
    return controller
}

/** Proporção do vídeo: a que o player mediu ou, antes dele, a que o site informou. */
fun sourceAspect(draft: DownloadDraft, controller: PreviewController?): Float? =
    controller?.takeIf { !it.failed }?.videoAspect?.takeIf { it > 0f } ?: draft.loadedInfo?.aspect

/** Moldura do recorte desenhada sobre a prévia; arrastar move a parte que fica. */
class FrameGuide(val window: CropWindow, val horizontal: Boolean, val onMove: (Float) -> Unit)

/** A moldura do formato escolhido no rascunho, ou null (formato original ou vídeo já no formato). */
fun frameGuide(draft: DownloadDraft, controller: PreviewController?): FrameGuide? {
    val reframe = draft.reframe ?: return null
    val aspect = sourceAspect(draft, controller) ?: return null
    val window = reframe.window(aspect) ?: return null
    return FrameGuide(window, reframe.movesHorizontally(aspect)) { delta ->
        draft.placeFrame(draft.framePosition + delta)
    }
}

/**
 * Quadro da prévia: o vídeo (ou a miniatura enquanto ele não abre), o botão de tocar, a etiqueta
 * [label], o tempo com [caption] e, com [guide], a moldura do recorte.
 */
@Composable
internal fun PreviewBox(
    info: VideoInfo?,
    controller: PreviewController?,
    label: String,
    caption: String?,
    onPlay: () -> Unit,
    guide: FrameGuide?,
) {
    val playerAspect = controller?.takeIf { !it.failed }?.videoAspect?.takeIf { it > 0f } ?: 0f
    val shownAspect = if (playerAspect > 0f) playerAspect else info?.aspect ?: 0f
    // Vídeos em pé (TikTok, Reels) ganham um quadro quadrado; os deitados ficam em 16:9.
    val boxAspect = if (shownAspect > 0f) shownAspect.coerceIn(1f, 16f / 9f) else 16f / 9f
    val mediaModifier = if (shownAspect > 0f) {
        Modifier.aspectRatio(shownAspect, matchHeightConstraintsFirst = shownAspect < boxAspect)
    } else {
        Modifier.fillMaxSize()
    }
    val canPlay = controller != null && controller.player != null && !controller.failed
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(boxAspect)
            .clip(RoundedCornerShape(14.dp))
            .background(Charis.Charcoal)
            .clickable(enabled = canPlay, role = Role.Button) {
                if (controller!!.playing) controller.pause() else onPlay()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (info?.thumbnail != null && controller?.hasFrame != true) {
            AsyncImage(
                model = info.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = if (guide != null && shownAspect > 0f) mediaModifier else Modifier.matchParentSize(),
            )
        }
        val player = controller?.player
        if (player != null && controller?.failed == false) {
            AndroidView(
                factory = { TextureView(it).also(player::setVideoTextureView) },
                onRelease = { player.clearVideoTextureView(it) },
                modifier = if (playerAspect > 0f) mediaModifier else Modifier.fillMaxSize(),
            )
        }
        if (guide != null && shownAspect > 0f) FrameOverlay(guide, mediaModifier)
        Text(
            label,
            style = CardLabelStyle.copy(fontSize = 9.sp),
            color = Charis.Cream,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Charis.Ink.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        when {
            controller == null || controller.failed -> Text(
                "Sem prévia para este link.\nO vídeo sai certo mesmo assim.",
                style = MaterialTheme.typography.bodySmall,
                color = Charis.Cream,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Charis.Ink.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            controller.buffering && !controller.playing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Charis.Cream, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                if (controller.loadingCopy) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Preparando a prévia…",
                        style = MaterialTheme.typography.labelMedium,
                        color = Charis.Cream,
                        modifier = Modifier
                            .background(Charis.Ink.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            else -> Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Charis.Cream.copy(alpha = if (controller.playing) 0.55f else 0.92f))
                    .semantics { contentDescription = if (controller.playing) "Pausar a prévia" else "Assistir a prévia" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (controller.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Charis.Charcoal,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (controller != null && !controller.failed) {
            Text(
                Formats.clock(controller.positionMs / 1000) + (caption?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = Charis.Cream,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Charis.Ink.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Escurece o que sai do recorte e contorna o que fica; arrastar desliza a moldura. */
@Composable
private fun FrameOverlay(guide: FrameGuide, modifier: Modifier) {
    val current by rememberUpdatedState(guide)
    Canvas(
        modifier
            .semantics { contentDescription = "Parte do vídeo que fica no recorte. Arraste para mudar." }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    val g = current
                    val slack = if (g.horizontal) size.width * (1f - g.window.width) else size.height * (1f - g.window.height)
                    if (slack > 0f) {
                        change.consume()
                        g.onMove((if (g.horizontal) drag.x else drag.y) / slack)
                    }
                }
            },
    ) {
        val w = guide.window
        val left = size.width * w.left
        val top = size.height * w.top
        val right = left + size.width * w.width
        val bottom = top + size.height * w.height
        val shade = Charis.Ink.copy(alpha = 0.62f)
        if (top > 0f) drawRect(shade, Offset.Zero, Size(size.width, top))
        if (bottom < size.height) drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
        if (left > 0f) drawRect(shade, Offset(0f, top), Size(left, bottom - top))
        if (right < size.width) drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
        val stroke = 2.dp.toPx()
        drawRect(
            Charis.Cream,
            Offset(left + stroke / 2, top + stroke / 2),
            Size(right - left - stroke, bottom - top - stroke),
            style = Stroke(stroke),
        )
        // Alça no meio da borda que anda, indicando que dá para arrastar.
        val grip = 18.dp.toPx()
        val thick = 4.dp.toPx()
        if (guide.horizontal) {
            val cy = (top + bottom) / 2
            listOf(left, right).forEach { x ->
                drawRoundRect(Charis.Cream, Offset(x - thick / 2, cy - grip / 2), Size(thick, grip),
                    androidx.compose.ui.geometry.CornerRadius(thick / 2))
            }
        } else {
            val cx = (left + right) / 2
            listOf(top, bottom).forEach { y ->
                drawRoundRect(Charis.Cream, Offset(cx - grip / 2, y - thick / 2), Size(grip, thick),
                    androidx.compose.ui.geometry.CornerRadius(thick / 2))
            }
        }
    }
}
