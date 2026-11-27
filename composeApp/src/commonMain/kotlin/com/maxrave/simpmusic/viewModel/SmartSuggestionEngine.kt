package com.maxrave.simpmusic.viewModel

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.suggestion.FilterKind
import com.maxrave.domain.suggestion.SmartSuggestion
import com.maxrave.domain.suggestion.SuggestionFilter
import com.maxrave.domain.suggestion.SuggestionTagRepository
import com.maxrave.domain.suggestion.TaggedSong
import com.maxrave.domain.suggestion.TrackTags
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A song already in the playlist: a seed for "related" radios and a source of filter chips. */
data class SuggestionSeed(
    val videoId: String,
    val title: String,
    val artists: List<String>,
)

/**
 * Extra candidates the playlist itself offers, such as YouTube's own suggestions for a synced
 * playlist. [first] is shown before the radios arrive; [next] pages on, null when there is no more.
 */
interface SuggestionExtraSource {
    suspend fun first(): List<Track>

    suspend fun next(): List<Track>?
}

/**
 * Smart playlist suggestions, shared by the local and the YouTube playlist screens.
 *
 * Five songs at a time from YouTube "related" radios of songs already in the playlist (plus any
 * [SuggestionExtraSource]). Chips are read from the playlist's Deezer tags; no chip picked = Auto,
 * ranked by closeness to the playlist (see [SmartSuggestion]). All state lives on [scope]'s
 * dispatcher (viewModelScope = main), so the mutable collections are touched by one thread only.
 */
