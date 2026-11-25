package app.muse.player

import android.app.Application
import app.muse.player.data.Netease
import app.muse.player.player.PlayerHub
import app.muse.player.player.SpatialAudio

class MuseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Netease.init(this)
        SpatialAudio.init(this)
        PlayerHub.init(this)
    }

    companion object {
        lateinit var instance: MuseApp
            private set
    }
}
