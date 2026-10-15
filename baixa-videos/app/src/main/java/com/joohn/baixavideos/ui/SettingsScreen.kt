package com.joohn.baixavideos.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joohn.baixavideos.BuildConfig
import com.joohn.baixavideos.data.Prefs
import com.joohn.baixavideos.engine.Engine
import com.joohn.baixavideos.engine.InstagramSession
import com.joohn.baixavideos.engine.MediaSaver
import com.joohn.baixavideos.ui.components.CharisSwitch
import com.joohn.baixavideos.ui.components.Headline
import com.joohn.baixavideos.ui.components.PrimaryButton
import com.joohn.baixavideos.ui.components.TopBar
import com.joohn.baixavideos.ui.theme.Charis

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    val engine by Engine.state.collectAsStateWithLifecycle()
    val update by Engine.update.collectAsStateWithLifecycle()
    val instagram by InstagramSession.loggedIn.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.statusBarsPadding())
        TopBar("Ajustes")
        Headline("Seus ajustes", "Veja e mude como o app funciona.", Modifier.padding(top = 6.dp))
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
            SettingRow(
                title = "Conta do Instagram",
                subtitle = if (instagram) "Conectada · toque para sair" else "Entre para baixar vídeos que pedem login",
                onClick = {
                    if (instagram) confirmLogout = true
                    else context.startActivity(Intent(context, InstagramLoginActivity::class.java))
                },
            )
            SettingRow(
                title = "Motor de download",
                subtitle = engineText(engine, update),
                onClick = { Engine.requestUpdate() },
            )
            SettingRow(
                title = "Atualizar sozinho",
                subtitle = "Procura versão nova a cada 12 horas",
                onClick = { Prefs.update { it.copy(autoUpdate = !it.autoUpdate) } },
                trailing = {
                    CharisSwitch(
                        checked = settings.autoUpdate,
                        onCheckedChange = { value -> Prefs.update { it.copy(autoUpdate = value) } },
                        onDark = true,
                    )
                },
            )
            SettingRow(
                title = "Versão nightly",
                subtitle = "Correções no mesmo dia, se algum site parar de baixar",
                onClick = {
                    Prefs.update { it.copy(nightly = !it.nightly) }
                    Engine.requestUpdate()
                },
                trailing = {
                    CharisSwitch(
                        checked = settings.nightly,
                        onCheckedChange = { value ->
                            Prefs.update { it.copy(nightly = value) }
                            Engine.requestUpdate()
                        },
                        onDark = true,
                    )
                },
            )
            SettingRow(
                title = "Pasta dos arquivos",
                subtitle = "${MediaSaver.publicPath} · aparece na galeria",
                onClick = {
                    try {
                        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "Abra o app de arquivos e procure ${MediaSaver.publicPath}.", Toast.LENGTH_LONG).show()
                    }
                },
            )
            SettingRow(
                title = "Sobre",
                subtitle = "Charis Downloader ${BuildConfig.VERSION_NAME} · licenças",
                onClick = { about = true },
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (update.inFlight) "Atualizando o motor…" else "Atualizar motor agora",
                onClick = { Engine.requestUpdate() },
                enabled = engine is Engine.State.Ready && !update.inFlight,
                icon = Icons.Filled.Refresh,
                onDark = true,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Feito com yt-dlp, FFmpeg e Python",
                style = MaterialTheme.typography.bodySmall,
                color = Charis.OnDarkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BottomSpace()
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
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Charis Downloader ${BuildConfig.VERSION_NAME}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Baixa vídeos e áudios do Instagram, YouTube e TikTok, inteiros ou só um trecho.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Feito com yt-dlp (Unlicense), youtubedl-android (GPL-3.0), FFmpeg (GPL), Python, " +
                            "QuickJS e AndroidX Media3 (Apache 2.0). Fonte Archivo (SIL Open Font License 1.1).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Charis.OnDarkSoft,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Baixe só o que você tem direito de usar e respeite quem criou o conteúdo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Charis.OnDarkSoft,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text("Fechar") } },
        )
    }
}

private fun engineText(engine: Engine.State, update: Engine.UpdateState): String {
    val version = (engine as? Engine.State.Ready)?.version
    val status = when {
        engine is Engine.State.Starting -> "preparando"
        engine is Engine.State.Failed -> "não iniciou: ${engine.message}"
        update is Engine.UpdateState.Checking -> "procurando atualização…"
        update is Engine.UpdateState.Downloading -> "baixando a versão ${update.version}…"
        update is Engine.UpdateState.WaitingIdle -> "a versão ${update.version} instala quando os downloads acabarem"
        update is Engine.UpdateState.Updated -> "atualizado agora"
        update is Engine.UpdateState.UpToDate -> "já é a versão mais nova"
        update is Engine.UpdateState.Failed -> "falha ao atualizar: ${update.message}"
        else -> "toque para procurar atualização"
    }
    return if (version != null) "yt-dlp $version · $status" else status.replaceFirstChar { it.uppercase() }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = if (trailing != null) Role.Switch else Role.Button, onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Charis.Cream)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Charis.OnDarkSoft)
            }
            Spacer(Modifier.width(12.dp))
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Charis.OnDarkSoft,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        HorizontalDivider(color = Charis.DarkLine)
    }
}
