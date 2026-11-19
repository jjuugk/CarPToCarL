package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 单声道档的线格式（1.2.72）。
 *
 * 为什么必须有这个测试：`101 AltAudio / default` 在奥迪上只声明**单声道** PCM
 * （bit14/bit10），发送端若仍按立体声封包（1408B），车机按单声道解就是噪声或静音。
 * 这里端到端验证：一包 704B，且内容是左右声道的平均值。
 */
class AudioSenderMonoTest {
    @Test
    fun `单声道档每包 704B，内容是立体声左右平均值`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { rx ->
            rx.soTimeout = 2_000
            val key = ByteArray(32) { (it + 3).toByte() }
            val sender =
                AudioSender(
                    host = "127.0.0.1",
                    dataPort = rx.localPort,
                    streamKey = key,
                    log = {},
                    sampleRate = 48_000,
                    codec = AudioCodec.PCM,
                    streamType = 101L,
                    channelCount = 1,
                )
            // 一帧立体声：左 = 1000，右 = 3000，交替排列（352 帧 = 1408B）
            val stereo = ByteArray(AudioSender.PACKET_BYTES)
            var i = 0
            while (i + 3 < stereo.size) {
                stereo[i] = 1000.toByte()
                stereo[i + 1] = (1000 shr 8).toByte()
                stereo[i + 2] = 3000.toByte()
                stereo[i + 3] = (3000 shr 8).toByte()
                i += 4
            }
            try {
                assertTrue(sender.startPassthrough())
                sender.onRecorded()
                sender.feedPcm(stereo, stereo.size, 48_000, 2)

                val packet = DatagramPacket(ByteArray(4096), 4096)
                rx.receive(packet)
                // 负载 = 352 帧 × 1ch × 2B = 704B，加 12B RTP 头 + 16B tag + 8B nonce
                assertEquals(12 + 704 + 16 + 8, packet.length)

                val aad = packet.data.copyOfRange(4, 12)
                val cipherAndTag = packet.data.copyOfRange(12, packet.length - 8)
                val nonceTail = packet.data.copyOfRange(packet.length - 8, packet.length)
                val nonce = ByteArray(12).also { System.arraycopy(nonceTail, 0, it, 4, 8) }
                val plain = AirPlayCrypto.chachaOpen(key, nonce, cipherAndTag, aad)
                assertEquals(704, plain.size)
                // 线上是大端 s16：每两个字节一组，值应为 (1000 + 3000) / 2 = 2000
                var o = 0
                while (o + 1 < plain.size) {
                    val sample = ((plain[o].toInt() and 0xFF) shl 8) or (plain[o + 1].toInt() and 0xFF)
                    assertEquals("下混应为左右平均", 2000, sample)
                    o += 2
                }
            } finally {
                sender.stop()
            }
        }
    }
}
