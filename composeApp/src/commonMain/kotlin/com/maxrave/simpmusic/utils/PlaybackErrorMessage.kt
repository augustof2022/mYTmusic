package com.maxrave.simpmusic.utils

/** Only genuine network/timeout errors use the existing localized connection message. */
fun playbackErrorMessage(errorCodeName: String): String? =
    when (errorCodeName) {
        "ERROR_CODE_TIMEOUT", "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
        "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" -> null
        "ERROR_CODE_IO_BAD_HTTP_STATUS" -> "The music server rejected this stream. Please try again."
        "ERROR_UNKNOWN", "TRANSITION_UNAVAILABLE" -> "Could not get playable audio for this song. Try another upload."
        else -> "Could not play this song ($errorCodeName)."
    }
