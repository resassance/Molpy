package app.musicplayer.queue

import app.musicplayer.catalog.PlaylistRowIdentity
import app.musicplayer.model.QueueEntry

data class PlaylistQueueRow(val identity: PlaylistRowIdentity, val entry: QueueEntry?)
