package com.maxrave.media3.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class SeamlessTimingTest {
    @Test fun fadeNeverRunsPastOutgoingTrack() {
        assertEquals(250, SeamlessTiming.duration(20_000, 250))
        assertEquals(0, SeamlessTiming.duration(5_000, -1))
    }

    @Test fun importedPreferencesCannotExceedTwentySeconds() {
        assertEquals(20_000, SeamlessTiming.duration(45_000, 90_000))
        assertEquals(1_000, SeamlessTiming.duration(0, 90_000))
    }

    @Test fun delayedFramesUseElapsedTimeRatherThanExtendingTheFade() {
        assertEquals(0f, SeamlessTiming.progress(1_000, 1_000, 20_000))
        assertEquals(0.5f, SeamlessTiming.progress(1_000, 11_000, 20_000))
        assertEquals(1f, SeamlessTiming.progress(1_000, 30_000, 20_000))
        assertEquals(1f, SeamlessTiming.progress(1_000, 1_000, 0))
    }
    @Test fun shortSongsKeepTimeOutsideTheOverlap() {
        assertEquals(1500, SeamlessTiming.duration(20_000, 90_000, incomingDurationMs = 3000))
        assertEquals(15_000, SeamlessTiming.duration(20_000, 90_000, outgoingDurationMs = 30_000))
        assertEquals(20_000, SeamlessTiming.duration(20_000, 90_000, 240_000, 240_000))
    }

    @Test fun unknownStreamLengthsDoNotSuppressTheTransition() {
        assertEquals(5000, SeamlessTiming.duration(5000, 90_000, incomingDurationMs = -1))
    }

    @Test fun videoPreparationLeavesAnAudibleOverlapAtTheEnd() {
        assertEquals(600L, SeamlessTiming.firstFrameWaitBudget(850, true))
        assertEquals(0L, SeamlessTiming.firstFrameWaitBudget(250, true))
        assertEquals(0L, SeamlessTiming.firstFrameWaitBudget(0, true))
    }

    @Test fun videoFramesCannotDelayAStreamThatIsAlreadyReadyWhenTheOldSongStops() {
        assertEquals(0L, SeamlessTiming.firstFrameWaitBudget(90_000, false))
        assertEquals(1_500L, SeamlessTiming.firstFrameWaitBudget(Long.MAX_VALUE, true))
    }

}
