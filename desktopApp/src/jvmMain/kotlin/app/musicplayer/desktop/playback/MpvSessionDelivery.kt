package app.musicplayer.desktop.playback

import kotlinx.coroutines.*

internal suspend fun deliverMpvSession(
    dispatcher: CoroutineDispatcher,
    create: suspend () -> MpvSession,
): MpvSession {
    var acquired: MpvSession? = null
    try {
        return withContext(dispatcher) { create().also { acquired = it } }
    } catch (error: Throwable) {
        acquired?.close()
        throw error
    }
}
