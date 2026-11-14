package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Iap2LinkPacket.splitFrames]：把一个输出缓冲切成「一帧一次传输」。
 *
 * 背景：接收端按「一个传输单元 = 一个 iAP2 包」解析 comm 隧道包，合并多帧会让
 * QNX 系车机在第二个帧上错位、投屏中途随机卡死（2026-11-05 手机侧分析报告 §9）。
 * 切分必须是**保守**的：切不准就原样返回，绝不能丢字节。
 */
class Iap2LinkFrameSplitTest {
    private fun frame(
        control: Int,
        seq: Int,
        ack: Int,
        sessionId: Int = Iap2LinkPacket.SESSION_CONTROL,
        payload: ByteArray = byteArrayOf(0x40, 0x40, 0x00, 0x08),
    ): ByteArray =
        Iap2LinkPacket.encode(
            Iap2LinkPacket.LinkPacket(
                control = control,
                seq = seq,
                ack = ack,
                sessionId = sessionId,
                payload = payload,
            ),
        )

    @Test
    fun singleFrameStaysSingle() {
        val one = frame(Iap2LinkPacket.CTRL_ACK, 1, 2)
        val parts = Iap2LinkPacket.splitFrames(one)
        assertEquals(1, parts.size)
        assertArrayEquals(one, parts[0])
    }

    @Test
    fun twoFramesAreSplitOnBoundary() {
        val a = frame(Iap2LinkPacket.CTRL_ACK, 1, 0)
        val b = frame(Iap2LinkPacket.CTRL_ACK, 2, 0, payload = byteArrayOf(0x40, 0x40, 0x00, 0x09, 0x01))
        val joined = a + b
        val parts = Iap2LinkPacket.splitFrames(joined)
        assertEquals(2, parts.size)
        assertArrayEquals(a, parts[0])
        assertArrayEquals(b, parts[1])
    }

    @Test
    fun headerOnlyAcknowledgementDoesNotMergeFollowingControlMessages() {
        val expected =
            listOf(
                "ff5a00094036e30045",
                "ff5a00104037e3013c404000061d025b",
                "ff5a00104038e3013b40400006aa00d0",
            ).map { hex -> hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
        val parts = Iap2LinkPacket.splitFrames(expected[0] + expected[1] + expected[2])
        assertEquals(3, parts.size)
        expected.indices.forEach { assertArrayEquals(expected[it], parts[it]) }
    }

    /** 协商期 MARKER（`FF 55 02 00 EE 10`）与数据帧连着时，标记必须单独成段。 */
    @Test
    fun syncMarkerIsItsOwnFrame() {
        val marker = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
        val data = frame(Iap2LinkPacket.CTRL_ACK, 7, 0)
        val parts = Iap2LinkPacket.splitFrames(marker + data)
        assertEquals(2, parts.size)
        assertArrayEquals(marker, parts[0])
        assertArrayEquals(data, parts[1])
    }

    /** 三帧连发（SYN 标记 + 两帧）——链路 bring-up 最常见的一次 output 形态。 */
    @Test
    fun markerPlusTwoFrames() {
        val marker = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
        val a = frame(Iap2LinkPacket.CTRL_ACK, 3, 1)
        val b = frame(Iap2LinkPacket.CTRL_ACK, 4, 1)
        val parts = Iap2LinkPacket.splitFrames(marker + a + b)
        assertEquals(3, parts.size)
        assertArrayEquals(marker, parts[0])
        assertArrayEquals(a, parts[1])
        assertArrayEquals(b, parts[2])
    }

    /** 首字节不是已知包头：整段原样返回（旧行为），不是切成空。 */
    @Test
    fun unknownHeadFallsBackToWholeBuffer() {
        val junk = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte())
        val parts = Iap2LinkPacket.splitFrames(junk)
        assertEquals(1, parts.size)
        assertArrayEquals(junk, parts[0])
    }

    /** 前导杂字节（对端/自己历史上可能混入）同样整段返回，交给上层按字节流处理。 */
    @Test
    fun leadingGarbageFallsBackToWholeBuffer() {
        val data = frame(Iap2LinkPacket.CTRL_ACK, 9, 0)
        val mixed = byteArrayOf(0x00, 0x01) + data
        val parts = Iap2LinkPacket.splitFrames(mixed)
        assertEquals(1, parts.size)
        assertArrayEquals(mixed, parts[0])
    }

    /** 末尾残帧（写到一半被打断）：不能只发前半段，整段退回旧路径。 */
    @Test
    fun truncatedTailFallsBackToWholeBuffer() {
        val a = frame(Iap2LinkPacket.CTRL_ACK, 1, 0)
        val b = frame(Iap2LinkPacket.CTRL_ACK, 2, 0)
        val truncated = a + b.copyOfRange(0, b.size - 3)
        val parts = Iap2LinkPacket.splitFrames(truncated)
        assertEquals(1, parts.size)
        assertArrayEquals(truncated, parts[0])
    }

    /** 声明长度小于最小帧（脏长度）时不能切成 0 长度段，直接退回旧路径。 */
    @Test
    fun absurdDeclaredLengthFallsBack() {
        val bad = byteArrayOf(0xFF.toByte(), 0x5A, 0x00, 0x02, 0x40, 0x01, 0x00, 0x00, 0x00)
        val parts = Iap2LinkPacket.splitFrames(bad)
        assertEquals(1, parts.size)
        assertArrayEquals(bad, parts[0])
    }

    /** 空输入：调用方本来就会提前 return，但函数自身不能抛。 */
    @Test
    fun emptyInputReturnsSingleEmptyChunk() {
        val parts = Iap2LinkPacket.splitFrames(ByteArray(0))
        assertEquals(1, parts.size)
        assertTrue(parts[0].isEmpty())
    }
}
