package com.maxrave.simpmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.simpmusic.ui.component.AddToPlaylistModalBottomSheet
import com.maxrave.simpmusic.ui.component.PlaylistDestinationDialog
import com.maxrave.simpmusic.ui.component.PlaylistPickerItem
import com.maxrave.simpmusic.ui.component.PlaylistPickerLabels
import com.maxrave.simpmusic.utils.QuickAddDestination.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debug-only UI probe. It never calls YouTube or mutates a real account. */
class PlaylistPickerProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val legacy = intent.getBooleanExtra("legacy", false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val scope = rememberCoroutineScope()
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                val requester = remember { FocusRequester() }
                var query by remember { mutableStateOf("dreams") }
                var open by remember { mutableStateOf(false) }
                var ready by remember { mutableStateOf(false) }
                var creating by remember { mutableStateOf(false) }
                var destination by remember { mutableStateOf<String?>(null) }
                var destinationSource by remember { mutableStateOf(Source.LOCAL) }
                var result by remember { mutableStateOf("NO_DESTINATION") }
                LaunchedEffect(Unit) { requester.requestFocus(); keyboard?.show() }
                LaunchedEffect(open) { if (open) { ready = false; delay(1800); ready = true } }
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text("Song search") },
                        modifier = Modifier.focusRequester(requester))
                    Button(onClick = {
                        if (!legacy) { keyboard?.hide(); focus.clearFocus(force = true) }
                        open = true
                    }) { Text("Open picker") }
                    Text(result)
                    Button(onClick = { result = if (destination != null) "QUICK_ADD_${destinationSource.name}" else "MISSING_DESTINATION" }) { Text("Add next song") }
                    Button(onClick = { keyboard?.hide(); focus.clearFocus(force = true); open = true }) { Text("Change destination") }
                }
                val locals = if (ready) (1..60).map { PlaylistPickerItem("$it", "Local playlist $it") } else emptyList()
                val youtube = if (ready) (1..60).map { PlaylistPickerItem("VLPLprobe$it", "YouTube playlist $it") } else emptyList()
                if (open && legacy) {
                    AddToPlaylistModalBottomSheet(true,
                        locals.map { LocalPlaylistEntity(id = it.id.toLong(), title = it.title) },
                        youtube.map { PlaylistsResult("", it.id, "", "0", "Playlist", emptyList(), it.title) },
                        showYouTubeTab = true, loadingYouTube = !ready,
                        onClick = { open = false }, onYTPlaylistClick = { open = false }, onDismiss = { open = false })
                } else if (open) {
                    PlaylistDestinationDialog(locals, youtube,
                        labels = PlaylistPickerLabels("Add to playlist", "Local", "YouTube", "Search playlists", "Create new playlist",
                            "Playlist name", "Create", "Cancel", "Close", "No playlists", "Retry", "Sign in", "Private on YouTube"),
                        initialSource = destinationSource, signedIn = true, loadingYouTube = !ready, creating = creating,
                        onChoose = { source, id -> destinationSource = source; destination = id; result = "CHOSEN_${source.name}"; open = false },
                        onCreate = { source, title ->
                            creating = true
                            scope.launch { delay(350); destinationSource = source; destination = title; result = "CREATED_${source.name}_$title"; open = false; creating = false }
                        },
                        onRetry = {}, onDismiss = { open = false })
                }
            }
        }
    }
}
