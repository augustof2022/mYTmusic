package com.maxrave.domain.mediaservice.player

/**
 * Decides what the player does after a track fails to load or dies midway.
 *
 * Before this, both handlers paused on every error, so one dead track stopped a whole playlist.
 * Now a failure skips to the next track, but only up to a limit of failures in a row: when the
 * network is down every track fails, and skipping forever would race through the queue.
 *
 * One failed track can be reported more than once: the crossfade adapter sends the error, then
 * sends a generic one again as it enters its ERROR state. Without the same-track check below,
 * one dead track would skip the good track after it as well.
 *
 * Shared by the Android and desktop handlers so both builds behave the same.
 */
class ErrorSkipPolicy(
    private val maxConsecutiveFailures: Int = 3,
) {
    enum class Decision { SKIP_TO_NEXT, STOP, ALREADY_HANDLED }

    var consecutiveFailures: Int = 0
        private set

    private var lastFailedMediaId: String? = null

    /**
     * Called from the playing progress tick. Only real audio ends a run of failures: the adapters
     * report "is playing" the moment a load starts, before a dead track has had a chance to fail,
     * so that signal would reset the count on every attempt and the guard would never trip.
     */
    fun onPlaybackProgress(positionMs: Long) {
        if (positionMs < PROOF_OF_PLAYBACK_MS) return
        consecutiveFailures = 0
        lastFailedMediaId = null
    }

    companion object {
        /** A position this far in means the track really produced sound. */
        const val PROOF_OF_PLAYBACK_MS = 3_000L

        /**
         * The crossfade adapter's "the track you picked could not load": it has already put the
         * healthy track back as current, so skipping would cut that track. Toast only.
         */
        const val TRANSITION_UNAVAILABLE = "TRANSITION_UNAVAILABLE"
    }

    /**
     * Called for every player error.
     * @param failedMediaId the track that was playing when the error came, if known.
     * @param hasNext whether the queue has a track after the failed one (repeat-all counts).
     */
    fun onError(
        failedMediaId: String?,
        hasNext: Boolean,
    ): Decision {
        if (failedMediaId != null && failedMediaId == lastFailedMediaId) return Decision.ALREADY_HANDLED
        lastFailedMediaId = failedMediaId
        consecutiveFailures++
        // No next track: the queue has ended. With repeat-all on, the player reports the first
        // track as "next", so repeat decides here without a check of its own.
        if (!hasNext) return Decision.STOP
        // Many failures in a row means the network, not the tracks: stop instead of racing the queue.
        if (consecutiveFailures >= maxConsecutiveFailures) return Decision.STOP
        return Decision.SKIP_TO_NEXT
    }
}
