package com.charis.shoot

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.charis.shoot.drive.AuthManager
import com.charis.shoot.ui.CharisTheme
import com.charis.shoot.ui.Root

class MainActivity : ComponentActivity() {

    private lateinit var app: CharisApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = AuthManager(this)
        app = CharisApp(applicationContext, auth)
        // Seletor de pastas do Android: memória interna, cartão SD, HD/pendrive USB, leitor de cartão.
        val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) {
                val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { contentResolver.takePersistableUriPermission(tree, rw) }
                    .recoverCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                app.onFolderPicked(tree)
            }
        }
        app.pickFolder = { picker.launch(null) }
        setContent {
            LaunchedEffect(app.dark) {
                val style = if (app.dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CharisTheme(dark = app.dark) { Root(app) }
        }
        app.start()
    }

    override fun onStop() {
        super.onStop()
        app.flushNow()
    }
}
