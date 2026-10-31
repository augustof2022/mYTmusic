package com.maxrave.simpmusic.ui.component

import androidx.compose.runtime.Composable
import com.maxrave.simpmusic.utils.QuickAddDestination.Source
import com.maxrave.simpmusic.viewModel.SearchPlaylistState
import com.maxrave.simpmusic.viewModel.SearchPlaylistViewModel
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.close
import simpmusic.composeapp.generated.resources.create
import simpmusic.composeapp.generated.resources.no_playlist_found
import simpmusic.composeapp.generated.resources.playlist_create_new
import simpmusic.composeapp.generated.resources.playlist_name_label
import simpmusic.composeapp.generated.resources.playlist_picker_title
import simpmusic.composeapp.generated.resources.playlist_private_youtube
import simpmusic.composeapp.generated.resources.playlist_source_local
import simpmusic.composeapp.generated.resources.playlist_source_youtube
import simpmusic.composeapp.generated.resources.quick_add_sign_in
import simpmusic.composeapp.generated.resources.retry
import simpmusic.composeapp.generated.resources.search

@Composable
fun SearchPlaylistPicker(controller: SearchPlaylistViewModel, state: SearchPlaylistState) {
    if (!state.showPicker) return
    PlaylistDestinationDialog(
        localPlaylists = state.localPlaylists.map { PlaylistPickerItem(it.id.toString(), it.title) },
        youTubePlaylists = state.youTubePlaylists.map { PlaylistPickerItem(it.browseId, it.title) },
        labels = PlaylistPickerLabels(
            title = stringResource(Res.string.playlist_picker_title),
            local = stringResource(Res.string.playlist_source_local),
            youtube = stringResource(Res.string.playlist_source_youtube),
            search = stringResource(Res.string.search), createNew = stringResource(Res.string.playlist_create_new),
            name = stringResource(Res.string.playlist_name_label), create = stringResource(Res.string.create),
            cancel = stringResource(Res.string.cancel), close = stringResource(Res.string.close),
            empty = stringResource(Res.string.no_playlist_found), retry = stringResource(Res.string.retry),
            signIn = stringResource(Res.string.quick_add_sign_in), privatePlaylist = stringResource(Res.string.playlist_private_youtube),
        ),
        initialSource = state.destination?.source ?: Source.LOCAL,
        createOnly = state.createOnly,
        signedIn = state.signedIn, loadingYouTube = state.loadingYouTube,
        youTubeError = state.youTubeStatus, creating = state.creating, creationError = state.creationError,
        onChoose = { source, id ->
            when (source) {
                Source.LOCAL -> state.localPlaylists.firstOrNull { it.id.toString() == id }?.let(controller::chooseLocal)
                Source.YOUTUBE -> state.youTubePlaylists.firstOrNull { it.browseId == id }?.let(controller::chooseYouTube)
            }
        },
        onCreate = controller::createPlaylist,
        onRetry = controller::refreshYouTubePlaylists,
        onDismiss = controller::dismissPicker,
    )
}
