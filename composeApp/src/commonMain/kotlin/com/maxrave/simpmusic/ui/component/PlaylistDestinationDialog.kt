package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maxrave.simpmusic.utils.QuickAddDestination.Source

data class PlaylistPickerItem(val id: String, val title: String)

data class PlaylistPickerLabels(
    val title: String,
    val local: String,
    val youtube: String,
    val search: String,
    val createNew: String,
    val name: String,
    val create: String,
    val cancel: String,
    val close: String,
    val empty: String,
    val retry: String,
    val signIn: String,
    val privatePlaylist: String,
)

/** A finite viewport, one list, and no draggable/IME anchor animations during first-time selection. */
@Composable
fun PlaylistDestinationDialog(
    localPlaylists: List<PlaylistPickerItem>,
    youTubePlaylists: List<PlaylistPickerItem>,
    labels: PlaylistPickerLabels,
    initialSource: Source = Source.LOCAL,
    createOnly: Boolean = false,
    signedIn: Boolean = false,
    loadingYouTube: Boolean = false,
    youTubeError: String? = null,
    creating: Boolean = false,
    creationError: String? = null,
    onChoose: (Source, String) -> Unit,
    onCreate: (Source, String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var source by remember { mutableStateOf(initialSource) }
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var createForm by remember { mutableStateOf(createOnly) }
    Dialog(onDismissRequest = { if (!creating) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.94f).height(maxHeight * 0.82f),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (createForm) labels.createNew else labels.title, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Source.LOCAL to labels.local, Source.YOUTUBE to labels.youtube).forEach { (option, label) ->
                            TextButton(onClick = { source = option; query = "" }, enabled = !creating,
                                modifier = Modifier.weight(1f)) {
                                Text(label, color = if (source == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    if (createForm) {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                                    enabled = !creating, label = { Text(labels.name) }, modifier = Modifier.fillMaxWidth())
                            }
                            if (source == Source.YOUTUBE) {
                                item { Text(if (signedIn) labels.privatePlaylist else labels.signIn, style = MaterialTheme.typography.bodySmall) }
                            }
                            if (creationError != null) item { Text(creationError, color = MaterialTheme.colorScheme.error) }
                        }
                        Button(onClick = { onCreate(source, name.trim()) },
                            enabled = !creating && name.isNotBlank() && (source == Source.LOCAL || signedIn),
                            modifier = Modifier.fillMaxWidth()) {
                            if (creating) CircularProgressIndicator() else Text(labels.create)
                        }
                    } else {
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text(labels.search) }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { createForm = true; name = "" }, modifier = Modifier.fillMaxWidth()) {
                            Text("+ ${labels.createNew}")
                        }
                        val available = if (source == Source.LOCAL) localPlaylists else youTubePlaylists
                        val shown = available.filter { it.title.contains(query.trim(), ignoreCase = true) }
                        // This list has a finite remaining height even when data arrives with the keyboard open.
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            if (source == Source.YOUTUBE && loadingYouTube) item { CircularProgressIndicator(Modifier.padding(12.dp)) }
                            if (source == Source.YOUTUBE && (!signedIn || youTubeError != null)) {
                                item {
                                    Text(if (!signedIn) labels.signIn else youTubeError.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                    if (signedIn) TextButton(onClick = onRetry) { Text(labels.retry) }
                                }
                            }
                            if (shown.isEmpty() && !(source == Source.YOUTUBE && loadingYouTube)) item {
                                Text(labels.empty, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                            items(shown.distinctBy { it.id }, key = { it.id }) { playlist ->
                                Box(Modifier.fillMaxWidth().clickable { onChoose(source, playlist.id) }.padding(vertical = 16.dp, horizontal = 8.dp)) {
                                    Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (createForm && !createOnly) TextButton(onClick = { createForm = false }, enabled = !creating) { Text(labels.cancel) }
                        TextButton(onClick = onDismiss, enabled = !creating) { Text(labels.close) }
                    }
                }
            }
        }
    }
}
