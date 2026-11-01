package com.maxrave.simpmusic.utils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegueVersionTest {
    @Test fun newerReleaseIsOffered() {
        assertTrue(isNewerRelease("2.4.5", "v2.5.0"))
        assertTrue(isNewerRelease("2.9.0", "v2.10.0"))
    }

    @Test fun sameOrOlderReleaseIsNotOffered() {
        assertFalse(isNewerRelease("2.4.5", "v2.4.5"))
        assertFalse(isNewerRelease("2.4.5", "v2.4.4"))
        assertFalse(isNewerRelease("2.4.5-dev", "v2.4.5"))
    }
}
