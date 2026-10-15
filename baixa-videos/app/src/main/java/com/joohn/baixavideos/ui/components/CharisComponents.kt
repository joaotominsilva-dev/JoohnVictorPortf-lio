package com.joohn.baixavideos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.joohn.baixavideos.R
import com.joohn.baixavideos.ui.theme.CardLabelStyle
import com.joohn.baixavideos.ui.theme.Charis

@Composable
fun CharisLogo(modifier: Modifier = Modifier, tint: Color = Charis.Sage, size: Dp = 26.dp) {
    Icon(
        painterResource(R.drawable.ic_charis_logo),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** Barra do topo: logo à esquerda e um texto curto à direita, como na referência. */
@Composable
fun TopBar(trailing: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharisLogo()
        Spacer(Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.labelLarge, color = Charis.OnDarkSoft)
    }
}

@Composable
fun Headline(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Charis.Cream)
        if (subtitle != null) {
            Spacer(Modifier.size(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Charis.OnDarkSoft)
        }
    }
}

/** Abas em pílula. Sobre fundo escuro a selecionada é sálvia; sobre creme, sálvia escuro. */
@Composable
fun <T> PillTabs(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDark: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val container = when {
                !isSelected -> Color.Transparent
                onDark -> Charis.Sage
                else -> Charis.SageDeep
            }
            val content = when {
                isSelected && onDark -> Charis.Charcoal
                isSelected -> Charis.Cream
                onDark -> Charis.Cream.copy(alpha = 0.78f)
                else -> Charis.InkSoft
            }
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(container)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(value) })
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Charis.Charcoal2,
    content: Color = Charis.Cream,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
    }
}

/** Bloco sálvia claro, de cantos arredondados em cima, onde ficam os cartões. */
fun Modifier.well(): Modifier = this
    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
    .background(Charis.SageLight)

@Composable
fun CreamCard(
    modifier: Modifier = Modifier,
    color: Color = Charis.Cream,
    padding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .padding(padding),
    ) {
        CompositionLocalProvider(LocalContentColor provides Charis.Ink) { content() }
    }
}

@Composable
fun CardLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = CardLabelStyle, color = Charis.InkSoft, modifier = modifier)
}

/** Listras diagonais finas: espaço vazio esperando conteúdo, como nos horários livres da referência. */
fun Modifier.hatched(
    color: Color = Charis.SageDeep.copy(alpha = 0.16f),
    corner: Dp = 14.dp,
): Modifier = drawBehind {
    val shape = Path().apply { addRoundRect(RoundRect(size.toRect(), CornerRadius(corner.toPx()))) }
    clipPath(shape) {
        val step = 12.dp.toPx()
        val stroke = 4.5.dp.toPx()
        var x = -size.height
        while (x < size.width + step) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stroke)
            x += step
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onDark: Boolean = false,
) {
    val container = if (onDark) Charis.Sage else Charis.Charcoal
    val content = if (onDark) Charis.Charcoal else Charis.Cream
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.35f),
            disabledContentColor = content.copy(alpha = 0.7f),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Botão pequeno em pílula, cheio (grafite) ou só com contorno. */
@Composable
fun SmallPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    container: Color = Charis.Charcoal,
    icon: ImageVector? = null,
) {
    val shape = CircleShape
    val base = modifier
        .clip(shape)
        .then(if (filled) Modifier.background(container) else Modifier.border(1.dp, Charis.Sage, shape))
        .clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 40.dp)
        .padding(horizontal = 14.dp)
    Row(base, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        val color = if (filled) Charis.Cream else Charis.Ink
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

@Composable
fun StatusChip(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = CardLabelStyle.copy(fontSize = 9.5.sp, letterSpacing = 0.8.sp),
        color = content,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
fun CharisSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Charis.Cream,
            checkedTrackColor = if (onDark) Charis.Sage else Charis.SageDeep,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Charis.Cream,
            uncheckedTrackColor = if (onDark) Color(0xFF4A4B42) else Charis.SageLight,
            uncheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = Charis.Cream.copy(alpha = 0.8f),
            disabledUncheckedTrackColor = (if (onDark) Color(0xFF4A4B42) else Charis.SageLight).copy(alpha = 0.6f),
            disabledUncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** Miniatura com a etiqueta da plataforma (IG, YT, TT). */
@Composable
fun Thumb(url: String?, badge: String, audio: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Charis.Cream2),
        contentAlignment = Alignment.Center,
    ) {
        CharisLogo(tint = Charis.Sage.copy(alpha = 0.55f), size = 22.dp)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Text(
            badge,
            style = CardLabelStyle.copy(fontSize = 8.sp, letterSpacing = 0.4.sp),
            color = Charis.Cream,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Charis.Ink.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        if (audio) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Charis.Cream,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Charis.Ink.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                    .padding(2.dp)
                    .size(11.dp),
            )
        }
    }
}
