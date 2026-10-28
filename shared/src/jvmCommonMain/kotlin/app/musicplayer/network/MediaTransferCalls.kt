package app.musicplayer.network

import okhttp3.Call

/** Streaming bodies use read inactivity limits; metadata keeps the client's total-call deadline. */
fun Call.Factory.forMediaTransfers(): Call.Factory = Call.Factory { request ->
    mediaCall(newCall(request))
}

private fun mediaCall(call: Call): Call {
    call.timeout().clearTimeout()
    return object : Call by call {
        override fun clone(): Call = mediaCall(call.clone())
    }
}
