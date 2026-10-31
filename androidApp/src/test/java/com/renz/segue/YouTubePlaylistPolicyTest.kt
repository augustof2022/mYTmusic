package com.renz.segue

import com.maxrave.domain.utils.YouTubePlaylistPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaylistPolicyTest {
    @Test fun browseAndWatchIdsAddressTheSamePlaylist() {
        assertEquals("PLabc_123-x", YouTubePlaylistPolicy.id("VLPLabc_123-x"))
        assertEquals("PLabc_123-x", YouTubePlaylistPolicy.id("PLabc_123-x"))
    }
    @Test fun mixesAndMissingIdsCannotBeMutationTargets() {
        listOf("", "PL", "VLLM", "RDAMVMsong", "PLabc/other", "PLabc\n").forEach { value ->
            assertTrue(runCatching { YouTubePlaylistPolicy.id(value) }.isFailure)
        }
    }
    @Test fun anHttp200ErrorBodyIsNotSuccess() {
        assertFalse(YouTubePlaylistPolicy.acknowledged(200, null, true, deleting = true))
        assertFalse(YouTubePlaylistPolicy.acknowledged(200, "STATUS_SUCCEEDED", true))
    }
    @Test fun removalRequiresConfirmation() {
        assertFalse(YouTubePlaylistPolicy.acknowledged(200, null, false))
        assertFalse(YouTubePlaylistPolicy.acknowledged(200, "STATUS_FAILED", false))
        assertTrue(YouTubePlaylistPolicy.acknowledged(200, "STATUS_SUCCEEDED", false))
    }
    @Test fun deniedAndFailedMutationsAreNotAcknowledged() {
        assertFalse(YouTubePlaylistPolicy.acknowledged(403, "STATUS_SUCCEEDED", false))
        assertFalse(YouTubePlaylistPolicy.acknowledged(500, null, false, deleting = true))
    }
    @Test fun deletionAcceptsAnEmptySuccessEnvelope() {
        assertTrue(YouTubePlaylistPolicy.acknowledged(200, null, false, deleting = true))
        assertTrue(YouTubePlaylistPolicy.acknowledged(204, null, false, deleting = true))
    }
}
