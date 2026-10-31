package com.maxrave.media3.exoplayer

/** Natural endings and precaching share a target; transport Next/Previous keep their own order. */
internal object AutomaticTransitionPolicy {
    fun target(currentIndex: Int, itemCount: Int, repeatOne: Boolean, nextIndex: Int?): Int? {
        if (currentIndex !in 0 until itemCount) return null
        return if (repeatOne) currentIndex else nextIndex?.takeIf { it in 0 until itemCount }
    }

    fun canCrossfade(
        enabled: Boolean,
        suppressed: Boolean,
        currentIndex: Int,
        targetIndex: Int?,
        currentLive: Boolean,
        targetLive: Boolean,
        withinAlbum: Boolean,
    ): Boolean = enabled && !suppressed && targetIndex != null && !currentLive && !targetLive &&
        // Skipping transitions between album tracks must not suppress a loop of the same track.
        (!withinAlbum || targetIndex == currentIndex)
}
