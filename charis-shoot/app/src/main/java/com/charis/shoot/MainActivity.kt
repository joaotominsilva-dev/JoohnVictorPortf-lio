package com.charis.shoot

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
}
