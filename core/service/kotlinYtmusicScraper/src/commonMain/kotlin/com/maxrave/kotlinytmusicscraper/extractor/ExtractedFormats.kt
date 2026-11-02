package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import io.ktor.http.Url

internal fun streamMimeTypeOf(url: String): String? =
    runCatching { Url(url).parameters["mime"] }.getOrNull()

internal fun String.isAudioStreamUrl(): Boolean = streamMimeTypeOf(this)?.startsWith("audio/") == true

internal fun List<Pair<Int, String>>.audioStreamCandidates(knownAudioItags: Set<Int>): List<Pair<Int, String>> =
    filter { (itag, url) -> url.isNotBlank() && (itag in knownAudioItags || url.isAudioStreamUrl()) }
        .orderByAudioTrack(null)
        .distinctBy { it.first }

/**
 * Music and the extractor can use different clients, so their format lists need not overlap.
 * Keep matching metadata, but also retain extracted audio identified by its signed URL's MIME
 * type. In particular, an OK Music response can carry no streamingData at all.
 */
internal fun extractedFormats(
    metadata: PlayerResponse.StreamingData?,
    streams: List<Pair<Int, String>>,
    preferredAudioLanguage: String?,
): PlayerResponse.StreamingData {
    val selected = streams.filter { it.second.isNotBlank() }.orderByAudioTrack(preferredAudioLanguage).distinctBy { it.first }
    val urls = selected.toMap()
    fun List<PlayerResponse.StreamingData.Format>.withUrls() =
        mapNotNull { format ->
            val url = urls[format.itag] ?: return@mapNotNull null
            format.copy(url = url, contentLength = contentLengthOf(url) ?: format.contentLength, signatureCipher = null)
        }

    val formats = metadata?.formats.orEmpty().withUrls()
    val adaptiveFormats = metadata?.adaptiveFormats.orEmpty().withUrls()
    val knownItags = (formats + adaptiveFormats).mapTo(mutableSetOf()) { it.itag }
    val extraAudio =
        selected.mapNotNull { (itag, url) ->
            val mimeType = streamMimeTypeOf(url)?.takeIf { it.startsWith("audio/") } ?: return@mapNotNull null
            if (itag in knownItags) return@mapNotNull null
            PlayerResponse.StreamingData.Format(
                itag = itag,
                url = url,
                mimeType = mimeType,
                bitrate = 0,
                width = null,
                height = null,
                contentLength = contentLengthOf(url),
                quality = "",
                fps = null,
                qualityLabel = null,
                averageBitrate = null,
                audioQuality = null,
                approxDurationMs = null,
                audioSampleRate = null,
                audioChannels = null,
                loudnessDb = null,
                lastModified = null,
                signatureCipher = null,
            )
        }
    val manifests =
        selected.filter { (_, url) -> url.contains(".m3u8") || url.contains(".mpd") || url.contains("manifest") }
            .filter { (itag, _) -> formats.none { it.itag == itag } }
            .map { (itag, url) ->
                PlayerResponse.StreamingData.Format(
                    itag = itag,
                    url = url,
                    mimeType = "",
                    bitrate = 0,
                    width = if (itag == 96) 1920 else 1280,
                    height = if (itag == 96) 1080 else 720,
                    contentLength = null,
                    quality = "",
                    fps = null,
                    qualityLabel = null,
                    averageBitrate = null,
                    audioQuality = null,
                    approxDurationMs = null,
                    audioSampleRate = null,
                    audioChannels = null,
                    loudnessDb = null,
                    lastModified = null,
                    signatureCipher = null,
                )
            }
    return PlayerResponse.StreamingData(
        hlsManifestUrl = selected.firstOrNull { it.first == 96 }?.second,
        formats = formats + manifests,
        adaptiveFormats = adaptiveFormats + extraAudio,
        // If the metadata omits a lifetime, resolve again instead of caching an unknown expiry.
        expiresInSeconds = metadata?.expiresInSeconds ?: 0,
        serverAbrStreamingUrl = metadata?.serverAbrStreamingUrl,
    )
}

/** A rejected video rendition must not reject working audio, or vice versa. */
internal suspend fun PlayerResponse.StreamingData.withPlayableAudio(
    muxedAudioItag: Int? = null,
    isPlayable: suspend (String) -> Boolean,
): PlayerResponse.StreamingData? {
    fun PlayerResponse.StreamingData.Format.hasAudio() = isAudio || itag == muxedAudioItag
    val audioUrls = (formats.orEmpty() + adaptiveFormats).filter { it.hasAudio() }.mapNotNull { it.url }.distinct()
    val playable = mutableSetOf<String>()
    for (url in audioUrls) {
        if (url.isNotBlank() && isPlayable(url)) playable.add(url)
    }
    if (playable.isEmpty()) return null
    fun List<PlayerResponse.StreamingData.Format>.withoutRejectedAudio() = filter { !it.hasAudio() || it.url in playable }
    return copy(formats = formats?.withoutRejectedAudio(), adaptiveFormats = adaptiveFormats.withoutRejectedAudio())
}

/** Select the requested audio family, its high-quality twin, then another available audio format. */
fun List<PlayerResponse.StreamingData.Format>.selectAudioFormat(
    preferredItag: Int?,
    twinItag: Int?,
    muxedAudioItag: Int? = null,
): PlayerResponse.StreamingData.Format? {
    val audio = filter { it.isAudio && !it.url.isNullOrBlank() }
    return audio.find { it.itag == preferredItag }
        ?: audio.find { it.itag == twinItag }
        ?: audio.maxByOrNull { it.bitrate }
        ?: find { it.itag == muxedAudioItag && !it.url.isNullOrBlank() }
}
