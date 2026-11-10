package io.github.shaonialife.carptocar.audio

import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.media.MediaSessionsState
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarPlusMediaKeyTest {
    @Test
    fun mutePausesAndTheNextPressResumesTheSamePlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = MediaSession(context, "carplus-media-key-test")
        val transitions = LinkedBlockingQueue<Int>()
        val previous = MediaSessionsState.activeController
        fun state(value: Int, speed: Float) =
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(value, 12_000, speed)
                .build()
        try {
            session.setPlaybackState(state(PlaybackState.STATE_PLAYING, 1f))
            session.setCallback(object : MediaSession.Callback() {
                override fun onPause() {
                    session.setPlaybackState(state(PlaybackState.STATE_PAUSED, 0f))
                    transitions.add(PlaybackState.STATE_PAUSED)
                }

                override fun onPlay() {
                    session.setPlaybackState(state(PlaybackState.STATE_PLAYING, 1f))
                    transitions.add(PlaybackState.STATE_PLAYING)
                }
            }, Handler(Looper.getMainLooper()))
            MediaSessionsState.activeController = session.controller

            SessionController.dispatchMediaKey(KeyEvent.KEYCODE_VOLUME_MUTE)
            assertEquals(PlaybackState.STATE_PAUSED, transitions.poll(3, TimeUnit.SECONDS))
            SessionController.dispatchMediaKey(KeyEvent.KEYCODE_VOLUME_MUTE)
            assertEquals(PlaybackState.STATE_PLAYING, transitions.poll(3, TimeUnit.SECONDS))
        } finally {
            MediaSessionsState.activeController = previous
            session.release()
        }
    }
}
