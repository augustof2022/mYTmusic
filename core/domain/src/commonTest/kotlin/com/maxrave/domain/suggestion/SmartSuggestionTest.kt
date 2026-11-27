package com.maxrave.domain.suggestion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartSuggestionTest {
    private fun song(
        id: String,
        artist: String,
        genres: Set<String> = emptySet(),
        year: Int? = null,
        bpm: Int? = null,
        known: Boolean = true,
    ) = TaggedSong(id, listOf(artist), if (known) TrackTags(genres, year, bpm) else null)

    private val rnb = SuggestionFilter(FilterKind.GENRE, "R&B")
    private val reggae = SuggestionFilter(FilterKind.GENRE, "Reggae")
    private val twenties = SuggestionFilter(FilterKind.DECADE, "2020")

    @Test
    fun chipsComeFromThePlaylist() {
        val playlist =
            listOf(
                song("1", "SZA", setOf("R&B"), 2022),
                song("2", "SZA", setOf("R&B"), 2023),
                song("3", "Chronixx", setOf("Reggae"), 2017),
            )
        val filters = SmartSuggestion.buildFilters(playlist)
        assertEquals(listOf("R&B", "Reggae"), filters.filter { it.kind == FilterKind.GENRE }.map { it.value })
        assertEquals(listOf("2020", "2010"), filters.filter { it.kind == FilterKind.DECADE }.map { it.value })
        assertEquals(listOf("SZA", "Chronixx"), filters.filter { it.kind == FilterKind.ARTIST }.map { it.value })
    }

    @Test
    fun noSpeedChipWhenMostSongsLackABpm() {
        val playlist = listOf(song("1", "A", bpm = 0), song("2", "B", bpm = 0), song("3", "C", bpm = 130))
        assertTrue(SmartSuggestion.buildFilters(playlist).none { it.kind == FilterKind.SPEED })
    }

    @Test
    fun speedChipWhenMostSongsHaveABpm() {
        val playlist = listOf(song("1", "A", bpm = 80), song("2", "B", bpm = 130), song("3", "C", bpm = 0))
        val speeds = SmartSuggestion.buildFilters(playlist).filter { it.kind == FilterKind.SPEED }.map { it.value }
        assertEquals(listOf("SLOW", "FAST"), speeds)
    }

    @Test
    fun sameKindWidensOtherKindsNarrow() {
        val rnb2022 = song("1", "SZA", setOf("R&B"), 2022)
        val reggae2017 = song("2", "Chronixx", setOf("Reggae"), 2017)
        assertTrue(SmartSuggestion.matches(reggae2017, setOf(rnb, reggae)))
        assertTrue(SmartSuggestion.matches(rnb2022, setOf(rnb, twenties)))
        assertFalse(SmartSuggestion.matches(reggae2017, setOf(reggae, twenties)))
    }

    @Test
    fun unknownDataDoesNotMatchAPickedFilter() {
        assertFalse(SmartSuggestion.matches(song("1", "X", known = false), setOf(rnb)))
        assertTrue(SmartSuggestion.matches(song("1", "X", known = false), setOf(SuggestionFilter(FilterKind.ARTIST, "x"))))
    }

    @Test
    fun autoRanksCloserSongsFirstAndKeepsOrderOnTies() {
        val profile = listOf(rnb, twenties)
        val pool = listOf(song("a", "X"), song("b", "Y", setOf("R&B"), 2021), song("c", "Z"))
        val picked = SmartSuggestion.pick(pool, emptySet(), profile, exclude = emptySet(), count = 3).map { it.id }
        assertEquals(listOf("b", "a", "c"), picked)
    }

    @Test
    fun pickSkipsExcludedAndShowsAtMostFive() {
        val pool = (1..9).map { song("$it", "A", setOf("R&B")) }
        val picked = SmartSuggestion.pick(pool, setOf(rnb), emptyList(), exclude = setOf("1", "2")).map { it.id }
        assertEquals(listOf("3", "4", "5", "6", "7"), picked)
    }
}
