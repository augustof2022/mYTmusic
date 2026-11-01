package com.maxrave.simpmusic

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.maxrave.media3.exoplayer.PlaybackSelectionProbe
import org.json.JSONArray

/** Invoked only by the Android emulator regression run, using local generated tones. */
class PlaybackSelectionProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var status by remember { mutableStateOf("PLAYBACK_TEST_RUNNING") }
            LaunchedEffect(Unit) {
                try {
                    val checks = PlaybackSelectionProbe.run(applicationContext)
                    Log.i("SeguePlaybackProbe", "PASS " + JSONArray(checks).toString())
                    status = "PLAYBACK_TEST_PASSED"
                } catch (error: Exception) {
                    Log.e("SeguePlaybackProbe", "FAIL " + (error.message ?: error.javaClass.simpleName), error)
                    status = "PLAYBACK_TEST_FAILED"
                }
            }
            Text(status)
        }
    }
}
