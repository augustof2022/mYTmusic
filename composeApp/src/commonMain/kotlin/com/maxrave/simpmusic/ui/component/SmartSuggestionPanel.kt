package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.suggestion.FilterKind
import com.maxrave.domain.suggestion.Speed
import com.maxrave.domain.suggestion.SuggestionFilter
import com.maxrave.simpmusic.ui.icon.Person
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.reload
import simpmusic.composeapp.generated.resources.suggest
import simpmusic.composeapp.generated.resources.suggest_auto
import simpmusic.composeapp.generated.resources.suggest_no_match
import simpmusic.composeapp.generated.resources.suggest_none_auto
import simpmusic.composeapp.generated.resources.suggest_reading_playlist
import simpmusic.composeapp.generated.resources.suggest_speed_fast
import simpmusic.composeapp.generated.resources.suggest_speed_medium
import simpmusic.composeapp.generated.resources.suggest_speed_slow

/**
 * The whole suggestion panel, for screens that do not already lay one out (the YouTube playlist
 * screen): a "Suggestion" pill that opens it, then header + Reload, chips, five songs with +.
 * The local playlist screen keeps its own header and uses [SuggestionFilterRow] alone.
 */
@Composable
fun SmartSuggestionPanel(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    songs: List<Track>,
    loading: Boolean,
    filters: List<SuggestionFilter>,
    selected: Set<SuggestionFilter>,
    filtersLoading: Boolean,
    playingVideoId: String?,
    onReload: () -> Unit,
    onToggle: (SuggestionFilter?) -> Unit,
    onAdd: (Track) -> Unit,
    onPlay: (Track) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SuggestionChipPill(
            label = stringResource(Res.string.suggest),
            on = open,
            onClick = { onOpenChange(!open) },
        )
        AnimatedVisibility(visible = open) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.suggest),
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 8.dp).weight(1f),
                    )
                    TextButton(onClick = onReload) {
                        Text(text = stringResource(Res.string.reload), color = Color.White)
                    }
                }
                SuggestionFilterRow(filters = filters, selected = selected, reading = filtersLoading, onToggle = onToggle)
                Spacer(modifier = Modifier.size(8.dp))
                Crossfade(targetState = loading) { isLoading ->
                    if (isLoading) {
                        CenterLoadingBox(modifier = Modifier.fillMaxWidth().height(200.dp))
                    } else {
                        Column {
                            if (songs.isEmpty()) {
                                Text(
                                    text =
                                        stringResource(
                                            if (selected.isEmpty()) Res.string.suggest_none_auto else Res.string.suggest_no_match,
                                        ),
                                    color = Color(0xC4FFFFFF),
                                    style = typo().bodySmall,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                            songs.forEach { track ->
                                SuggestItems(
                                    forceDark = true,
                                    track = track,
                                    isPlaying = playingVideoId == track.videoId,
                                    onAddClickListener = { onAdd(track) },
                                    onClickListener = { onPlay(track) },
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.size(12.dp))
                HorizontalDivider(color = Color.Gray, thickness = 0.5.dp)
                Spacer(modifier = Modifier.size(8.dp))
            }
        }
    }
}

/**
 * The chips above the suggestions: Auto first, then what this playlist is made of (genres, decades,
 * artists, speed). Tap several; same kind widens, different kinds narrow (see SmartSuggestion).
 * Styled like the playlist's Sort pill: white at 12% when off, solid white with dark text when on.
 */
@Composable
fun SuggestionFilterRow(
    filters: List<SuggestionFilter>,
    selected: Set<SuggestionFilter>,
    reading: Boolean,
    onToggle: (SuggestionFilter?) -> Unit,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SuggestionChipPill(
                label = stringResource(Res.string.suggest_auto),
                on = selected.isEmpty(),
                onClick = { onToggle(null) },
            )
            filters.forEach { filter ->
                SuggestionChipPill(
                    label = filter.label(),
                    on = filter in selected,
                    onClick = { onToggle(filter) },
                    isArtist = filter.kind == FilterKind.ARTIST,
                )
            }
        }
        if (reading) {
            Text(
                text = stringResource(Res.string.suggest_reading_playlist),
                color = Color(0xC4FFFFFF),
                style = typo().bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SuggestionChipPill(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    isArtist: Boolean = false,
) {
    // The pill looks 32dp tall; the Box gives it a 48dp touch target without changing the look.
    Box(
        modifier = Modifier.minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .widthIn(max = 180.dp)
                    .clip(CircleShape)
                    .background(if (on) Color.White else Color.White.copy(alpha = 0.12f))
                    .toggleable(value = on, role = Role.Checkbox, onValueChange = { onClick() })
                    .padding(vertical = 8.dp, horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Only artists get an icon: "Jazz" could be a genre or an artist, "2020s" and "Fast" cannot.
            if (isArtist) {
                Icon(
                    imageVector = SimpIcons.Person,
                    contentDescription = null,
                    tint = if (on) Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = typo().bodySmall,
                color = if (on) Color.Black else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SuggestionFilter.label(): String =
    when (kind) {
        FilterKind.DECADE -> "${value}s"
        FilterKind.SPEED ->
            when (value) {
                Speed.SLOW.name -> stringResource(Res.string.suggest_speed_slow)
                Speed.MEDIUM.name -> stringResource(Res.string.suggest_speed_medium)
                else -> stringResource(Res.string.suggest_speed_fast)
            }
        else -> value
    }
