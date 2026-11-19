package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * 直通音频的两条硬约束：
 *  1. 会话 `RECORD` 之前不得向车机发任何 RTP / 同步包（抢跑的包会被拒或把 jitter buffer 填偏）；
 *  2. `RECORD` 之后注入的 PCM **到齐一帧就发**——没有混音泵，也没有空载补静音，
 *     所以「不注入就没有包」本身就是这一版的设计（1.2.72 删掉了补帧）。
 */
class AudioSenderRecordGateTest {
    @Test
    fun `RECORD 之前线上静默，onRecorded 之后注入 PCM 立即发出加密 RTP`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { dataRx ->
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { ctrlRx ->
                dataRx.soTimeout = 150
                ctrlRx.soTimeout = 150
                val key = ByteArray(32) { (it + 7).toByte() }
                val sender =
                    AudioSender(
                        host = "127.0.0.1",
                        dataPort = dataRx.localPort,
                        streamKey = key,
                        log = {},
                        sampleRate = 48_000,
                        codec = AudioCodec.PCM,
                        streamType = 100L,
                        controlPort = ctrlRx.localPort,
                    )
                // 一段有内容的 PCM（非零，便于验证解密后的字节序与内容）
                val pcm = ByteArray(AudioSender.PACKET_BYTES) { (it * 7 and 0xFF).toByte() }
                try {
                    assertTrue(sender.startPassthrough())

                    // RECORD 之前注入：一帧都不许出去
                    sender.feedPcm(pcm, pcm.size, 48_000, 2)
                    val probe = DatagramPacket(ByteArray(4096), 4096)
                    try {
                        dataRx.receive(probe)
                        fail("RECORD 之前不应发出 RTP 音频包")
                    } catch (_: SocketTimeoutException) {
                    }
                    try {
                        ctrlRx.receive(probe)
                        fail("RECORD 之前不应发出同步包")
                    } catch (_: SocketTimeoutException) {
                    }

                    dataRx.soTimeout = 500
                    ctrlRx.soTimeout = 500
                    sender.onRecorded()
                    sender.onRecorded() // 多条 RECORD 成功路径重复调用：幂等

                    val syncPkt = DatagramPacket(ByteArray(64), 64)
                    ctrlRx.receive(syncPkt)
                    assertEquals(20, syncPkt.length)
                    // 首个同步包携带 X 位（0x90）。
                    assertEquals(0x90, syncPkt.data[0].toInt() and 0xFF)

                    // 注入 5 帧：每帧一到齐就发，序号逐包递增，解密后是同一段 PCM（大端）。
                    repeat(5) { sender.feedPcm(pcm, pcm.size, 48_000, 2) }
                    val expected = AudioSender.bigEndianS16(pcm)
                    var lastSeq = -1
                    repeat(5) {
                        val rtp = DatagramPacket(ByteArray(4096), 4096)
                        dataRx.receive(rtp)
                        val seq = ((rtp.data[2].toInt() and 0xFF) shl 8) or (rtp.data[3].toInt() and 0xFF)
                        if (lastSeq >= 0) assertEquals((lastSeq + 1) and 0xFFFF, seq)
                        lastSeq = seq
                        val aad = rtp.data.copyOfRange(4, 12)
                        val cipherAndTag = rtp.data.copyOfRange(12, rtp.length - 8)
                        val nonceTail = rtp.data.copyOfRange(rtp.length - 8, rtp.length)
                        val nonce = ByteArray(12).also { System.arraycopy(nonceTail, 0, it, 4, 8) }
                        val plain = AirPlayCrypto.chachaOpen(key, nonce, cipherAndTag, aad)
                        assertEquals(AudioSender.PACKET_BYTES, plain.size)
                        assertTrue("解密后应与注入的 PCM 一致（小端 → 大端）", plain.contentEquals(expected))
                    }

                    // 没有新 PCM 就不再有包（补静音已删除）——等 300ms 确认线上安静。
                    val quiet = DatagramPacket(ByteArray(4096), 4096)
                    try {
                        dataRx.receive(quiet)
                        fail("直通模式不补静音帧：没有新 PCM 时不应继续发包")
                    } catch (_: SocketTimeoutException) {
                    }
                } finally {
                    sender.stop()
                }
            }
        }
    }
}
