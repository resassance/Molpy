package app.musicplayer.playback

import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.musicplayer.queue.QueueCommand
import app.musicplayer.queue.QueueOrder
import app.musicplayer.queue.QueuePlaybackPort

/** Exposes queue navigation without pretending the single resolved source is a native playlist. */
@UnstableApi
internal class QueueSessionPlayer(
    player: Player,
    private val host: QueuePlaybackPort,
    private val dispatch: (QueueCommand) -> Unit,
) : ForwardingPlayer(player) {
    private val listeners = mutableMapOf<Player.Listener, Player.Listener>()
    private var publishedCommands: Player.Commands? = null
    private var publishedShuffle: Boolean? = null

    override fun getAvailableCommands(): Player.Commands {
        val commands =
            super.getAvailableCommands()
                .buildUpon()
                .remove(Player.COMMAND_SET_MEDIA_ITEM)
                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
        commands.add(Player.COMMAND_SET_SHUFFLE_MODE)
        val q = host.queue.value
        for (command in
            listOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
            if (q.currentEntryId != null && QueueOrder.next(q) != null) commands.add(command)
            else commands.remove(command)
        }
        for (command in
            listOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
            if (q.currentEntryId != null && (QueueOrder.previous(q) != null || q.positionMs > 0))
                commands.add(command)
            else commands.remove(command)
        }
        return commands.build()
    }

    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)

    override fun addListener(listener: Player.Listener) {
        if (listener in listeners) return
        val wrapper =
            object : Player.Listener by listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    listener.onAvailableCommandsChanged(this@QueueSessionPlayer.availableCommands)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    val filtered = FlagSet.Builder()
                    for (index in 0 until events.size()) {
                        val event = events.get(index)
                        if (event != Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) filtered.add(event)
                    }
                    val flags = filtered.build()
                    if (flags.size() > 0)
                        listener.onEvents(this@QueueSessionPlayer, Player.Events(flags))
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {}
            }
        listeners[listener] = wrapper
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    /** Called by the service on its main dispatcher when the normalized queue changes. */
    fun onQueueChanged() {
        val commands = availableCommands
        val shuffle = shuffleModeEnabled
        val commandChanged = publishedCommands != commands
        val shuffleChanged = publishedShuffle != shuffle
        if (!commandChanged && !shuffleChanged) return
        publishedCommands = commands
        publishedShuffle = shuffle
        val flags = FlagSet.Builder()
        if (commandChanged) flags.add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
        if (shuffleChanged) flags.add(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
        val events = Player.Events(flags.build())
        for (listener in listeners.keys.toList()) {
            if (commandChanged) listener.onAvailableCommandsChanged(commands)
            if (shuffleChanged) listener.onShuffleModeEnabledChanged(shuffle)
            listener.onEvents(this, events)
        }
    }

    override fun getShuffleModeEnabled() = host.queue.value.shuffleEnabled

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) =
        dispatch(QueueCommand.SetShuffle(shuffleModeEnabled))

    override fun play() = host.play()

    override fun pause() = host.pause()

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) host.play() else host.pause()
    }

    override fun seekTo(positionMs: Long) = host.seek(positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == 0) host.seek(positionMs)
    }

    override fun seekToDefaultPosition() = host.seek(0)

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (mediaItemIndex == 0) host.seek(0)
    }

    override fun seekBack() = host.seek((currentPosition - seekBackIncrement).coerceAtLeast(0))

    override fun seekForward() = host.seek(currentPosition + seekForwardIncrement)

    override fun seekToNext() = dispatch(QueueCommand.Next)

    override fun seekToNextMediaItem() = seekToNext()

    override fun seekToPrevious() = dispatch(QueueCommand.Previous)

    override fun seekToPreviousMediaItem() = seekToPrevious()

    override fun stop() = host.stop()
}
