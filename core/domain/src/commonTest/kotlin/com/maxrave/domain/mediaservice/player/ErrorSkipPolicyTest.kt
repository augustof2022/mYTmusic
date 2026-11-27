package com.maxrave.domain.mediaservice.player

import com.maxrave.domain.mediaservice.player.ErrorSkipPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorSkipPolicyTest {
    @Test
    fun oneDeadTrackSkipsToTheNext() {
        val policy = ErrorSkipPolicy()
        assertEquals(Decision.SKIP_TO_NEXT, policy.onError("a", hasNext = true))
    }

    @Test
    fun lastTrackFailingStops() {
        val policy = ErrorSkipPolicy()
        assertEquals(Decision.STOP, policy.onError("a", hasNext = false))
    }

    @Test
    fun stopsAfterTooManyFailuresInARow() {
        val policy = ErrorSkipPolicy(maxConsecutiveFailures = 3)
        assertEquals(Decision.SKIP_TO_NEXT, policy.onError("a", hasNext = true))
        assertEquals(Decision.SKIP_TO_NEXT, policy.onError("b", hasNext = true))
        assertEquals(Decision.STOP, policy.onError("c", hasNext = true))
    }

    @Test
    fun loadsThatNeverMakeSoundStillCountAsARun() {
        // Offline: each load reports a position of 0 before failing. That must not reset the count.
        val policy = ErrorSkipPolicy(maxConsecutiveFailures = 3)
        policy.onPlaybackProgress(0)
        assertEquals(Decision.SKIP_TO_NEXT, policy.onError("a", hasNext = true))
        policy.onPlaybackProgress(0)
        assertEquals(Decision.SKIP_TO_NEXT, policy.onError("b", hasNext = true))
        policy.onPlaybackProgress(0)
        assertEquals(Decision.STOP, policy.onError("c", hasNext = true))
    }

    @Test
    fun realPlaybackResetsTheCount() {
        val policy = ErrorSkipPolicy(maxConsecutiveFailures = 3)
        policy.onError("a", hasNext = true)
        policy.onError("b", hasNext = true)
        policy.onPlaybackProgress(ErrorSkipPolicy.PROOF_OF_PLAYBACK_MS)
        assertEquals(Decision.SKIP_TO_NEXT, policy.onError("c", hasNext = true))
    }

    @Test
    fun theSameTrackReportedTwiceSkipsOnlyOnce() {
        val policy = ErrorSkipPolicy()
        assertEquals(Decision.SKIP_TO_NEXT, policy.onError("a", hasNext = true))
        assertEquals(Decision.ALREADY_HANDLED, policy.onError("a", hasNext = true))
        assertEquals(1, policy.consecutiveFailures)
    }

    @Test
    fun theSameTrackFailingAgainAfterPlayingCountsAgain() {
        val policy = ErrorSkipPolicy()
        policy.onError("a", hasNext = true)
        policy.onPlaybackProgress(60_000)
        assertEquals(Decision.SKIP_TO_NEXT, policy.onError("a", hasNext = true))
    }
}
