package com.maxrave.simpmusic.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackErrorMessageTest {
    @Test fun extractionFailuresDoNotBlameInternetConnectivity() {
        val message = playbackErrorMessage("ERROR_UNKNOWN")
        assertNotNull(message)
        assertFalse(message!!.contains("internet", ignoreCase = true))
        assertFalse(message.contains("timeout", ignoreCase = true))
        assertNotNull(playbackErrorMessage("ERROR_CODE_IO_BAD_HTTP_STATUS"))
    }

    @Test fun networkTimeoutsKeepTheLocalizedConnectionMessage() {
        assertNull(playbackErrorMessage("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertNull(playbackErrorMessage("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"))
        assertNull(playbackErrorMessage("ERROR_CODE_TIMEOUT"))
    }
}
