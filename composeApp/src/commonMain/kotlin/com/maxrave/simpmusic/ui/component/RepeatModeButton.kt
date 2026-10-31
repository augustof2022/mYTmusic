package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.simpmusic.ui.icon.Repeat
import com.maxrave.simpmusic.ui.icon.RepeatOne
import com.maxrave.simpmusic.ui.icon.SimpIcons
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.repeat_cycle_hint
import simpmusic.composeapp.generated.resources.repeat_mode_all
import simpmusic.composeapp.generated.resources.repeat_mode_off
import simpmusic.composeapp.generated.resources.repeat_mode_one
import simpmusic.composeapp.generated.resources.repeat_short_all
import simpmusic.composeapp.generated.resources.repeat_short_off
import simpmusic.composeapp.generated.resources.repeat_short_one

/** All player styles and the queue expose the same engine-backed three-state control. */
@Composable
fun RepeatModeButton(
    repeatState: RepeatState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    activeColor: Color = MaterialTheme.colorScheme.primary,
) {
    val label = stringResource(when (repeatState) {
        RepeatState.None -> Res.string.repeat_mode_off
        RepeatState.All -> Res.string.repeat_mode_all
        RepeatState.One -> Res.string.repeat_mode_one
    })
    val shortLabel = stringResource(when (repeatState) {
        RepeatState.None -> Res.string.repeat_short_off
        RepeatState.All -> Res.string.repeat_short_all
        RepeatState.One -> Res.string.repeat_short_one
    })
    val hint = stringResource(Res.string.repeat_cycle_hint)
    val tint = if (repeatState == RepeatState.None) contentColor else activeColor
    val buttonModifier = modifier.clip(RoundedCornerShape(20.dp))
        .clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = hint; stateDescription = label }
        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).padding(4.dp)
    val icon: @Composable () -> Unit = {
        Icon(if (repeatState == RepeatState.One) SimpIcons.RepeatOne else SimpIcons.Repeat,
            contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
    if (compact) {
        Column(buttonModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            icon()
            Text(shortLabel, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    } else {
        Row(buttonModifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Text(label, color = tint, style = MaterialTheme.typography.labelMedium)
        }
    }
}
