package com.joohn.baixavideos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.joohn.baixavideos.data.Prefs
import com.joohn.baixavideos.engine.Downloads
import com.joohn.baixavideos.engine.TaskStatus
import com.joohn.baixavideos.ui.DownloadDraft
import com.joohn.baixavideos.ui.InfoState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Percorre o app como uma pessoa e fotografa cada tela para conferência visual no CI. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun captureScreens(): Unit = runBlocking {
        TestSupport.awaitEngine(context)
        // Vitrine limpa: sem itens de outros testes (inclusive os de sites reais).
        Downloads.clearFinished()
        Prefs.welcomeSeen = false
        // Sem a caixa de permissão de notificação no meio das fotos.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)

        TestHttpServer(TestSupport.testAssets, "dash", delayMs = 300, chunkDelayMs = 400).use { server ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                settle()
                TestSupport.screenshot(device, context, "01_boas_vindas")
                TestSupport.dismissSystemDialogs(device)
                device.findObject(By.text("Começar"))?.click()
                settle()

                var draft: DownloadDraft? = null
                scenario.onActivity { draft = it.vm.draft }
                val home = draft!!
                scenario.onActivity { home.updateText(server.url("Ensaio%20em%20fita%20VHS.mpd")) }
                awaitInfo(scenario) { home }
                settle()
                TestSupport.screenshot(device, context, "02_inicio")

                // Liga o corte; a duração vem do player da prévia (o link de teste não informa).
                scenario.onActivity { home.setTrimEnabled(true) }
                waitUntil(20.seconds) { onMain(scenario) { home.durationSec != null } }
                scenario.onActivity { home.setTrimRange(1, 2) }
                scrollDown()
                delay(2_500) // o quadro da prévia aparece
                TestSupport.screenshot(device, context, "03_corte_com_previa")

                if (!clickWithScroll(Pattern.compile("Baixar.*"))) {
                    Log.w(TAG, "Botão Baixar não encontrado; enfileirando direto")
                    scenario.onActivity { activity -> home.toStart()?.enqueue(activity) }
                }
                delay(500)
                device.findObject(By.text("Downloads"))?.click()
                waitUntil(90.seconds) {
                    Downloads.tasks.value.firstOrNull()?.let { !it.status.isActive || it.status == TaskStatus.DOWNLOADING } ?: false
                }
                delay(700)
                TestSupport.screenshot(device, context, "04_baixando")
                withTimeoutOrNull(3.minutes) {
                    Downloads.tasks.first { list -> list.firstOrNull()?.status?.isActive == false }
                }
                Log.i(TAG, "Download pela interface: ${Downloads.tasks.value.firstOrNull()}")
                delay(1_000)
                TestSupport.screenshot(device, context, "05_concluido")

                // Texto que não é link vira busca no YouTube (o CI não alcança o YouTube; fica só o campo).
                device.findObject(By.text("Início"))?.click()
                delay(600)
                scenario.onActivity { home.updateText("receita de bolo de cenoura") }
                settle()
                TestSupport.screenshot(device, context, "09_busca")
                scenario.onActivity { home.updateText("") }

                device.findObject(By.text("Ajustes"))?.click()
                device.wait(Until.hasObject(By.text("Seus ajustes")), 5_000)
                delay(800)
                TestSupport.screenshot(device, context, "06_ajustes")
            }

            // Compartilhar um link de outro app abre a janela de opções por cima dele.
            val share = Intent(context, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Olha esse vídeo: ${server.url("Bastidores%20da%20campanha.mpd")}")
            ActivityScenario.launch<ShareActivity>(share).use { scenario ->
                var draft: DownloadDraft? = null
                scenario.onActivity { draft = it.vm.draft }
                val sheet = draft!!
                awaitInfo(scenario) { sheet }
                settle()
                TestSupport.screenshot(device, context, "07_compartilhar")
                scenario.onActivity { sheet.setTrimEnabled(true) }
                waitUntil(20.seconds) { onMain(scenario) { sheet.durationSec != null } }
                scrollDown()
                delay(2_500)
                TestSupport.screenshot(device, context, "08_compartilhar_corte")
            }
        }
    }

    private suspend fun <A : android.app.Activity> awaitInfo(scenario: ActivityScenario<A>, draft: () -> DownloadDraft) {
        val ok = waitUntil(60.seconds) { onMain(scenario) { draft().info !is InfoState.Loading } }
        Log.i(TAG, "Prévia do link: ${onMain(scenario) { draft().info }} (ok=$ok)")
    }

    private fun <A : android.app.Activity, T> onMain(scenario: ActivityScenario<A>, read: () -> T): T {
        var value: T? = null
        scenario.onActivity { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private suspend fun waitUntil(timeout: Duration, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeout) {
            while (!condition()) delay(250)
            true
        } ?: false

    /** Procura um texto na tela, rolando para baixo algumas vezes, e toca nele. */
    private fun clickWithScroll(text: Pattern, attempts: Int = 4): Boolean {
        repeat(attempts) {
            TestSupport.dismissSystemDialogs(device)
            val target = device.findObject(By.text(text))
            if (target != null) {
                target.click()
                return true
            }
            scrollDown()
        }
        return false
    }

    private fun scrollDown() {
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, (h * 0.75).toInt(), w / 2, (h * 0.3).toInt(), 30)
        device.waitForIdle()
    }

    private suspend fun settle() {
        TestSupport.dismissSystemDialogs(device)
        device.waitForIdle()
        delay(1_500) // o emulador (renderização por software) leva alguns quadros para redesenhar
    }
}
