package com.maxrave.media3.exoplayer

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two decoder surfaces share the audio envelope's clock. */
data class CrossfadeVideoFrame(
    val outgoing: Player? = null,
    val incoming: Player? = null,
    val progress: Float = 0f,
)

object CrossfadeVideoState {
    private val mutableFrame = MutableStateFlow(CrossfadeVideoFrame())
    val frame = mutableFrame.asStateFlow()
    private var visibleViews = 0
    val visible: Boolean get() = visibleViews > 0

    fun attachView() { visibleViews++ }
    fun detachView() { visibleViews = (visibleViews - 1).coerceAtLeast(0) }

    internal fun show(outgoing: Player?, incoming: Player? = null, progress: Float = 0f) {
        mutableFrame.value = CrossfadeVideoFrame(outgoing, incoming, progress.coerceIn(0f, 1f))
    }
}
