package app.musicplayer

import android.app.Application

class MusicApplication : Application() {
    lateinit var graph: AppGraph
        private set
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}
