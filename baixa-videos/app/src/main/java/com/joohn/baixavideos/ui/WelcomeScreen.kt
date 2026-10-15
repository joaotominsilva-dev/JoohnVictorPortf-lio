package com.joohn.baixavideos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joohn.baixavideos.engine.Engine
import com.joohn.baixavideos.ui.components.CharisLogo
import com.joohn.baixavideos.ui.components.PrimaryButton
import com.joohn.baixavideos.ui.theme.Charis

/** Primeira abertura: apresenta o app enquanto o motor é preparado (só demora desta vez). */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val engine by Engine.state.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxSize()
            .background(Charis.Cream),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Charis.Charcoal)
                    .drawBehind {
                        // Arcos concêntricos saindo dos cantos, como o cartão de abertura da referência.
                        val stroke = Stroke(width = 1.dp.toPx())
                        val color = Charis.Sage.copy(alpha = 0.22f)
                        listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
                            .forEach { corner ->
                                for (i in 1..9) drawCircle(color, radius = i * size.width * 0.075f, center = corner, style = stroke)
                            }
                    },
                contentAlignment = Alignment.Center,
            ) {
                CharisLogo(size = 150.dp)
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "Baixe vídeos sem complicação.",
                style = MaterialTheme.typography.headlineMedium,
                color = Charis.Ink,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Instagram, YouTube e TikTok em MP4 ou MP3. Corte e salve só o trecho que você quiser.",
                style = MaterialTheme.typography.bodyMedium,
                color = Charis.InkSoft,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Começar", onClick = onStart)
            Spacer(Modifier.height(16.dp))
            val status = when (val state = engine) {
                Engine.State.Starting -> "Preparando o motor de download · só na primeira vez"
                is Engine.State.Ready -> "Motor pronto · yt-dlp ${state.version}"
                is Engine.State.Failed -> "O motor não iniciou. Ele tenta de novo ao abrir o app."
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = Charis.InkSoft, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            val bar = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
            if (engine == Engine.State.Starting) {
                LinearProgressIndicator(modifier = bar, color = Charis.SageDeep, trackColor = Charis.Cream2, gapSize = 0.dp)
            } else {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = bar,
                    color = Charis.SageDeep,
                    trackColor = Charis.Cream2,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}
