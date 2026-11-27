package com.maxrave.data.suggestion

import com.maxrave.domain.suggestion.SuggestionTagRepository
import com.maxrave.domain.suggestion.TrackTags
import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.TimeSource

/**
 * Genre, release year and BPM for a song, from Deezer's public API (free, no key, no sign-in).
 *
 * - Genre and year come from the album (`/album/{id}`), so one album call serves every song on it.
 * - BPM needs its own `/track/{id}` call and is 0 for many songs, so it is asked for only when
 *   the caller will use it.
 * - Deezer allows 50 requests per 5 seconds; requests start at least [MIN_GAP_MS] apart.
 * - Answers are kept for the app's lifetime. A rate-limit answer is not kept, so it is asked again.
 */
class DeezerTagSource(
    private val client: HttpClient =
        HttpClient(getEngine()) {
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
            }
        },
) : SuggestionTagRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheLock = Mutex()
    private val songCache = mutableMapOf<String, TrackTags?>()
    private val bpmCache = mutableMapOf<Long, Int?>()
    private val albumCache = mutableMapOf<Long, Pair<Set<String>, Int?>>()
    private val searchCache = mutableMapOf<String, Pair<Long, Long>?>()

    private val gapLock = Mutex()
    private var lastRequest: TimeSource.Monotonic.ValueTimeMark? = null

    override suspend fun tagsFor(
        title: String,
        artist: String,
        withBpm: Boolean,
    ): TrackTags? {
        val key = "${artist.lowercase()}|${cleanTitle(title).lowercase()}|$withBpm"
        cacheLock.withLock { if (songCache.containsKey(key)) return songCache[key] }
        return try {
            val found = search(cleanTitle(title), artist) ?: return remember(key, null)
            val (trackId, albumId) = found
            val (genres, year) = album(albumId) ?: (emptySet<String>() to null)
            val bpm = if (withBpm) bpm(trackId) else null
            remember(key, TrackTags(genres = genres, year = year, bpm = bpm))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimited) {
            Logger.w(TAG, "Deezer rate limit; not caching $key")
            null
        } catch (e: Exception) {
            Logger.w(TAG, "Deezer lookup failed for $key: ${e.message}")
            null
        }
    }

    /** The first result whose artist matches, as (track id, album id). */
    private suspend fun search(
        title: String,
        artist: String,
    ): Pair<Long, Long>? {
        val key = "${artist.lowercase()}|${title.lowercase()}"
        cacheLock.withLock { if (searchCache.containsKey(key)) return searchCache[key] }
        val body = request("search") { parameter("q", "$artist $title"); parameter("limit", 5) }
        val hit =
            body["data"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { item ->
                val name = item["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content.orEmpty()
                sameArtist(name, artist)
            }
        val result =
            hit?.let { item ->
                val trackId = item["id"]?.jsonPrimitive?.longOrNull
                val albumId = item["album"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
                if (trackId != null && albumId != null) trackId to albumId else null
            }
        cacheLock.withLock { searchCache[key] = result }
        return result
    }

    private suspend fun album(albumId: Long): Pair<Set<String>, Int?>? {
        cacheLock.withLock { albumCache[albumId]?.let { return it } }
        val body = request("album/$albumId") {}
        val genres =
            body["genres"]?.jsonObject?.get("data")?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                ?.toSet()
                .orEmpty()
        val year = body["release_date"]?.jsonPrimitive?.content?.take(4)?.toIntOrNull()
        val result = genres to year
        cacheLock.withLock { albumCache[albumId] = result }
        return result
    }

    private suspend fun bpm(trackId: Long): Int? {
        cacheLock.withLock { if (bpmCache.containsKey(trackId)) return bpmCache[trackId] }
        val body = request("track/$trackId") {}
        // Deezer sends the tempo as a decimal ("bpm": 120.5) or 0 when it has none.
        val raw = body["bpm"]?.jsonPrimitive
        val value = (raw?.intOrNull ?: raw?.content?.toDoubleOrNull()?.toInt())?.takeIf { it > 0 }
        cacheLock.withLock { bpmCache[trackId] = value }
        return value
    }

    private suspend fun request(
        path: String,
        params: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): JsonObject {
        gapLock.withLock {
            lastRequest?.elapsedNow()?.inWholeMilliseconds?.let { since ->
                if (since < MIN_GAP_MS) delay(MIN_GAP_MS - since)
            }
            lastRequest = TimeSource.Monotonic.markNow()
        }
        val text = client.get("$BASE/$path", params).bodyAsText()
        val body = json.parseToJsonElement(text).jsonObject
        // Deezer answers errors with HTTP 200 and an "error" object; code 4 is its quota limit.
        body["error"]?.jsonObject?.let { err ->
            if (err["code"]?.jsonPrimitive?.intOrNull == 4) throw RateLimited()
            error("Deezer error: ${err["message"]?.jsonPrimitive?.content}")
        }
        return body
    }

    private suspend fun remember(
        key: String,
        tags: TrackTags?,
    ): TrackTags? {
        cacheLock.withLock { songCache[key] = tags }
        return tags
    }

    private class RateLimited : Exception()

    companion object {
        private const val TAG = "DeezerTagSource"
        private const val BASE = "https://api.deezer.com"
        private const val MIN_GAP_MS = 110L

        private val noise =
            Regex(
                """\s*[(\[][^)\]]*(official|video|lyric|audio|visuali[sz]er|mv|hd|4k|remaster)[^)\]]*[)\]]""",
                RegexOption.IGNORE_CASE,
            )

        /** "Snooze (Official Video)" -> "Snooze": YouTube titles carry noise Deezer's do not. */
        internal fun cleanTitle(title: String): String = title.replace(noise, "").trim()

        internal fun sameArtist(
            deezer: String,
            youtube: String,
        ): Boolean {
            val a = deezer.lowercase().trim()
            val b = youtube.lowercase().trim()
            return a.isNotEmpty() && b.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
        }
    }
}
