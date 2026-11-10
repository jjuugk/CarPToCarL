package io.github.shaonialife.carptocar.audio

import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import io.github.shaonialife.carptocar.carplus.CarPlusCameraRecovery
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarPlusAudioTransportTest {
    @Test
    fun closingCameraRecoversMusicOnceAfterLensSwitch() {
        val worker = HandlerThread("test-camera-audio").apply { start() }
        val handler = Handler(worker.looper)
        val recoveries = LinkedBlockingQueue<Unit>()
        var playing = false
        val recovery = CarPlusCameraRecovery(handler, { playing }, { recoveries.add(Unit) })
        val delay = CarPlusCameraRecovery.RELEASE_DELAY_MS
        try {
            handler.post {
                recovery.onCameraAvailable("rear")
                playing = true
                recovery.onCameraUnavailable("rear")
                playing = false
                recovery.onCameraAvailable("rear")
                recovery.onCameraUnavailable("front")
            }
            assertEquals(null, recoveries.poll(delay + 300, TimeUnit.MILLISECONDS))

            handler.post {
                recovery.onCameraAvailable("front")
                recovery.onCameraAvailable("front")
            }
            assertEquals(Unit, recoveries.poll(delay + 1_000, TimeUnit.MILLISECONDS))
            assertEquals(null, recoveries.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            worker.quit()
        }
    }

    @Test
    fun unevenPcmChunksReachReceiverWithContiguousSamples() {
        val address = InetAddress.getLoopbackAddress()
        val key = ByteArray(32) { it.toByte() }
        val frameBytes = AudioSender.PACKET_BYTES
        val pcm = ByteArray(frameBytes * 3) { (it * 13 + it / 256).toByte() }
        DatagramSocket(0, address).use { receiver ->
            receiver.soTimeout = 2_000
            val sender = AudioSender(address.hostAddress!!, receiver.localPort, key, log = {})
            try {
                assertTrue(sender.startPassthrough(keepAlive = false))
                val split = frameBytes * 2 + 64
                for (chunk in listOf(pcm.copyOfRange(0, 128), pcm.copyOfRange(128, split), pcm.copyOfRange(split, pcm.size))) {
                    sender.feedPcm(chunk, chunk.size, srcRate = 48_000)
                }

                repeat(3) { frame ->
                    val packet = DatagramPacket(ByteArray(2_048), 2_048)
                    receiver.receive(packet)
                    val header = ByteBuffer.wrap(packet.data)
                    assertEquals(frame, header.getShort(2).toInt() and 0xffff)
                    assertEquals(frame * AudioSender.SAMPLES_PER_PACKET, header.getInt(4))
                    val payload = AirPlayCrypto.chachaOpen(
                        key,
                        AirPlayCrypto.nonce64(frame.toLong()),
                        packet.data.copyOfRange(12, packet.length - 8),
                        packet.data.copyOfRange(4, 12),
                    )
                    assertArrayEquals(
                        pcm.copyOfRange(frame * frameBytes, (frame + 1) * frameBytes),
                        AudioSender.bigEndianS16(payload),
                    )
                }
            } finally {
                sender.stop()
            }
        }
    }
}
