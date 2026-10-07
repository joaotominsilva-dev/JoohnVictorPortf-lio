package com.joohn.baixavideos.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joohn.baixavideos.BuildConfig
import com.joohn.baixavideos.data.Prefs
import com.joohn.baixavideos.engine.Downloads
import com.joohn.baixavideos.engine.Engine
import com.joohn.baixavideos.engine.InstagramSession
import com.joohn.baixavideos.engine.MediaSaver
import com.joohn.baixavideos.ui.components.BrandHeader
import com.joohn.baixavideos.ui.components.BrandPanel
import com.joohn.baixavideos.ui.components.SectionLabel
import com.joohn.baixavideos.ui.components.TopStrip
import com.joohn.baixavideos.ui.theme.Brand
import com.joohn.baixavideos.ui.theme.MonoLabel

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = Brand.colors
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    val engine by Engine.state.collectAsStateWithLifecycle()
    val update by Engine.update.collectAsStateWithLifecycle()
    val tasks by Downloads.tasks.collectAsStateWithLifecycle()
    val instagram by InstagramSession.loggedIn.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column(Modifier.background(c.paper)) {
                TopStrip(recording = tasks.any { it.status.isActive })
                BrandHeader(
                    title = "AJUSTES",
                    subtitle = "BAIXA VÍDEOS · JOOHN",
                    leading = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = c.ink)
                        }
                    },
                )
                HorizontalDivider(color = c.line)
            }
        },
        containerColor = c.paper,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Section("INSTAGRAM") {
                Text(
                    if (instagram) {
                        "Conta conectada. Os downloads do Instagram usam o seu login."
                    } else {
                        "Muitos vídeos do Instagram só baixam com login. Você entra pelo site oficial do " +
                            "Instagram, aqui dentro do app, e o acesso fica salvo só neste aparelho."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                )
                Spacer(Modifier.height(12.dp))
                if (instagram) {
                    OutlinedButton(onClick = { confirmLogout = true }, shape = RoundedCornerShape(6.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sair da conta")
                    }
                } else {
                    Button(
                        onClick = { context.startActivity(Intent(context, InstagramLoginActivity::class.java)) },
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Entrar no Instagram")
                    }
                }
            }

            Section("MOTOR DE DOWNLOAD") {
                val version = (engine as? Engine.State.Ready)?.version
                Text(
                    "yt-dlp " + (version ?: "…"),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                )
                Text(updateText(engine, update), style = MonoLabel, color = c.inkSoft)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { Engine.requestUpdate() },
                    enabled = engine is Engine.State.Ready && !update.inFlight,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    if (update.inFlight) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Atualizar agora")
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = "Atualizar automaticamente",
                    subtitle = "Procura versão nova ao abrir o app, no máximo a cada 12 horas.",
                    checked = settings.autoUpdate,
                    onChange = { value -> Prefs.update { it.copy(autoUpdate = value) } },
                )
                SwitchRow(
                    title = "Versão nightly",
                    subtitle = "Recebe correções no mesmo dia. Ligue se algum site parar de baixar.",
                    checked = settings.nightly,
                    onChange = { value ->
                        Prefs.update { it.copy(nightly = value) }
                        Engine.requestUpdate()
                    },
                )
            }

            Section("COMPARTILHAR") {
                SwitchRow(
                    title = "Baixar ao compartilhar",
                    subtitle = "Ao compartilhar um link com o app, o download começa sozinho no formato e na qualidade escolhidos.",
                    checked = settings.autoStartOnShare,
                    onChange = { value -> Prefs.update { it.copy(autoStartOnShare = value) } },
                )
            }

            Section("ARQUIVOS") {
                Text(
                    "Tudo fica em ${MediaSaver.publicPath} e aparece na galeria e no app de música.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                )
            }

            Section("SOBRE") {
                Text("Baixa Vídeos ${BuildConfig.VERSION_NAME} · JOOHN", style = MaterialTheme.typography.titleSmall, color = c.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Feito com yt-dlp, youtubedl-android (GPL-3.0), FFmpeg, Python e QuickJS. " +
                        "Fontes Archivo e Courier Prime (SIL Open Font License 1.1).",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkSoft,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Baixe só o que você tem direito de usar e respeite quem criou o conteúdo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkSoft,
                )
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sair do Instagram?") },
            text = { Text("O app apaga o login salvo. Vídeos que exigem conta voltam a falhar até você entrar de novo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    InstagramSession.logout(context)
                }) { Text("Sair") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancelar") } },
        )
    }
}

private fun updateText(engine: Engine.State, update: Engine.UpdateState): String = when {
    engine is Engine.State.Starting -> "PREPARANDO O MOTOR"
    engine is Engine.State.Failed -> "ERRO: ${engine.message}"
    update is Engine.UpdateState.Checking -> "PROCURANDO ATUALIZAÇÃO…"
    update is Engine.UpdateState.Downloading -> "BAIXANDO A VERSÃO ${update.version}…"
    update is Engine.UpdateState.WaitingIdle -> "VERSÃO ${update.version} INSTALA QUANDO OS DOWNLOADS ACABAREM"
    update is Engine.UpdateState.Updated -> "ATUALIZADO AGORA"
    update is Engine.UpdateState.UpToDate -> "JÁ ESTÁ NA VERSÃO MAIS NOVA"
    update is Engine.UpdateState.Failed -> "FALHA AO ATUALIZAR: ${update.message}"
    else -> "PRONTO"
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionLabel(title)
        Spacer(Modifier.height(10.dp))
        BrandPanel(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Brand.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.blue),
        )
    }
}
