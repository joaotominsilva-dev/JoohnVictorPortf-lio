package com.joohn.baixavideos.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joohn.baixavideos.BuildConfig
import com.joohn.baixavideos.ui.theme.ArchivoExpanded
import com.joohn.baixavideos.ui.theme.Brand
import com.joohn.baixavideos.ui.theme.MonoLabel

/** Cantoneiras de visor de câmera, a assinatura visual do portfólio. */
fun Modifier.viewfinder(color: Color, length: Dp = 16.dp, stroke: Dp = 2.dp, inset: Dp = 0.dp) =
    drawWithContent {
        drawContent()
        val l = length.toPx()
        val s = stroke.toPx()
        val i = inset.toPx() + s / 2
        val w = size.width
        val h = size.height
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = s, cap = StrokeCap.Square)
        line(i, i, i + l, i); line(i, i, i, i + l)
        line(w - i - l, i, w - i, i); line(w - i, i, w - i, i + l)
        line(i, h - i, i + l, h - i); line(i, h - i - l, i, h - i)
        line(w - i - l, h - i, w - i, h - i); line(w - i, h - i - l, w - i, h - i)
    }

/** Painel "papel" com borda fina e sombra dura deslocada. */
@Composable
fun BrandPanel(
    modifier: Modifier = Modifier,
    shadow: Dp = 3.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Brand.colors
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.padding(end = shadow, bottom = shadow)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .background(c.lineDark.copy(alpha = 0.55f), shape),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.panel)
                .border(1.dp, c.line, shape)
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
fun RecDot(active: Boolean, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    val c = Brand.colors
    val transition = rememberInfiniteTransition(label = "rec")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "recAlpha",
    )
    Box(
        modifier
            .size(size)
            .alpha(if (active) pulse else 1f)
            .clip(CircleShape)
            .background(if (active) c.rec else c.inkSoft.copy(alpha = 0.6f)),
    )
}

/** Faixa azul de "intranet" no topo, atrás da barra de status. */
@Composable
fun TopStrip(recording: Boolean, modifier: Modifier = Modifier) {
    val c = Brand.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.strip)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecDot(active = recording, size = 7.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            if (recording) "REC" else "STBY",
            style = MonoLabel.copy(fontWeight = FontWeight.Bold),
            color = c.onStrip,
        )
        Spacer(Modifier.width(14.dp))
        Text("JOOHN / DOWNLOADER", style = MonoLabel, color = c.onStrip.copy(alpha = 0.85f), maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text("v${BuildConfig.VERSION_NAME}", style = MonoLabel, color = c.onStrip.copy(alpha = 0.75f))
    }
}

/** Marca: quadrado azul com "J" e o nome do app em Archivo expandida. */
@Composable
fun BrandHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Brand.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = if (leading != null) 4.dp else 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
        } else {
            Box(Modifier.padding(end = 2.dp, bottom = 2.dp)) {
                Box(
                    Modifier
                        .size(34.dp)
                        .offset(2.dp, 2.dp)
                        .background(c.lineDark, RoundedCornerShape(4.dp)),
                )
                Box(
                    Modifier
                        .size(34.dp)
                        .background(c.blue, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("J", color = Color.White, fontFamily = ArchivoExpanded, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontFamily = ArchivoExpanded,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                letterSpacing = 0.4.sp,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = MonoLabel.copy(fontSize = 10.sp, letterSpacing = 1.6.sp), color = c.inkSoft, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, content = actions)
    }
}

/** Título de seção em mono, com um traço que vai até a borda. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = Brand.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MonoLabel.copy(fontWeight = FontWeight.Bold), color = c.inkSoft)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .padding(end = 8.dp)
                .height(1.dp)
                .background(c.line),
        )
        trailing()
    }
}
