package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音频编码通路的**回归测试**。
 *
 * 背景（2026-10-20 Honda HFT 实测三次都是「有包无声」）：旧实现对所有负载无条件做
 * 大端 s16 换序，连 AAC 比特流也按 2 字节对调。AAC 帧被换序后，车机解码器直接判坏帧
 * ——于是包在流、`/feedback` 的 `sampleTime` 一路推进、喇叭全静音。
 * 这条测试锁死「AAC 帧必须原样上线，只有 PCM 才换序」。
 */
class AudioAacPayloadTest {
    private val key = ByteArray(32) { (it * 11 + 3).toByte() }

    private fun sender(codec: AudioCodec) =
        AudioSender(
            host = "127.0.0.1",
            dataPort = 5000,
            streamKey = key,
            log = {},
            codec = codec,
            streamType =
                when (codec) {
                    AudioCodec.AAC_LC -> 102L
                    AudioCodec.OPUS -> 101L
                    AudioCodec.PCM -> 100L
                },
        )

    @Test
    fun aacFrameGoesOnTheWireByteForByte() {
        // 真实 MediaCodec 首帧的头部（裸 AAC：id_syn_ele=CPE，tag=1）
        val frame = ByteArray(557) { index -> ((index * 37 + 0x21) and 0xFF).toByte() }
        val wire = sender(AudioCodec.AAC_LC).sealPacket(frame, 0, 0, 0)
        val decrypted =
            AirPlayCrypto.chachaOpen(
                key,
                ByteArray(12).also { wire.copyOfRange(wire.size - 8, wire.size).copyInto(it, 4) },
                wire.copyOfRange(12, wire.size - 8),
                wire.copyOfRange(4, 12),
            )
        assertArrayEquals(frame, decrypted)
    }

    @Test
    fun pcmIsStillByteSwapped() {
        val pcm = byteArrayOf(0x34, 0x12, 0x78, 0x56)
        val wire = sender(AudioCodec.PCM).sealPacket(pcm, 0, 0, 0)
        val decrypted =
            AirPlayCrypto.chachaOpen(
                key,
                ByteArray(12).also { wire.copyOfRange(wire.size - 8, wire.size).copyInto(it, 4) },
                wire.copyOfRange(12, wire.size - 8),
                wire.copyOfRange(4, 12),
            )
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), decrypted)
    }

    @Test
    fun firstPacketCarriesMarkerBit() {
        val frame = ByteArray(16)
        val first = sender(AudioCodec.AAC_LC).sealPacket(frame, 0, 0, 0, marker = true)
        val second = sender(AudioCodec.AAC_LC).sealPacket(frame, 1, 1024, 1, marker = false)
        // 字节 1：M(0x80) | PT(102)
        assertEquals(0x80 or 102, first[1].toInt() and 0xFF)
        assertEquals(102, second[1].toInt() and 0xFF)
    }

    @Test
    fun opusFrameGoesOnTheWireByteForByteWithAltAudioPayloadType() {
        val frame = ByteArray(160) { index -> ((index * 29 + 0x78) and 0xFF).toByte() }
        val wire = sender(AudioCodec.OPUS).sealPacket(frame, 1, 960, 0, marker = true)
        assertEquals(0x80 or 101, wire[1].toInt() and 0xFF)
        val decrypted =
            AirPlayCrypto.chachaOpen(
                key,
                ByteArray(12).also { wire.copyOfRange(wire.size - 8, wire.size).copyInto(it, 4) },
                wire.copyOfRange(12, wire.size - 8),
                wire.copyOfRange(4, 12),
            )
        assertArrayEquals(frame, decrypted)
    }
}

/** 同步包（RTCP 0xD4）线格式测试：20 字节、无 SSRC、首包置扩展位。 */
class AudioSyncPacketTest {
    private val ntp = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @Test
    fun layoutMatchesSpecification() {
        val packet = AudioSyncPacket.build(0x0102, 44100L, 45124L, ntp, first = false)
        assertEquals(AudioSyncPacket.SIZE, packet.size)
        assertEquals(0x80, packet[0].toInt() and 0xFF) // V=2，非首包无 X 位
        assertEquals(0xD4, packet[1].toInt() and 0xFF) // M=1 + PT=84
        assertEquals(0x01, packet[2].toInt() and 0xFF) // 序号 BE
        assertEquals(0x02, packet[3].toInt() and 0xFF)
        assertEquals(0x00, packet[4].toInt() and 0xFF) // 当前时间戳 BE = 44100
        assertEquals(0x00, packet[5].toInt() and 0xFF)
        assertEquals(0xAC, packet[6].toInt() and 0xFF)
        assertEquals(0x44, packet[7].toInt() and 0xFF)
        assertArrayEquals(ntp, packet.copyOfRange(8, 16)) // NTP 原样 8 字节
        assertEquals(0x00, packet[16].toInt() and 0xFF) // 下一包时间戳 BE = 45124
        assertEquals(0x00, packet[17].toInt() and 0xFF)
        assertEquals(0xB0, packet[18].toInt() and 0xFF)
        assertEquals(0x44, packet[19].toInt() and 0xFF)
    }

    @Test
    fun firstSyncSetsExtensionBit() {
        val packet = AudioSyncPacket.build(0, 0, 1024, ntp, first = true)
        assertEquals(0x90, packet[0].toInt() and 0xFF)
    }

    @Test
    fun rejectsWrongNtpLength() {
        var failed = false
        try {
            AudioSyncPacket.build(0, 0, 0, ByteArray(4), first = false)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        org.junit.Assert.assertTrue("NTP 必须是 8 字节", failed)
    }
}
