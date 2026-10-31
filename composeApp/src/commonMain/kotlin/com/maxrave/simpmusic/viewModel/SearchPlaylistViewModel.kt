package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.LocalResource
import com.maxrave.simpmusic.utils.QuickAddDestination
import com.maxrave.simpmusic.utils.QuickAddDestination.Source
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.added_to_playlist
import simpmusic.composeapp.generated.resources.added_to_youtube_playlist
import simpmusic.composeapp.generated.resources.error
import simpmusic.composeapp.generated.resources.quick_add_choose_again
import simpmusic.composeapp.generated.resources.quick_add_sign_in
import simpmusic.composeapp.generated.resources.quick_add_youtube_add_failed
import simpmusic.composeapp.generated.resources.quick_add_sync_failed
import simpmusic.composeapp.generated.resources.quick_add_youtube_failed

data class SearchPlaylistState(
    val destination: QuickAddDestination? = null,
    val localPlaylists: List<LocalPlaylistEntity> = emptyList(),
    val youTubePlaylists: List<PlaylistsResult> = emptyList(),
    val showPicker: Boolean = false,
    val createOnly: Boolean = false,
    val signedIn: Boolean = false,
    val creating: Boolean = false,
    val creationError: String? = null,
    val pendingSong: SongEntity? = null,
    val loadingYouTube: Boolean = false,
    val youTubeStatus: String? = null,
    val busy: Set<String> = emptySet(),
    val favoriteBusy: Set<String> = emptySet(),
    val added: Map<String, QuickAddDestination> = emptyMap(),
)

