package com.maxrave.media3.exoplayer

internal object SeamlessTiming {
    const val MAX_DURATION_MS = 20_000

    fun duration(
        configuredMs: Int,
        remainingMs: Long,
        incomingDurationMs: Long = 0,
        outgoingDurationMs: Long = 0,
    ): Int = minOf(
        configuredMs.coerceIn(1_000, MAX_DURATION_MS).toLong(),
        remainingMs.coerceAtLeast(0L),
        incomingDurationMs.takeIf { it > 0 }?.div(2) ?: Long.MAX_VALUE,
        outgoingDurationMs.takeIf { it > 0 }?.div(2) ?: Long.MAX_VALUE,
    ).toInt()

    fun progress(startedMs: Long, nowMs: Long, durationMs: Int): Float =
        if (durationMs <= 0) 1f else ((nowMs - startedMs).toFloat() / durationMs).coerceIn(0f, 1f)

    // Leave an audible tail for the overlap; a video frame must never delay a silent handoff.
    fun firstFrameWaitBudget(remainingMs: Long, outgoingPlaying: Boolean): Long =
        if (!outgoingPlaying) 0L else minOf(1_500L, (remainingMs - 250L).coerceAtLeast(0L))
}
