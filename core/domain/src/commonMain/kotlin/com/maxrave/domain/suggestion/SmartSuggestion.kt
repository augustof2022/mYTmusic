package com.maxrave.domain.suggestion

/**
 * Smart playlist suggestions: the filter chips are read from the playlist on screen, and the
 * suggested songs are matched against the chips the listener picks (none picked = Auto).
 *
 * YouTube Music carries no genre, mood or tempo per song, so [TrackTags] come from Deezer's public
 * catalogue (album genre, release date, BPM). Any of them can be missing: Deezer reports BPM as 0
 * for many songs, which is why the speed chip only appears when most of the playlist has one.
 */
data class TrackTags(
    val genres: Set<String> = emptySet(),
    val year: Int? = null,
    val bpm: Int? = null,
)

enum class FilterKind { GENRE, DECADE, ARTIST, SPEED }

data class SuggestionFilter(
    val kind: FilterKind,
    /** GENRE: "R&B". DECADE: "2020". ARTIST: "SZA". SPEED: one of [Speed] names. */
    val value: String,
)

enum class Speed { SLOW, MEDIUM, FAST }

/** One song, as the rules need it: who sings it and what Deezer knows about it (null = not known). */
data class TaggedSong(
    val id: String,
    val artists: List<String>,
    val tags: TrackTags?,
)

object SmartSuggestion {
    const val MAX_CHIPS_PER_KIND = 6

    /** Share of the playlist that must carry a BPM before a speed chip is offered. */
    const val SPEED_COVERAGE = 0.5

    fun decadeOf(year: Int): Int = year / 10 * 10

    fun speedOf(bpm: Int?): Speed? =
        when {
            bpm == null || bpm <= 0 -> null
            bpm < 95 -> Speed.SLOW
            bpm <= 125 -> Speed.MEDIUM
            else -> Speed.FAST
        }

    /** The chips for this playlist, most common first within each kind. */
    fun buildFilters(playlist: List<TaggedSong>): List<SuggestionFilter> {
        val tagged = playlist.mapNotNull { it.tags }
        val genres = topValues(tagged.flatMap { it.genres })
        val decades = topValues(tagged.mapNotNull { it.year?.let(::decadeOf)?.toString() }).sortedDescending()
        val artists = topValues(playlist.flatMap { it.artists.take(1) })
        val speeds =
            if (tagged.isNotEmpty() && tagged.count { speedOf(it.bpm) != null } >= tagged.size * SPEED_COVERAGE) {
                tagged.mapNotNull { speedOf(it.bpm) }.distinct().sortedBy { it.ordinal }.map { it.name }
            } else {
                emptyList()
            }
        return genres.map { SuggestionFilter(FilterKind.GENRE, it) } +
            decades.map { SuggestionFilter(FilterKind.DECADE, it) } +
            artists.map { SuggestionFilter(FilterKind.ARTIST, it) } +
            speeds.map { SuggestionFilter(FilterKind.SPEED, it) }
    }

    /**
     * Picked chips of one kind widen (R&B OR Reggae); different kinds narrow (R&B AND 2020s).
     * A song missing the data a picked kind needs does not match: a filter must mean what it says.
     */
    fun matches(
        song: TaggedSong,
        selected: Set<SuggestionFilter>,
    ): Boolean =
        selected.groupBy { it.kind }.all { (kind, filters) ->
            val values = filters.map { it.value }.toSet()
            when (kind) {
                FilterKind.ARTIST -> song.artists.any { artist -> values.any { it.equals(artist, ignoreCase = true) } }
                FilterKind.GENRE -> song.tags?.genres?.any { it in values } == true
                FilterKind.DECADE -> song.tags?.year?.let { decadeOf(it).toString() in values } == true
                FilterKind.SPEED -> speedOf(song.tags?.bpm)?.name in values
            }
        }

    /** Auto: how close a song sits to the playlist's own chips. Unknown data scores 0, never negative. */
    fun score(
        song: TaggedSong,
        profile: List<SuggestionFilter>,
    ): Int =
        profile.sumOf { filter ->
            if (!matches(song, setOf(filter))) {
                0
            } else {
                when (filter.kind) {
                    FilterKind.GENRE -> 3
                    FilterKind.ARTIST -> 2
                    FilterKind.DECADE -> 2
                    FilterKind.SPEED -> 1
                }
            }
        }

    /**
     * The next songs to show: not excluded (already in the playlist, already shown, added), matching
     * the picked chips, or with none picked ranked by [score]. Ties keep the pool's order, which is
     * YouTube's own "related" order.
     */
    fun pick(
        pool: List<TaggedSong>,
        selected: Set<SuggestionFilter>,
        profile: List<SuggestionFilter>,
        exclude: Set<String>,
        count: Int = 5,
    ): List<TaggedSong> {
        val open = pool.filter { it.id !in exclude }.distinctBy { it.id }
        return if (selected.isEmpty()) {
            open.withIndex().sortedWith(compareByDescending<IndexedValue<TaggedSong>> { score(it.value, profile) }.thenBy { it.index })
                .map { it.value }
                .take(count)
        } else {
            open.filter { matches(it, selected) }.take(count)
        }
    }

    private fun topValues(values: List<String>): List<String> =
        values
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(MAX_CHIPS_PER_KIND)
            .map { it.key }
}

/** Reads a song's genre, year and tempo from an outside catalogue. Null when it cannot be found. */
interface SuggestionTagRepository {
    suspend fun tagsFor(
        title: String,
        artist: String,
        withBpm: Boolean,
    ): TrackTags?
}