/** One remembered destination for search, independent of the multi-select playlist action. */
class SearchPlaylistViewModel(
    private val dataStoreManager: DataStoreManager,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val songRepository: SongRepository,
) : BaseViewModel() {
    private val _state = MutableStateFlow(SearchPlaylistState())
    val state = _state.asStateFlow()
    private val addMutex = Mutex()
    private val preferenceMutex = Mutex()
    private val addedYouTubeItems = mutableSetOf<Pair<String, String>>()
    private var accountKey = ""
    private var accountReady = false
    private var destinationChosen = false
    private var youTubeJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = QuickAddDestination.decode(dataStoreManager.getString(DESTINATION_KEY).first())
            combine(dataStoreManager.loggedIn, dataStoreManager.cookie, dataStoreManager.pageId, dataStoreManager.authUser) {
                loggedIn, cookie, pageId, authUser ->
                // A non-secret session fingerprint prevents sending a remembered target to another account.
                if (loggedIn == DataStoreManager.TRUE && cookie.isNotEmpty()) "$authUser:$pageId:${cookie.hashCode()}" else ""
            }.collect { currentAccount ->
                val previous = if (accountReady || destinationChosen) _state.value.destination else saved
                val changedAccount = accountReady && accountKey != currentAccount
                accountKey = currentAccount
                accountReady = true
                val valid = previous?.takeIf { it.belongsTo(currentAccount) }
                _state.update { it.copy(destination = valid, signedIn = currentAccount.isNotEmpty(), youTubePlaylists = emptyList(),
                    added = it.added.filterValues { target -> target.belongsTo(currentAccount) }) }
                if (changedAccount) addedYouTubeItems.clear()
                if (valid != previous) persistDestination(null)
                if (_state.value.showPicker) refreshYouTubePlaylists()
            }
        }
        refreshLocalPlaylists()
        viewModelScope.launch {
            var first = true
            dataStoreManager.getString(QuickAddDestination.PREFERENCE_KEY).collect { value ->
                if (first) { first = false; return@collect }
                val restored = QuickAddDestination.decode(value)?.takeIf { it.belongsTo(accountKey) }
                _state.update { it.copy(destination = restored) }
            }
        }
    }

    private fun refreshLocalPlaylists() {
        viewModelScope.launch {
            try {
                val playlists = localPlaylistRepository.getAllLocalPlaylists().first()
                val previous = _state.value.destination
                val renamed = if (previous?.source == Source.LOCAL) {
                    playlists.firstOrNull { it.id.toString() == previous.id }?.let { previous.copy(title = it.title) }
                } else previous
                _state.update { it.copy(localPlaylists = playlists, destination = renamed) }
                if (renamed != previous) persistDestination(renamed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                makeToast(e.message ?: getString(Res.string.error))
            }
        }
    }

    fun favoriteStatus(videoId: String): Flow<Boolean> =
        songRepository.getSongAsFlow(videoId).map { it?.liked == true }.distinctUntilChanged()

    fun toggleFavorite(song: SongEntity) {
        if (song.videoId in _state.value.favoriteBusy) return
        _state.update { it.copy(favoriteBusy = it.favoriteBusy + song.videoId) }
        viewModelScope.launch {
            try {
                val stored = songRepository.getSongById(song.videoId).first()
                if (stored == null) songRepository.insertSong(song).first()
                val liked = songRepository.getSongById(song.videoId).first()?.liked == true
                songRepository.updateLikeStatus(song.videoId, if (liked) 0 else 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                makeToast(e.message ?: getString(Res.string.error))
            } finally {
                _state.update { it.copy(favoriteBusy = it.favoriteBusy - song.videoId) }
            }
        }
    }

    fun quickAdd(song: SongEntity) {
        val target = _state.value.destination
        if (target == null || !accountReady || !target.belongsTo(accountKey)) openPicker(song) else add(song, target)
    }

    fun openPickerForVideo(videoId: String) {
        viewModelScope.launch {
            try {
                songRepository.getSongById(videoId).first()?.let { openPicker(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { makeToast(error.message ?: getString(Res.string.error)) }
        }
    }

    fun openPicker(song: SongEntity? = null, createOnly: Boolean = false) {
        _state.update { it.copy(showPicker = true, createOnly = createOnly, pendingSong = song, creationError = null) }
        refreshLocalPlaylists()
        refreshYouTubePlaylists()
    }

    fun dismissPicker() {
        if (_state.value.creating) return
        _state.update { it.copy(showPicker = false, pendingSong = null) }
    }

    fun chooseLocal(playlist: LocalPlaylistEntity) = choose(QuickAddDestination(Source.LOCAL, playlist.id.toString(), playlist.title))

    fun chooseYouTube(playlist: PlaylistsResult) {
        if (accountKey.isEmpty()) {
            makeToast(getString(Res.string.quick_add_sign_in))
            return
        }
        choose(QuickAddDestination(Source.YOUTUBE, playlist.browseId, playlist.title, accountKey))
    }

    fun createPlaylist(source: Source, title: String) {
        if (_state.value.creating || title.isBlank()) return
        if (source == Source.YOUTUBE && accountKey.isEmpty()) {
            _state.update { it.copy(creationError = getString(Res.string.quick_add_sign_in)) }
            return
        }
        val song = _state.value.pendingSong
        val account = accountKey
        _state.update { it.copy(creating = true, creationError = null) }
        viewModelScope.launch {
            try {
                withTimeout(30_000L) {
                    if (source == Source.YOUTUBE) {
                        playlistRepository.createYouTubePlaylist(title, song?.videoId).collect { result ->
                            when (result) {
                                is Resource.Success -> result.data?.let { playlist ->
                                    check(account == accountKey) { "The Google account changed. Refresh your playlists before continuing." }
                                    _state.update { it.copy(youTubePlaylists = listOf(playlist) + it.youTubePlaylists.filterNot { p -> p.browseId == playlist.browseId }) }
                                    created(QuickAddDestination(Source.YOUTUBE, playlist.browseId, playlist.title, account), song)
                                }
                                is Resource.Error -> _state.update { it.copy(creationError = result.message ?: getString(Res.string.error)) }
                            }
                        }
                    } else {
                        localPlaylistRepository.createLocalPlaylist(title, song).collect { result ->
                            when (result) {
                                is LocalResource.Success -> result.data?.let { playlist ->
                                    _state.update { it.copy(localPlaylists = listOf(playlist) + it.localPlaylists.filterNot { p -> p.id == playlist.id }) }
                                    created(QuickAddDestination(Source.LOCAL, playlist.id.toString(), playlist.title), song)
                                }
                                is LocalResource.Error -> _state.update { it.copy(creationError = result.message ?: getString(Res.string.error)) }
                                is LocalResource.Loading -> Unit
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _state.update { it.copy(creationError = "Could not confirm creation. Refresh your playlists before trying again.") }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { _state.update { it.copy(creationError = e.message ?: getString(Res.string.error)) } }
            finally { _state.update { it.copy(creating = false) } }
        }
    }

    private fun created(target: QuickAddDestination, song: SongEntity?) {
        destinationChosen = true
        _state.update { it.copy(destination = target, showPicker = false, pendingSong = null,
            added = if (song == null) it.added else it.added + (song.videoId to target)) }
        if (song != null && target.source == Source.YOUTUBE) addedYouTubeItems.add(target.key to song.videoId)
        persistDestination(target)
    }

    private fun choose(target: QuickAddDestination) {
        destinationChosen = true
        val pending = _state.value.pendingSong
        _state.update { it.copy(destination = target, showPicker = false, pendingSong = null) }
        persistDestination(target)
        if (pending != null) add(pending, target)
    }

    private fun persistDestination(target: QuickAddDestination?) {
        viewModelScope.launch {
            preferenceMutex.withLock { dataStoreManager.putString(DESTINATION_KEY, target?.encode().orEmpty()) }
        }
    }

    fun refreshYouTubePlaylists() {
        youTubeJob?.cancel()
        if (accountKey.isEmpty()) {
            _state.update { it.copy(loadingYouTube = false, youTubeStatus = getString(Res.string.quick_add_sign_in)) }
            return
        }
        val requestedAccount = accountKey
        youTubeJob = viewModelScope.launch {
            _state.update { it.copy(loadingYouTube = true, youTubeStatus = null) }
            try {
                val playlists = playlistRepository.getLibraryPlaylist().first()
                if (accountKey == requestedAccount) {
                    _state.update {
                        it.copy(youTubePlaylists = playlists.orEmpty().filter { p -> p.browseId != "VLLM" },
                            youTubeStatus = if (playlists == null) getString(Res.string.quick_add_youtube_failed) else null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(youTubeStatus = getString(Res.string.quick_add_youtube_failed)) }
            } finally {
                if (accountKey == requestedAccount) _state.update { it.copy(loadingYouTube = false) }
            }
        }
    }

    private fun add(song: SongEntity, target: QuickAddDestination) {
        if (song.videoId in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + song.videoId) }
        viewModelScope.launch {
            try {
                // The repository appends a whole tracks list; serialize quick adds to avoid lost writes.
                addMutex.withLock {
                    if (!target.belongsTo(accountKey)) {
                        invalidateTarget(target, song)
                        return@withLock
                    }
                    var saved = false
                    var failure: String? = null
                    if (target.source == Source.LOCAL) {
                        val playlist = localPlaylistRepository.getLocalPlaylist(target.id.toLong())
                            .first { it !is LocalResource.Loading }.data
                        if (playlist == null) {
                            invalidateTarget(target, song)
                            return@withLock
                        }
                        if (song.videoId in localPlaylistRepository.getListTrackVideoId(playlist.id)) {
                            saved = true
                        } else {
                            localPlaylistRepository.addTrackToLocalPlaylist(playlist.id, song,
                                getString(Res.string.added_to_playlist), getString(Res.string.added_to_youtube_playlist),
                                getString(Res.string.error)).collect { result ->
                                when (result) {
                                    is LocalResource.Success -> saved = true
                                    is LocalResource.Error -> failure = result.message
                                    is LocalResource.Loading -> Unit
                                }
                            }
                        }
                    } else {
                        val receipt = target.key to song.videoId
                        if (receipt in addedYouTubeItems) {
                            saved = true
                        } else {
                            localPlaylistRepository.addYouTubePlaylistItem(target.id, song.videoId).collect { result ->
                                when (result) {
                                    is LocalResource.Success -> saved = true
                                    is LocalResource.Error -> failure = result.message
                                    is LocalResource.Loading -> Unit
                                }
                            }
                            if (saved) addedYouTubeItems.add(receipt)
                        }
                    }
                    if (saved && target.belongsTo(accountKey)) _state.update { it.copy(added = it.added + (song.videoId to target)) }
                    if (failure != null) makeToast(if (saved) getString(Res.string.quick_add_sync_failed)
                        else if (target.source == Source.YOUTUBE) getString(Res.string.quick_add_youtube_add_failed) else failure)
                    else if (!saved) makeToast(getString(Res.string.error))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                makeToast(e.message ?: getString(Res.string.error))
            } finally {
                _state.update { it.copy(busy = it.busy - song.videoId) }
            }
        }
    }

    private fun invalidateTarget(target: QuickAddDestination, song: SongEntity) {
        if (_state.value.destination?.key == target.key) {
            _state.update { it.copy(destination = null) }
            persistDestination(null)
        }
        makeToast(getString(Res.string.quick_add_choose_again))
        openPicker(song)
    }

    companion object {
        private const val DESTINATION_KEY = QuickAddDestination.PREFERENCE_KEY
    }
}
