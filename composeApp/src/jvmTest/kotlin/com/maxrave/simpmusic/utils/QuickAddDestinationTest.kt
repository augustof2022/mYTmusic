package com.maxrave.simpmusic.utils

import com.maxrave.simpmusic.utils.QuickAddDestination.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickAddDestinationTest {
    @Test
    fun rememberedPlaylistSurvivesUnicodeAndMultilineTitles() {
        val local = QuickAddDestination(Source.LOCAL, "42", "Night mix 🎵\nPart 2 · favorites")
        assertEquals(local, QuickAddDestination.decode(local.encode()))
        val youtube = QuickAddDestination(Source.YOUTUBE, "VLPL123", "A / B: Chill", "0:channel:12345")
        assertEquals(youtube, QuickAddDestination.decode(youtube.encode()))
    }

    @Test
    fun corruptPreferencesFallBackToChoosingAPlaylist() {
        listOf(null, "", "LOCAL", "2\nLOCAL\n42\n\nMix", "1\nUNKNOWN\n42\n\nMix", "1\nLOCAL\n0\n\nMix",
            "1\nLOCAL\n-1\n\nMix", "1\nLOCAL\nno-id\n\nMix", "1\nYOUTUBE\nVLPL123\n\nMix", "1\nLOCAL\n42\n\n").forEach {
            assertNull(QuickAddDestination.decode(it), "Invalid preference: $it")
        }
    }

    @Test
    fun aRenamedPlaylistKeepsItsIdentity() {
        val target = QuickAddDestination(Source.LOCAL, "7", "Original")
        assertEquals(target.key, target.copy(title = "Renamed").key)
    }

    @Test
    fun changingDestinationMakesThePreviousReceiptDistinct() {
        val first = QuickAddDestination(Source.LOCAL, "7", "Mix")
        assertNotEquals(first.key, first.copy(id = "8").key)
        assertNotEquals(first.key, QuickAddDestination(Source.YOUTUBE, "7", "Mix", "account").key)
    }

    @Test
    fun youtubeDestinationCannotCarryAcrossSignOutOrAccountChanges() {
        val target = QuickAddDestination(Source.YOUTUBE, "VLPL123", "Mix", "account-a")
        assertTrue(target.belongsTo("account-a"))
        assertFalse(target.belongsTo("account-b"))
        assertFalse(target.belongsTo(""))
        assertNotEquals(target.key, target.copy(account = "account-b").key)
    }

    @Test
    fun localDestinationWorksWithOrWithoutGoogleSignIn() {
        val target = QuickAddDestination(Source.LOCAL, "7", "Mix")
        assertTrue(target.belongsTo(""))
        assertTrue(target.belongsTo("account-a"))
        assertTrue(target.belongsTo("account-b"))
    }
}
