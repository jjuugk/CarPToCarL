package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AmcarCarMicTest {
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val connectionId = 4242L

    private fun inputKey(): ByteArray =
        AirPlayCrypto.hkdfSha512(
            secret,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            "DataStream-Input-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )

    private fun outputKey(): ByteArray =
        AirPlayCrypto.hkdfSha512(
            secret,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )

    private fun packet(
        pcmBigEndian: ByteArray,
        counter: Long = 1L,
    ): ByteArray {
        val header =
            byteArrayOf(
                0x80.toByte(),
                100,
                0,
                1,
                0,
                0,
                0,
                7,
                0,
                0,
                0,
                9,
            )
        val aad = header.copyOfRange(4, 12)
        val nonce = AirPlayCrypto.nonce64(counter)
        val cipher = AirPlayCrypto.chachaSeal(inputKey(), nonce, pcmBigEndian, aad)
        val tail = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
        return header + cipher + tail
    }

    @Test
    fun selectsOnlyPcm24SpeechRecognitionBit() {
        val info =
            mapOf(
                "audioFormats" to
                    listOf(
                        mapOf("type" to 100, "audioType" to "SpeechRecognition", "audioInputFormats" to 0x20000040L),
                        mapOf("type" to 100, "audioType" to "default", "audioInputFormats" to 0x30000050L),
                    ),
            )
        val format = AmcarMicStreamFormat.select(info)
        assertNotNull(format)
        assertEquals(AmcarMicStreamFormat.PCM24, format)
        assertEquals(24_000, format!!.rate)
        val description = format.description(connectionId, 55_000, 55_001)
        assertEquals(true, description["input"])
        assertEquals(480L, description["framesPerPacket"])
        assertEquals(false, description["supportsRTPPacketRedundancy"])
    }

    @Test
    fun opusOnlyInfoOpensNothing() {
        val info =
            mapOf(
                "audioFormats" to
                    listOf(mapOf("type" to 100, "audioType" to "speechRecognition", "audioInputFormats" to 0x20000000L)),
            )
        assertNull(AmcarMicStreamFormat.select(info))
    }

    @Test
    fun decryptsInputKeyAndSwapsEndianness() {
        // 大端 PCM16 两个样本：0x0102 / 0x0304 → 小端 02 01 04 03
        val bigEndian = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val plain = AmcarCarMicReceiver.decodeForTest(inputKey(), outputKey(), packet(bigEndian))
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x04, 0x03), plain)
    }

    @Test
    fun outputKeyIsAcceptedAsTolerantFallback() {
        val bigEndian = byteArrayOf(0x05, 0x06, 0x07, 0x08)
        val header = byteArrayOf(0x80.toByte(), 100, 0, 1, 0, 0, 0, 7, 0, 0, 0, 9)
        val nonce = AirPlayCrypto.nonce64(2L)
        val cipher =
            AirPlayCrypto.chachaSeal(
                outputKey(),
                nonce,
                bigEndian,
                header.copyOfRange(4, 12),
            )
        val tail = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(2L).array()
        val plain = AmcarCarMicReceiver.decodeForTest(inputKey(), outputKey(), header + cipher + tail)
        assertArrayEquals(byteArrayOf(0x06, 0x05, 0x08, 0x07), plain)
    }

    @Test
    fun resample480FramesTo320() {
        val pcm24 = ByteArray(480 * 2) { if (it % 2 == 0) 0x34 else 0x12 }
        val pcm16 = AmcarCarMicReceiver.resample24To16(pcm24)
        assertEquals(320 * 2, pcm16.size)
    }
}
