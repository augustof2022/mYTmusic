package com.maxrave.media3.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticTransitionPolicyTest {
    @Test fun repeatOneLoopsEvenWhenTheQueueContainsOnlyOneSong() {
        assertEquals(0, AutomaticTransitionPolicy.target(0, 1, true, null))
        assertTrue(fade(0, AutomaticTransitionPolicy.target(0, 1, true, null)))
    }

    @Test fun repeatOnePreparesTheCurrentSongInsteadOfTheNextSong() {
        assertEquals(1, AutomaticTransitionPolicy.target(1, 5, true, 2))
        assertEquals(4, AutomaticTransitionPolicy.target(4, 5, true, null))
    }

    @Test fun regularAndShuffledPlaybackUseTheTransportNextIndex() {
        assertEquals(2, AutomaticTransitionPolicy.target(1, 5, false, 2))
        assertEquals(4, AutomaticTransitionPolicy.target(1, 5, false, 4))
        assertEquals(0, AutomaticTransitionPolicy.target(4, 5, false, 0))
    }

    @Test fun repeatAllWithOneSongAlsoOverlapsTheSameSong() {
        assertEquals(0, AutomaticTransitionPolicy.target(0, 1, false, 0))
        assertTrue(fade(0, AutomaticTransitionPolicy.target(0, 1, false, 0)))
    }

    @Test fun repeatOffDoesNotInventANextSongAtTheEndOfTheQueue() {
        assertNull(AutomaticTransitionPolicy.target(0, 1, false, null))
        assertFalse(fade(0, null))
        assertNull(AutomaticTransitionPolicy.target(-1, 1, true, null))
        assertNull(AutomaticTransitionPolicy.target(0, 0, true, null))
    }

    @Test fun disabledCrossfadeAndLiveStreamsNeverEnterTheMixer() {
        assertFalse(AutomaticTransitionPolicy.canCrossfade(false, false, 0, 0, false, false, false))
        assertFalse(AutomaticTransitionPolicy.canCrossfade(true, true, 0, 0, false, false, false))
        assertFalse(AutomaticTransitionPolicy.canCrossfade(true, false, 0, 0, true, false, false))
        assertFalse(AutomaticTransitionPolicy.canCrossfade(true, false, 0, 1, false, true, false))
    }

    @Test fun albumSkipStillAllowsCrossfadingBackIntoTheSameSong() {
        assertTrue(AutomaticTransitionPolicy.canCrossfade(true, false, 2, 2, false, false, true))
        assertFalse(AutomaticTransitionPolicy.canCrossfade(true, false, 2, 3, false, false, true))
    }

    private fun fade(current: Int, target: Int?) =
        AutomaticTransitionPolicy.canCrossfade(true, false, current, target, false, false, false)
}
