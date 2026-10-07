package com.joohn.baixavideos.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.joohn.baixavideos.engine.InstagramSession
import com.joohn.baixavideos.ui.components.BrandHeader
import com.joohn.baixavideos.ui.components.TopStrip
import com.joohn.baixavideos.ui.theme.BaixaVideosTheme
import com.joohn.baixavideos.ui.theme.Brand
import com.joohn.baixavideos.util.AppVisibility
import kotlinx.coroutines.delay

/** Login no site oficial do Instagram; ao detectar a sessão, salva os cookies e fecha. */
class InstagramLoginActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            val view = webView
            if (view != null && view.canGoBack()) view.goBack() else finish()
        }
        setContent {
            BaixaVideosTheme {
                LoginScreen(
                    onClose = { finish() },
                    onCreated = { webView = it },
                    tryCapture = ::tryCapture,
                )
            }
        }
    }

    /** Retorna true quando o login foi detectado (e encerra a tela). */
    private fun tryCapture(manual: Boolean): Boolean {
        if (finished) return true
        if (InstagramSession.captureFromWebView(this)) {
            finished = true
            Toast.makeText(this, "Instagram conectado", Toast.LENGTH_SHORT).show()
            finish()
            return true
        }
        if (manual) Toast.makeText(this, "Ainda não detectei o login. Entre na sua conta primeiro.", Toast.LENGTH_SHORT).show()
        return false
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
    }

    override fun onStop() {
        AppVisibility.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginScreen(onClose: () -> Unit, onCreated: (WebView) -> Unit, tryCapture: (Boolean) -> Boolean) {
    val c = Brand.colors
    var loading by remember { mutableStateOf(true) }

    // O Instagram é um app de página única: depois do login a página pode nem recarregar.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_500)
            if (tryCapture(false)) break
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(c.paper)) {
                TopStrip(recording = false)
                BrandHeader(
                    title = "INSTAGRAM",
                    subtitle = "O LOGIN FICA SÓ NESTE APARELHO",
                    leading = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = c.ink)
                        }
                    },
                    actions = {
                        TextButton(onClick = { tryCapture(true) }) { Text("Concluir") }
                    },
                )
                HorizontalDivider(color = c.line)
            }
        },
        containerColor = c.paper,
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                tryCapture(false)
                            }
                        }
                        loadUrl("https://www.instagram.com/accounts/login/")
                        onCreated(this)
                    }
                },
            )
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = c.blue,
                    trackColor = c.line,
                )
            }
        }
    }
}
