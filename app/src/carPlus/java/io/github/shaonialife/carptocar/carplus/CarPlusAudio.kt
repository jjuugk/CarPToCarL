package io.github.shaonialife.carptocar.carplus

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.media.MediaSessionsState
import io.github.shaonialife.carptocar.util.Bg
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

/** Replaces the two SDK playback calls in the packaged runtime DEX. */
@Keep
internal object CarPlusAudioTap {
    private val target = AtomicReference<Messenger?>()
    private val silence = ByteArray(32 * 1024)
    private val forwardedFocus = WeakHashMap<AudioFocusRequest, Boolean>()
    private val stream = ThreadLocal.withInitial {
        val thread = Thread.currentThread()
        val tag = runCatching {
            thread.javaClass.getDeclaredField("mTag").apply { isAccessible = true }.get(thread)
        }.getOrNull()
        when (tag) {
            "AudioPlayer@STREAM_CAST_MUSIC.Play" -> 0
            "AudioPlayer@STREAM_TTS.Play", "AudioPlayer@STREAM_SYSTEM.Play",
            "AudioPlayer@STREAM_NOTIFICATION.Play" -> 1
            else -> -1
        }
    }

    fun setTarget(messenger: Messenger?) = target.set(messenger)

    @JvmStatic
    fun requestAudioFocus(manager: AudioManager, request: AudioFocusRequest): Int {
        // The local SDK track is silent during forwarding. Let the source app keep audio focus.
        val usage = request.audioAttributes.usage
        if (target.get() != null && (usage == AudioAttributes.USAGE_MEDIA ||
                usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE ||
                usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION ||
                usage == AudioAttributes.USAGE_NOTIFICATION)
        ) {
            synchronized(forwardedFocus) { forwardedFocus[request] = true }
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        synchronized(forwardedFocus) { forwardedFocus.remove(request) }
        return manager.requestAudioFocus(request)
    }

    @JvmStatic
    fun abandonAudioFocusRequest(manager: AudioManager, request: AudioFocusRequest): Int {
        val forwarded = synchronized(forwardedFocus) { forwardedFocus.remove(request) == true }
        return if (forwarded) AudioManager.AUDIOFOCUS_REQUEST_GRANTED else manager.abandonAudioFocusRequest(request)
    }

    @JvmStatic
    fun write(track: AudioTrack, pcm: ByteArray, offset: Int, size: Int, mode: Int): Int {
        val messenger = target.get()
        val source = if (messenger != null) stream.get() ?: -1 else -1
        if (messenger == null || size !in 1..silence.size || offset < 0 || offset > pcm.size - size ||
            track.audioFormat != AudioFormat.ENCODING_PCM_16BIT || track.channelCount !in 1..2 || source < 0
        ) {
            return track.write(pcm, offset, size, mode)
        }
        // Keep the SDK playback clock without playing the same sound twice.
        val written = track.write(silence, 0, size, mode)
        if (written <= 0) return written
        val message = Message.obtain(null, source, track.sampleRate, track.channelCount).apply {
            data = Bundle().apply { putByteArray("pcm", pcm.copyOfRange(offset, offset + written)) }
        }
        try {
            messenger.send(message)
        } catch (_: RemoteException) {
            target.compareAndSet(messenger, null)
        }
        return written
    }
}

/** Combines the SDK's independently paced music and guidance into one CarPlay media stream. */
internal class CarPlusAudio(context: Context?) {
    private val client = SessionController.currentClient
    private val worker = HandlerThread("iccoa-audio", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val music = PcmQueue()
    private val guidance = PcmQueue()
    private val output = ByteArray(SAMPLES_PER_TICK * 2)
    private var nextTick = SystemClock.uptimeMillis()
    private val handler = Handler(worker.looper) { message ->
        val pcm = message.data.getByteArray("pcm") ?: return@Handler false
        val rate = message.arg1
        val channels = message.arg2
        if (rate !in 8_000..192_000 || channels !in 1..2) return@Handler false
        val isGuidance = message.what == 1
        val queue = if (isGuidance) guidance else music
        queue.add(pcm, rate, channels, if (isGuidance) "guidance" else "music")
        true
    }
    val endpoint = Messenger(handler)
    private val cameraManager = context?.getSystemService(CameraManager::class.java)
    private val cameraRecovery = CarPlusCameraRecovery(
        handler,
        hasMusic = { MediaSessionsState.nowPlaying?.playing == true || music.hasRecentAudio(SystemClock.uptimeMillis()) },
        recover = {
            val session = client
            val sender = SessionController.audioSender
            if (session != null && sender != null && SessionController.currentClient === session) {
                val hasPcm = music.hasRecentAudio(SystemClock.uptimeMillis())
                Bg.run("camera-audio-recovery") {
                    SessionController.recoverCarPlusAudioAfterCamera(session, sender, hasPcm)
                }
            }
        },
    )

    init {
        scheduleTick()
        runCatching { cameraManager?.registerAvailabilityCallback(cameraRecovery, handler) }
            .onFailure { SessionController.appendLog("AUDIO: 相机状态监听启动失败 - ${it.message}") }
    }

    fun close() {
        runCatching { cameraManager?.unregisterAvailabilityCallback(cameraRecovery) }
        worker.quit()
    }

    private fun scheduleTick() {
        handler.postAtTime(::mix, nextTick)
    }

    private fun mix() {
        val now = SystemClock.uptimeMillis()
        val playMusic = music.ready(now)
        val playGuidance = guidance.ready(now)
        for (index in 0 until SAMPLES_PER_TICK) {
            val media = if (playMusic) music.take() else 0
            val speech = if (playGuidance) guidance.take() else 0
            val sample = ((if (playGuidance) media / 3 else media) + speech).coerceIn(-32768, 32767)
            output[index * 2] = sample.toByte()
            output[index * 2 + 1] = (sample shr 8).toByte()
        }
        if (SessionController.currentClient === client) {
            SessionController.audioSender?.feedPcm(output, output.size, SAMPLE_RATE, 2)
        }
        nextTick = maxOf(nextTick + TICK_MS, SystemClock.uptimeMillis() - MAX_CATCH_UP_MS)
        scheduleTick()
    }

    private class PcmQueue {
        private val samples = ShortArray(SAMPLE_RATE / 4 * 2)
        private var head = 0
        private var lastAudibleAt = 0L
        private var size = 0
        private var playing = false
        private var readyAt = 0L

        fun hasRecentAudio(now: Long): Boolean = lastAudibleAt > 0 && now - lastAudibleAt <= 2_000

        fun add(pcm: ByteArray, rate: Int, channels: Int, source: String) {
            if (size == 0 && !playing) readyAt = SystemClock.uptimeMillis() + BUFFER_MS
            val frames = pcm.size / (channels * 2)
            val resampledFrames = frames * SAMPLE_RATE / rate
            var audible = false
            for (frame in 0 until resampledFrames) {
                val offset = frame * rate / SAMPLE_RATE * channels * 2
                val left = sampleAt(pcm, offset)
                val right = if (channels == 2) sampleAt(pcm, offset + 2) else left
                if (size == samples.size) {
                    head = (head + 2) % samples.size
                    size -= 2
                }
                val tail = (head + size) % samples.size
                samples[tail] = left
                samples[tail + 1] = right
                size += 2
                audible = audible || left != 0.toShort() || right != 0.toShort()
            }
            if (audible) {
                val now = SystemClock.uptimeMillis()
                if (now - lastAudibleAt > 1_000) {
                    Log.i("CarPlusAudio", "ICCOA $source PCM ${rate}Hz ${channels}ch")
                }
                lastAudibleAt = now
            }
        }

        fun ready(now: Long): Boolean {
            if (size == 0) playing = false
            else if (size >= BUFFER_SAMPLES || now >= readyAt) playing = true
            return playing
        }

        fun take(): Int {
            if (size == 0) return 0
            val sample = samples[head].toInt()
            head = (head + 1) % samples.size
            size--
            return sample
        }

        private fun sampleAt(pcm: ByteArray, offset: Int): Short =
            ((pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)).toShort()
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val TICK_MS = 10L
        const val SAMPLES_PER_TICK = SAMPLE_RATE / 100 * 2
        const val BUFFER_MS = 90L
        const val BUFFER_SAMPLES = SAMPLE_RATE * 2 * BUFFER_MS / 1_000
        const val MAX_CATCH_UP_MS = 20L
    }
}

/** Debounces the camera being released, including lens changes during the same camera session. */
internal class CarPlusCameraRecovery(
    private val handler: Handler,
    private val hasMusic: () -> Boolean,
    private val recover: () -> Unit,
) : CameraManager.AvailabilityCallback() {
    private val inUse = mutableSetOf<String>()
    private var hadMusic = false
    private val afterRelease = Runnable {
        val shouldRecover = hadMusic || hasMusic()
        hadMusic = false
        if (shouldRecover) recover()
    }

    override fun onCameraUnavailable(cameraId: String) {
        if (inUse.add(cameraId)) {
            handler.removeCallbacks(afterRelease)
            hadMusic = hadMusic || hasMusic()
        }
    }

    override fun onCameraAvailable(cameraId: String) {
        if (inUse.remove(cameraId) && inUse.isEmpty()) {
            handler.postDelayed(afterRelease, RELEASE_DELAY_MS)
        }
    }

    companion object {
        const val RELEASE_DELAY_MS = 2_000L
    }
}
