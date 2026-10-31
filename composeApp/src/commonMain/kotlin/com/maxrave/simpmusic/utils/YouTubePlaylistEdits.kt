package com.maxrave.simpmusic.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Invalidate quick-add receipts in other open screens after an acknowledged account edit. */
object YouTubePlaylistEdits {
    data class Edit(val playlistId: String, val videoId: String? = null)

    private val events = MutableSharedFlow<Edit>(extraBufferCapacity = 16)
    val changes = events.asSharedFlow()

    fun removed(playlistId: String, videoId: String) {
        events.tryEmit(Edit(playlistId.removePrefix("VL"), videoId))
    }

    fun deleted(playlistId: String) {
        events.tryEmit(Edit(playlistId.removePrefix("VL")))
    }
}
