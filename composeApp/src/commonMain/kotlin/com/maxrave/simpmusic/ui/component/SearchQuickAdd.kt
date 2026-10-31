package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maxrave.simpmusic.ui.icon.Star
import com.maxrave.simpmusic.ui.icon.StarBorder
import com.maxrave.simpmusic.ui.icon.Check
import com.maxrave.simpmusic.ui.icon.PlaylistAdd
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.utils.QuickAddDestination
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.search_add_favorite
import simpmusic.composeapp.generated.resources.search_remove_favorite
import simpmusic.composeapp.generated.resources.quick_add_action
import simpmusic.composeapp.generated.resources.quick_add_added
import simpmusic.composeapp.generated.resources.quick_add_change
import simpmusic.composeapp.generated.resources.quick_add_choose
import simpmusic.composeapp.generated.resources.quick_add_local
import simpmusic.composeapp.generated.resources.quick_add_target
import simpmusic.composeapp.generated.resources.quick_add_youtube

@Composable
fun SearchQuickAddButton(busy: Boolean, added: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !busy) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = if (added) SimpIcons.Check else SimpIcons.PlaylistAdd,
                contentDescription = stringResource(Res.string.quick_add_action),
                tint = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun SearchQuickAddDestination(destination: QuickAddDestination?, onChange: () -> Unit) {
    QuickAddMessage(
        text = if (destination == null) stringResource(Res.string.quick_add_choose)
            else stringResource(Res.string.quick_add_target, destination.displayName()),
        onChange = onChange,
    )
}

@Composable
fun SearchQuickAddReceipt(destination: QuickAddDestination?, onChange: () -> Unit) {
    if (destination != null) {
        QuickAddMessage(stringResource(Res.string.quick_add_added, destination.displayName()), onChange)
    }
}

@Composable
private fun QuickAddDestination.displayName(): String =
    stringResource(if (source == QuickAddDestination.Source.LOCAL) Res.string.quick_add_local else Res.string.quick_add_youtube, title)

@Composable
private fun QuickAddMessage(text: String, onChange: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onChange) { Text(stringResource(Res.string.quick_add_change)) }
    }
}

@Composable
fun SearchFavoriteButton(liked: Boolean, busy: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !busy) {
        Icon(
            imageVector = if (liked) SimpIcons.Star else SimpIcons.StarBorder,
            contentDescription = stringResource(if (liked) Res.string.search_remove_favorite else Res.string.search_add_favorite),
            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
