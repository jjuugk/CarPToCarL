package io.github.shaonialife.carptocar.video

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.carptocar.MainActivity
import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import io.github.shaonialife.carptocar.carplus.CarPlusReceiver
import io.github.shaonialife.carptocar.carplus.CarPlusVideo
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in hardware test for a rooted ColorOS phone with a previously paired local receiver. */
@RunWith(AndroidJUnit4::class)
class CarPlusStandaloneReconnectTest {
    @Test
    fun savedPairStreamsToCarPlayEncoderWithoutHostRelay() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("colorosHardware") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val local = InetAddress.getByName("127.0.0.1")
        val key = ByteArray(32) { it.toByte() }
        val launchFailed = AtomicBoolean()
        ServerSocket(0, 1, local).use { sink ->
            sink.soTimeout = 5_000
            val sender = VideoSender(
                host = local.hostAddress!!,
                dataPort = sink.localPort,
                frameCipher = StreamFrameCipher.ChaCha(key),
                width = CarPlusVideo.WIDTH,
                height = CarPlusVideo.HEIGHT,
                targetFps = CarPlusVideo.FPS,
                log = { Log.i(TAG, it) },
                canvasSize = { CarPlusVideo.WIDTH to CarPlusVideo.HEIGHT },
                onSurfaceStopped = { CarPlusReceiver.detach(it) },
            )
            try {
                assertTrue(sender.start { CarPlusReceiver.attach(it) })
                sink.accept().use { socket ->
                    val broadcastDeadline = SystemClock.elapsedRealtime() + 15_000
                    while (!CarPlusReceiver.status.contains("配对码") &&
                        SystemClock.elapsedRealtime() < broadcastDeadline
                    ) {
                        Thread.sleep(100)
                    }
                    instrumentation.runOnMainSync {
                        CarPlusReceiver.openSystemCarPlus(activity) { launchFailed.set(true) }
                    }
                    val firstFrameDeadline = SystemClock.elapsedRealtime() + 45_000
                    socket.soTimeout = 35_000
                    val input = DataInputStream(socket.getInputStream())
                    val header = ByteArray(128)
                    var frames = 0L
                    var firstFrame = 0L
                    var lastFrame = 0L
                    var configReceived = false
                    while (firstFrame == 0L || lastFrame - firstFrame < 75_000) {
                        assertTrue("Timed out waiting for standalone video", firstFrame != 0L ||
                            SystemClock.elapsedRealtime() < firstFrameDeadline)
                        input.readFully(header)
                        val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                        assertTrue("Invalid CarPlay payload size", size in 0..2_000_000)
                        val body = ByteArray(size).also(input::readFully)
                        when (header[4].toInt()) {
                            1 -> configReceived = body.isNotEmpty() && body[0] == 1.toByte()
                            0 -> {
                                assertTrue("Missing AVC config", configReceived)
                                val decoded = AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(frames), body, header)
                                val nal = ByteBuffer.wrap(decoded)
                                assertTrue("Invalid H.264 NAL size", nal.int in 1..nal.remaining())
                                frames++
                                lastFrame = SystemClock.elapsedRealtime()
                                if (firstFrame == 0L) {
                                    firstFrame = lastFrame
                                    socket.soTimeout = 8_000
                                    Log.i(TAG, "First real ICCOA video frame reached the CarPlay data socket")
                                }
                                if (frames % 50 == 0L) {
                                    Log.i(TAG, "Verified $frames frames across ${lastFrame - firstFrame} ms")
                                }
                            }
                        }
                    }
                    assertTrue("Could not open the system Car+ page", !launchFailed.get())
                    assertTrue("Insufficient video frames", frames >= 75)
                    Log.i(TAG, "Standalone reconnect passed with $frames frames across ${lastFrame - firstFrame} ms")
                }
            } finally {
                sender.stop()
            }
        }
    }

    companion object {
        private const val TAG = "CarPlusStandaloneTest"
    }
}
