package com.maxrave.domain.utils

object YouTubePlaylistPolicy {
    fun id(value: String): String = value.removePrefix("VL").also {
        require(it.startsWith("PL") && it.length > 2 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }) {
            "Choose an editable YouTube playlist."
        }
    }

    fun acknowledged(httpStatus: Int, status: String?, hasError: Boolean, deleting: Boolean = false): Boolean =
        httpStatus in 200..299 && !hasError &&
            (status == "STATUS_SUCCEEDED" || status == "SUCCESS" || (deleting && status == null))
}
