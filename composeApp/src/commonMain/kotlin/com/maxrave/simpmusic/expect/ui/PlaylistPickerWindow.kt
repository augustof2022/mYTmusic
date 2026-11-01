package com.maxrave.simpmusic.expect.ui

import androidx.compose.ui.window.DialogProperties

/** Android supplies IME insets to the picker instead of panning its action buttons out of view. */
expect fun playlistPickerDialogProperties(): DialogProperties
