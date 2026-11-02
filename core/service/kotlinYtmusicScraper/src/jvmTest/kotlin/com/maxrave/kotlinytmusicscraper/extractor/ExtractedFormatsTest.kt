package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractedFormatsTest {
    private fun url(itag: Int, mime: String = "audio%2Fwebm", extra: String = "") =
        "https://media.example/videoplayback?itag=$itag&mime=$mime&clen=12345$extra"

    private fun metadata(vararg streams: Pair<Int, String>) = extractedFormats(null, streams.toList(), null).copy(expiresInSeconds = 3600)

    @Test
    fun metadataOnlyPlayerResponseCanBeDecodedBeforeResolvingStreams() {
        val json = Json { explicitNulls = false }
        val response = json.decodeFromString<PlayerResponse>(
            """{"responseContext":{},"playabilityStatus":{"status":"OK"},"streamingData":{"serverAbrStreamingUrl":"https://media.example/sabr"}}""",
        )
        assertEquals("OK", response.playabilityStatus.status)
        assertTrue(assertNotNull(response.streamingData).adaptiveFormats.isEmpty())
        assertEquals(0, response.streamingData.expiresInSeconds)
    }

    @Test
    fun audioOnlyExtractionDoesNotRequireVideoOrAnAppQualityItag() {
        val aac = 140 to url(140, "audio%2Fmp4")
        assertEquals(listOf(aac), listOf(aac).audioStreamCandidates(setOf(250, 251, 774, 141)))
        assertTrue(listOf(137 to url(137, "video%2Fmp4")).audioStreamCandidates(setOf(251)).isEmpty())
        assertTrue(listOf(251 to "").audioStreamCandidates(setOf(251)).isEmpty())
    }

    @Test
    fun missingMusicStreamingDataStillRetainsExtractedAudio() = runBlocking {
        val result = extractedFormats(null, listOf(140 to url(140, "audio%2Fmp4")), null).withPlayableAudio { true }
        val audio = assertNotNull(result).adaptiveFormats.single()
        assertEquals(140, audio.itag)
        assertEquals("audio/mp4", audio.mimeType)
        assertEquals(12345L, audio.contentLength)
        assertEquals(0, result.expiresInSeconds)
    }

    @Test
    fun nonOverlappingMusicAndExtractorFormatsStillPlay() = runBlocking {
        val music = metadata(251 to url(251))
        val result = assertNotNull(
            extractedFormats(music, listOf(140 to url(140, "audio%2Fmp4")), null).withPlayableAudio { true },
        )
        assertEquals(listOf(140), result.adaptiveFormats.map { it.itag })
        assertEquals(3600, result.expiresInSeconds)
        assertEquals(140, result.adaptiveFormats.selectAudioFormat(251, null)?.itag)
    }

    @Test
    fun matchingMetadataKeepsCodecAndQualityButUsesTheExtractedLength() {
        val music = metadata(251 to url(251)).let {
            it.copy(adaptiveFormats = listOf(it.adaptiveFormats.single().copy(mimeType = "audio/webm; codecs=\"opus\"", bitrate = 128000)))
        }
        val freshUrl = url(251).replace("clen=12345", "clen=54321")
        val format = extractedFormats(music, listOf(251 to freshUrl), null).adaptiveFormats.single()
        assertEquals(freshUrl, format.url)
        assertEquals(54321L, format.contentLength)
        assertEquals("audio/webm; codecs=\"opus\"", format.mimeType)
        assertEquals(128000, format.bitrate)
    }

    @Test
    fun missingMetadataKeepsThePreferredLanguageAndItsLength() {
        val original = url(251, extra = "&xtags=acont%3Doriginal%3Alang%3Den")
        val dub = url(251, extra = "&xtags=acont%3Ddubbed%3Alang%3Dvi").replace("clen=12345", "clen=22222")
        val streams = listOf(251 to original, 251 to dub)
        val format = extractedFormats(null, streams, "vi").adaptiveFormats.single()
        assertEquals(dub, format.url)
        assertEquals(22222L, format.contentLength)
        assertEquals(original, extractedFormats(null, streams, null).adaptiveFormats.single().url)
    }

    @Test
    fun anUnplayableVideoDoesNotRejectPlayableAudio() = runBlocking {
        val audio = url(251)
        val video = url(137, "video%2Fmp4")
        val data = metadata(251 to audio).copy(
            formats = listOf(metadata(251 to audio).adaptiveFormats.single().copy(itag = 137, url = video, width = 1920, mimeType = "video/mp4")),
        )
        val checked = mutableListOf<String>()
        val result = data.withPlayableAudio { checked.add(it); it == audio }
        assertNotNull(result)
        assertEquals(listOf(audio), checked)
    }

    @Test
    fun rejectedPreferredQualityFallsBackToWorkingAudioInsteadOfVideo() = runBlocking {
        val high = url(774)
        val normal = url(251)
        val data = metadata(774 to high, 251 to normal)
        val result = assertNotNull(data.withPlayableAudio { it == normal })
        assertEquals(listOf(251), result.adaptiveFormats.map { it.itag })
        assertEquals(251, result.adaptiveFormats.selectAudioFormat(774, 141)?.itag)
    }

    @Test
    fun highQualityTwinStillWinsOverOtherAudio() {
        val data = metadata(251 to url(251), 141 to url(141, "audio%2Fmp4"))
        assertEquals(141, data.adaptiveFormats.selectAudioFormat(774, 141)?.itag)
    }

    @Test
    fun videoOnlyResponseCannotBeSelectedAsAudio() = runBlocking {
        val video = metadata(251 to url(251)).adaptiveFormats.single().copy(itag = 137, width = 1920, mimeType = "video/mp4")
        val data = metadata().copy(adaptiveFormats = listOf(video))
        assertNull(data.withPlayableAudio { true })
        assertNull(data.adaptiveFormats.selectAudioFormat(774, 141))
    }

    @Test
    fun muxedAudioVideoRemainsAnExplicitAudioFallback() = runBlocking {
        val muxedUrl = url(18, "video%2Fmp4")
        val muxed = metadata(251 to url(251)).adaptiveFormats.single().copy(itag = 18, url = muxedUrl, width = 640, mimeType = "video/mp4")
        val data = metadata().copy(formats = listOf(muxed))
        val result = assertNotNull(data.withPlayableAudio(18) { it == muxedUrl })
        assertEquals(18, result.formats?.selectAudioFormat(774, 141, 18)?.itag)
        assertEquals(listOf(18 to muxedUrl), listOf(18 to muxedUrl).audioStreamCandidates(setOf(251, 18)))
    }

    @Test
    fun rejectedOrEmptyStreamsAreNotReportedAsPlayable() = runBlocking {
        assertNull(metadata(251 to url(251)).withPlayableAudio { false })
        assertNull(extractedFormats(null, emptyList(), null).withPlayableAudio { true })
        assertNull(extractedFormats(null, listOf(251 to ""), null).withPlayableAudio { true })
    }

    @Test
    fun cancellationDoesNotBecomeAnotherStreamFailure() = runBlocking<Unit> {
        assertFailsWith<CancellationException> {
            metadata(251 to url(251)).withPlayableAudio { throw CancellationException("Track changed") }
        }
    }

    @Test
    fun extractedManifestIsRetainedAlongsideAudio() {
        val manifest = "https://media.example/manifest/playlist.m3u8"
        val data = extractedFormats(null, listOf(251 to url(251), 96 to manifest), null)
        assertEquals(manifest, data.hlsManifestUrl)
        assertEquals(manifest, data.formats?.single()?.url)
        assertEquals(251, data.adaptiveFormats.single().itag)
    }
}
