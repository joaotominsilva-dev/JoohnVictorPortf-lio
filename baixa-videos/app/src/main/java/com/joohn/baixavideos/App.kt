package com.joohn.baixavideos

import android.app.Application
import com.joohn.baixavideos.data.Prefs
import com.joohn.baixavideos.engine.Downloads
import com.joohn.baixavideos.engine.Engine
import com.joohn.baixavideos.engine.InstagramSession
import com.joohn.baixavideos.service.Notifications

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        InstagramSession.init(this)
        Notifications.createChannels(this)
        Downloads.init(this)
        // Na primeira abertura o motor descompacta Python e FFmpeg (alguns segundos, em segundo plano).
        Engine.start(this)
    }
}
