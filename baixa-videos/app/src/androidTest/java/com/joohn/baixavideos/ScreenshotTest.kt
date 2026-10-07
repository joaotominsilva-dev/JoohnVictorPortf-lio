package com.joohn.baixavideos

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.Downloads
import com.joohn.baixavideos.engine.TaskStatus
import com.joohn.baixavideos.engine.VideoQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Fotografa as telas principais (claro e escuro) para conferência visual no CI. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun captureMainScreens(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        TestSupport.awaitEngine(context)
        // Vitrine limpa: sem itens de outros testes (inclusive os de sites reais).
        Downloads.clearFinished()

        ActivityScenario.launch(MainActivity::class.java).use {
            device.waitForIdle()
            delay(1_500)
            TestSupport.screenshot(device, context, "01_inicio")

            TestHttpServer(TestSupport.testAssets, "dash", delayMs = 300, chunkDelayMs = 400).use { server ->
                val task = Downloads.enqueue(
                    context, server.url("Ensaio%20em%20fita%20VHS.mpd"), DownloadMode.VIDEO, VideoQuality.P1080,
                )
                withTimeoutOrNull(90.seconds) {
                    Downloads.tasks.first { list ->
                        val t = list.firstOrNull { it.id == task.id }
                        t == null || !t.status.isActive ||
                            (t.status == TaskStatus.DOWNLOADING && (t.progress ?: 0f) > 0.1f)
                    }
                }
                delay(700) // o emulador (renderização por software) leva alguns quadros para redesenhar
                TestSupport.screenshot(device, context, "02_baixando")
                withTimeout(3.minutes) {
                    Downloads.tasks.first { list -> list.firstOrNull { it.id == task.id }?.status?.isActive == false }
                }
                delay(1_000)
                TestSupport.screenshot(device, context, "03_concluido")
            }

            device.findObject(By.desc("Ajustes"))?.click()
            device.wait(Until.hasObject(By.text("AJUSTES")), 5_000)
            delay(800)
            TestSupport.screenshot(device, context, "04_ajustes")
            device.pressBack()
        }

        instrumentation.uiAutomation.executeShellCommand("cmd uimode night yes").close()
        delay(1_000)
        ActivityScenario.launch(MainActivity::class.java).use {
            device.waitForIdle()
            delay(1_500)
            TestSupport.screenshot(device, context, "05_inicio_escuro")
        }
        instrumentation.uiAutomation.executeShellCommand("cmd uimode night no").close()
    }
}
