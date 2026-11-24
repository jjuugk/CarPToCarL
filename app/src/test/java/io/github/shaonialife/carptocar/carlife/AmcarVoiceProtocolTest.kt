package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AmcarVoiceProtocolTest {
    @Test
    fun microphoneFrameHasBigEndianHeader() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val frame = AmcarVoiceProtocol.microphone(pcm, 0x11223344)
        val head = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        assertEquals(4, head.int)
        assertEquals(0x11223344, head.int)
        assertEquals(AmcarVoiceProtocol.MIC_DATA, head.int)
        assertArrayEquals(pcm, frame.copyOfRange(12, frame.size))
        assertEquals(16, frame.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyPcmIsRejected() {
        AmcarVoiceProtocol.microphone(ByteArray(0), 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oddLengthPcmIsRejected() {
        AmcarVoiceProtocol.microphone(ByteArray(3), 0)
    }

    @Test
    fun recognitionIdsMatchCarWith() {
        assertEquals(0x10024, AmcarVoiceProtocol.MIC_RECOGNITION)
        assertEquals(0x10023, AmcarVoiceProtocol.MIC_END)
        assertEquals(0x58001, AmcarVoiceProtocol.MIC_DATA)
    }
}