class SmartSuggestionEngine(
    private val scope: CoroutineScope,
    private val songRepository: SongRepository,
    private val tagRepository: SuggestionTagRepository,
) {
    data class State(
        val songs: List<Track> = emptyList(),
        val filters: List<SuggestionFilter> = emptyList(),
        val selected: Set<SuggestionFilter> = emptySet(),
        val filtersLoading: Boolean = false,
        val loading: Boolean = false,
        val active: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The first load. Later work waits for it, never cancels it. */
    private var setupJob: Job? = null
    private var refillJob: Job? = null
    private var tagJob: Job? = null
    private val pool = mutableListOf<Track>()
    private val poolTags = mutableMapOf<String, TrackTags?>()
    private val excludedIds = mutableSetOf<String>()
    private val usedSeeds = mutableSetOf<String>()
    private var seeds: List<SuggestionSeed> = emptyList()
    private var playlistTagged: List<TaggedSong> = emptyList()
    private var extra: SuggestionExtraSource? = null
    private var useBpm = false

    /** True once the listener changed the list (add, refresh, chip): Auto then stops re-ranking it. */
    private var touched = false

    /**
     * Starts over for a playlist. [loadSeeds] reads its songs (all of them: they are also excluded
     * from the suggestions); it runs inside the engine so a Reload tapped meanwhile waits for it.
     */
    fun start(
        loadSeeds: suspend () -> List<SuggestionSeed>,
        extraSource: SuggestionExtraSource? = null,
        /** Songs in the playlist beyond the seeds (e.g. pages not loaded yet): never suggested. */
        excludeIds: Collection<String> = emptyList(),
    ) {
        stop()
        extra = extraSource
        _state.value = State(filtersLoading = true, loading = true, active = true)
        setupJob =
            scope.launch {
                seeds = loadSeeds()
                excludedIds += seeds.map { it.videoId }
                excludedIds += excludeIds
                if (seeds.isEmpty()) {
                    // Nothing to work from yet (songs still loading): go back to idle so a later
                    // start() can run, instead of an open panel that stays empty.
                    _state.value = State()
                    return@launch
                }
                // Chips are read in parallel with finding the first songs.
                readTags()
                extra?.let { source ->
                    addToPool(runCatching { source.first() }.getOrDefault(emptyList()))
                    if (pool.isNotEmpty()) {
                        show()
                        _state.update { it.copy(loading = false) }
                    }
                }
                addRelatedFromSeeds(seedsMatching(emptySet()), count = 2)
                show()
                _state.update { it.copy(loading = false) }
            }
    }

    fun stop() {
        setupJob?.cancel()
        refillJob?.cancel()
        tagJob?.cancel()
        pool.clear()
        poolTags.clear()
        excludedIds.clear()
        usedSeeds.clear()
        seeds = emptyList()
        playlistTagged = emptyList()
        extra = null
        useBpm = false
        touched = false
        _state.value = State()
    }

    /** Refresh: drop the five on screen and show the next five, finding more songs if needed. */
    fun reload() {
        touched = true
        excludedIds += _state.value.songs.map { it.videoId }
        refill()
    }

    /** null = Auto (clears every chip). Otherwise the chip is switched on or off. */
    fun toggle(filter: SuggestionFilter?) {
        touched = true
        _state.update { s ->
            val next =
                when {
                    filter == null -> emptySet()
                    filter in s.selected -> s.selected - filter
                    else -> s.selected + filter
                }
            s.copy(selected = next)
        }
        refill()
    }

    /** The song joined the playlist: it leaves the list for good and a new one takes its place. */
    fun onAdded(videoId: String) {
        touched = true
        excludedIds += videoId
        _state.update { s -> s.copy(songs = s.songs.filter { it.videoId != videoId }) }
        refill()
    }

    /** The add did not go through: the song may be suggested again. */
    fun onAddFailed(videoId: String) {
        excludedIds -= videoId
    }

    /** Shows what the pool holds now, then grows the pool while the picked chips leave gaps. */
    private fun refill() {
        refillJob?.cancel()
        refillJob =
            scope.launch {
                setupJob?.join()
                show()
                var rounds = 0
                while (_state.value.songs.size < SmartSuggestion.SHOW_COUNT && rounds < MAX_REFILL_ROUNDS) {
                    _state.update { it.copy(loading = it.songs.isEmpty()) }
                    val needsTags = _state.value.selected.any { it.kind != FilterKind.ARTIST }
                    // Songs already found but not yet looked up may match: check them before fetching more.
                    if (needsTags && pool.any { it.videoId !in poolTags }) {
                        tagPool()
                    } else {
                        rounds++
                        if (!growPool()) break
                    }
                    show()
                }
                _state.update { it.copy(loading = false) }
            }
    }

    /** One more batch: the extra source's next page, else radios of fitting playlist songs. */
    private suspend fun growPool(): Boolean {
        val before = pool.size
        extra?.let { source ->
            val more = runCatching { source.next() }.getOrNull()
            if (more == null) extra = null else addToPool(more)
        }
        if (pool.size == before) addRelatedFromSeeds(seedsMatching(_state.value.selected), count = 2)
        return pool.size > before
    }

    /** Playlist songs that fit the picked chips (any song for Auto), not used as a seed yet. */
    private fun seedsMatching(selected: Set<SuggestionFilter>): List<String> {
        val all = seeds.map { it.videoId }
        val fitting =
            if (selected.isEmpty() || playlistTagged.isEmpty()) {
                all
            } else {
                playlistTagged.filter { SmartSuggestion.matches(it, selected) }.map { it.id }.ifEmpty { all }
            }
        return fitting.filter { it !in usedSeeds }.shuffled()
    }

    private suspend fun addRelatedFromSeeds(
        seedIds: List<String>,
        count: Int,
    ) {
        seedIds.take(count).forEach { seed ->
            usedSeeds += seed
            val related = songRepository.getRelatedData(seed).firstOrNull()
            (related as? Resource.Success)?.data?.first?.let { addToPool(it) }
        }
    }

    private fun addToPool(tracks: List<Track>) {
        val known = pool.map { it.videoId }.toSet()
        pool += tracks.filter { it.videoId !in known && it.videoId !in excludedIds }
    }

    /** Chips first (from the playlist), then the candidates, so filters can match them. */
    private fun readTags() {
        tagJob?.cancel()
        tagJob =
            scope.launch {
                val sample = seeds.shuffled().take(PLAYLIST_TAG_SAMPLE)
                playlistTagged = tagAll(sample.map { Triple(it.videoId, it.title, it.artists) }, withBpm = true)
                val filters = SmartSuggestion.buildFilters(playlistTagged)
                useBpm = filters.any { it.kind == FilterKind.SPEED }
                _state.update { it.copy(filters = filters) }
                tagPool()
                _state.update { it.copy(filtersLoading = false) }
                // Auto, untouched: re-rank the first five now that the songs can be compared.
                if (!touched || _state.value.selected.isNotEmpty()) show()
            }
    }

    private suspend fun tagPool() {
        val untagged = pool.filter { it.videoId !in poolTags }.take(POOL_TAG_BATCH)
        tagAll(untagged.map { t -> Triple(t.videoId, t.title, t.artistNames()) }, withBpm = useBpm)
            .forEach { poolTags[it.id] = it.tags }
    }

    private suspend fun tagAll(
        songs: List<Triple<String, String, List<String>>>,
        withBpm: Boolean,
    ): List<TaggedSong> =
        coroutineScope {
            songs.chunked(TAG_PARALLELISM).flatMap { chunk ->
                chunk.map { (id, title, artists) ->
                    async {
                        val tags = artists.firstOrNull()?.let { tagRepository.tagsFor(title, it, withBpm) }
                        TaggedSong(id, artists, tags)
                    }
                }.awaitAll()
            }
        }

    /** Fills the list up to five from the pool, keeping songs already on screen in place. */
    private fun show() {
        val s = _state.value
        val onScreen =
            if (touched) {
                s.songs.filter { t ->
                    t.videoId !in excludedIds && (s.selected.isEmpty() || SmartSuggestion.matches(tagged(t), s.selected))
                }
            } else {
                emptyList()
            }
        val more =
            SmartSuggestion.pick(
                pool = pool.map(::tagged),
                selected = s.selected,
                profile = s.filters,
                exclude = excludedIds + onScreen.map { it.videoId },
                count = SmartSuggestion.SHOW_COUNT - onScreen.size,
            )
        val byId = pool.associateBy { it.videoId }
        _state.update { it.copy(songs = onScreen + more.mapNotNull { m -> byId[m.id] }) }
    }

    private fun tagged(t: Track) = TaggedSong(t.videoId, t.artistNames(), poolTags[t.videoId])

    private fun Track.artistNames(): List<String> = artists?.map { it.name }.orEmpty()

    private companion object {
        const val MAX_REFILL_ROUNDS = 3
        const val PLAYLIST_TAG_SAMPLE = 20
        const val POOL_TAG_BATCH = 30
        const val TAG_PARALLELISM = 4
    }
}
