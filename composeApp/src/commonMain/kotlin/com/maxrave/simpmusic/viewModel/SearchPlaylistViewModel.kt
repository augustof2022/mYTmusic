package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.utils.LocalResource
import com.maxrave.simpmusic.utils.QuickAddDestination
import com.maxrave.simpmusic.utils.QuickAddDestination.Source
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    val pendingSong: SongEntity? = null,
    val loadingYouTube: Boolean = false,
    val youTubeStatus: String? = null,
    val busy: Set<String> = emptySet(),
    val added: Map<String, QuickAddDestination> = emptyMap(),
)

/** One remembered destination for search, independent of the multi-select playlist action. */
class SearchPlaylistViewModel(
    private val dataStoreManager: DataStoreManager,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
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
                _state.update { it.copy(destination = valid, youTubePlaylists = emptyList(),
                    added = it.added.filterValues { target -> target.belongsTo(currentAccount) }) }
                if (changedAccount) addedYouTubeItems.clear()
                if (valid != previous) persistDestination(null)
                if (_state.value.showPicker) refreshYouTubePlaylists()
            }
        }
        refreshLocalPlaylists()
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

    fun quickAdd(song: SongEntity) {
        val target = _state.value.destination
        if (target == null || !accountReady || !target.belongsTo(accountKey)) openPicker(song) else add(song, target)
    }

    fun openPicker(song: SongEntity? = null) {
        _state.update { it.copy(showPicker = true, pendingSong = song) }
        refreshLocalPlaylists()
        refreshYouTubePlaylists()
    }

    fun dismissPicker() {
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
        private const val DESTINATION_KEY = "search_quick_add_destination_v1"
    }
}
