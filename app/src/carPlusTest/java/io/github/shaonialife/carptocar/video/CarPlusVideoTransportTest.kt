package io.github.shaonialife.carptocar.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import io.github.shaonialife.carptocar.carplus.CarPlusVideo
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarPlusVideoTransportTest {
    @Test
    fun decodedSampleReachesCarPlaySocketAsH264() {
        val address = InetAddress.getByName("127.0.0.1")
        val key = ByteArray(32) { it.toByte() }
        ServerSocket(0, 1, address).use { receiver ->
            receiver.soTimeout = 5_000
            val sender = VideoSender(
                host = address.hostAddress!!,
                dataPort = receiver.localPort,
                frameCipher = StreamFrameCipher.ChaCha(key),
                width = CarPlusVideo.WIDTH,
                height = CarPlusVideo.HEIGHT,
                targetFps = CarPlusVideo.FPS,
                limitSurfaceFps = true,
                log = { Log.i("CarPlusTransportTest", it) },
                canvasSize = { 1412 to 654 },
            )
            try {
                lateinit var encoderInput: Surface
                assertTrue(sender.start { encoderInput = it })
                receiver.accept().use { socket ->
                    socket.soTimeout = 5_000
                    renderSample(encoderInput)
                    val input = DataInputStream(socket.getInputStream())
                    var receivedConfig = false
                    var frameCount = 0L
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (frameCount < 3 && SystemClock.elapsedRealtime() < deadline) {
                        val header = ByteArray(128).also(input::readFully)
                        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        val size = fields.getInt(0)
                        assertTrue("Invalid screen packet size: $size", size in 0..1_000_000)
                        val body = ByteArray(size).also(input::readFully)
                        when (fields.get(4).toInt()) {
                            1 -> {
                                assertEquals(1, body[0].toInt())
                                assertEquals(1412f, fields.getFloat(16), 0f)
                                assertEquals(654f, fields.getFloat(20), 0f)
                                receivedConfig = true
                            }
                            0 -> {
                                assertTrue("Frame arrived before VideoConfig", receivedConfig)
                                val nal = ByteBuffer.wrap(
                                    AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(frameCount), body, header),
                                )
                                val length = nal.int
                                assertTrue("Invalid H.264 NAL length: $length", length in 1..nal.remaining())
                                frameCount++
                            }
                        }
                    }
                    assertEquals(3L, frameCount)
                    assertTrue(sender.isVideoFlowing)
                }
            } finally {
                sender.stop()
            }
        }
    }

    private fun renderSample(surface: Surface) {
        val extractor = MediaExtractor()
        try {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            assets.openFd("carplus-sample.mp4").use { sample ->
                extractor.setDataSource(sample.fileDescriptor, sample.startOffset, sample.length)
            }
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                decoder.configure(format, surface, null, 0)
                decoder.start()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = checkNotNull(decoder.getInputBuffer(index))
                            val size = extractor.readSampleData(buffer, 0)
                            inputEnded = size < 0
                            val flags = if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            decoder.queueInputBuffer(index, 0, size.coerceAtLeast(0), extractor.sampleTime.coerceAtLeast(0), flags)
                            if (!inputEnded) extractor.advance()
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        decoder.releaseOutputBuffer(index, info.size > 0)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
                error("Sample decoder did not finish")
            } finally {
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }
}
