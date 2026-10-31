package com.maxrave.simpmusic.utils

/** Stable playlist identity; the title is display text and can contain arbitrary newlines. */
data class QuickAddDestination(
    val source: Source,
    val id: String,
    val title: String,
    val account: String = "",
) {
    enum class Source { LOCAL, YOUTUBE }

    val key: String get() = "${source.name}:$account:$id"

    fun encode(): String = listOf("1", source.name, id, account, title).joinToString("\n")

    fun belongsTo(accountKey: String): Boolean = source == Source.LOCAL || (accountKey.isNotEmpty() && account == accountKey)

    companion object {
        fun decode(value: String?): QuickAddDestination? {
            val fields = value?.split('\n', limit = 5) ?: return null
            if (fields.size != 5 || fields[0] != "1" || fields[2].isBlank() || fields[4].isBlank()) return null
            val source = Source.entries.firstOrNull { it.name == fields[1] } ?: return null
            if (source == Source.LOCAL && (fields[2].toLongOrNull() ?: 0) <= 0) return null
            if (source == Source.YOUTUBE && fields[3].isEmpty()) return null
            return QuickAddDestination(source, fields[2], fields[4], fields[3])
        }
    }
}
