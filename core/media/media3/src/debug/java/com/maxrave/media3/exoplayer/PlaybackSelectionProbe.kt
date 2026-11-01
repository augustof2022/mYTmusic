package com.maxrave.media3.exoplayer

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.StreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Debug-only end-to-end exercise of the real adapter using generated, offline audio. */
@OptIn(UnstableApi::class)
object PlaybackSelectionProbe {
    suspend fun run(context: Context): List<String> {
        val koin = GlobalContext.get()
        val settings: DataStoreManager = koin.get()
        val streams: StreamRepository = koin.get()
        settings.setCrossfadeEnabled(true)
        settings.setCrossfadeDuration(1_000)
        settings.setCrossfadeDjMode(false)
        settings.setCrossfadeSkipAlbum(false)
        val items = withContext(Dispatchers.IO) {
            listOf("A", "B", "C").mapIndexed { index, name ->
                val file = File(context.cacheDir, "selection-probe-$name.wav")
                writeTone(file, 320 + index * 110)
                GenericMediaItem(name, Uri.fromFile(file).toString(), GenericMediaMetadata(title = name))
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val adapter = CrossfadeExoPlayerAdapter(
            context = context,
            coroutineScope = scope,
            dataStoreManager = settings,
            mediaSourceFactory = DefaultMediaSourceFactory(context),
            audioAttributes = AudioAttributes.DEFAULT,
            streamRepository = streams,
        )
        var fadeStarts = 0
        adapter.addListener(object : MediaPlayerListener {
            override fun onCrossfadeStateChanged(isCrossfading: Boolean) {
                if (isCrossfading) fadeStarts++
            }
        })
        suspend fun waitFor(label: String, condition: () -> Boolean) {
            try {
                withTimeout(20_000L) { while (!condition()) delay(25) }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw IllegalStateException("Timed out waiting for $label", error)
            }
        }
        suspend fun active(id: String) {
            waitFor("playing $id") {
                CrossfadeVideoState.frame.value.outgoing?.let {
                    it.currentMediaItem?.mediaId == id && it.isPlaying
                } == true
            }
        }
        fun current(): Player = checkNotNull(CrossfadeVideoState.frame.value.outgoing)
        fun trackResumes(player: Player): () -> Boolean {
            var resumed = false
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) resumed = true
                }
            })
            return { resumed }
        }
        val checks = mutableListOf<String>()
        try {
            // Give the adapter's settings collectors time to consume the configured values.
            delay(500)
            adapter.selectMediaItem(items[0], true)
            active("A")
            adapter.pause()
            waitFor("A paused") { !current().isPlaying && !adapter.playWhenReady }
            var oldResumed = trackResumes(current())
            var fadesBefore = fadeStarts
            adapter.selectMediaItem(items[1], true)
            active("B")
            check(!oldResumed()) { "The paused song resumed before the new selection" }
            check(fadeStarts == fadesBefore) { "A paused selection started a crossfade" }
            checks += "Paused: selected song starts without resuming or fading the old song"

            fadesBefore = fadeStarts
            adapter.selectMediaItem(items[2], true)
            waitFor("active crossfade") { fadeStarts > fadesBefore }
            active("C")
            checks += "Playing: selecting another song still crossfades"

            adapter.addMediaItem(items[0])
            adapter.pause()
            waitFor("C paused") { !current().isPlaying && !adapter.playWhenReady }
            oldResumed = trackResumes(current())
            fadesBefore = fadeStarts
            adapter.selectQueueItem(1)
            active("A")
            check(!oldResumed()) { "The paused song resumed when selecting a queue item" }
            check(fadeStarts == fadesBefore) { "A paused queue selection started a crossfade" }
            checks += "Paused queue: selected song starts directly"

            oldResumed = trackResumes(current())
            fadesBefore = fadeStarts
            adapter.stop()
            adapter.selectMediaItem(items[1], true)
            active("B")
            check(!oldResumed()) { "The stopped song restarted before the new selection" }
            check(fadeStarts == fadesBefore) { "A stopped selection started a crossfade" }
            checks += "Stopped: selected song starts directly"
            return checks
        } finally {
            adapter.release()
        }
    }

    private fun writeTone(file: File, frequency: Int) {
        val rate = 44_100
        val count = rate * 20
        val bytes = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + count * 2)
        bytes.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        bytes.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        bytes.put("data".toByteArray(Charsets.US_ASCII)).putInt(count * 2)
        repeat(count) { sample ->
            bytes.putShort((sin(2.0 * PI * frequency * sample / rate) * 4_000).toInt().toShort())
        }
        file.writeBytes(bytes.array())
    }
}
