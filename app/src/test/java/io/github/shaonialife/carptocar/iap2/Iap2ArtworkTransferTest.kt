package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面文件传输（iAP2 session 12）数据报编解码回归。
 *
 * 这一层错了不会「看起来有点问题」，而是**整张封面传不过去**：车机按
 * `[id][cmd][payload]` 逐字节解析，命令字错一位就是 FAILURE，分块边界错了
 * 图片就是一坨花屏 —— 而手机侧日志只会看到「车机拒收」，无从下手。
 */
class Iap2ArtworkTransferTest {
    @Test
    fun setupCarriesIdSizeAndFileType() {
        // 用 Int 范围内、四个字节都非零的值：u64 高 4 字节必须是 0（文件大小按 Int 传）
        val datagram = Iap2ArtworkTransfer.setup(transferId = 7, size = 0x01020304)
        assertEquals("SETUP 必须是 12 字节定长", 12, datagram.size)
        assertEquals(7, datagram[0].toInt() and 0xFF)
        assertEquals(Iap2ArtworkTransfer.CMD_SETUP, datagram[1].toInt() and 0xFF)
        // u64 大端：高字节在前
        val size =
            (2 until 10).fold(0L) { acc, index -> (acc shl 8) or (datagram[index].toLong() and 0xFF) }
        assertEquals(0x01020304L, size)
        assertEquals(
            "文件类型固定 0x0002 = Now Playing Artwork（车机据此选落盘槽位）",
            Iap2ArtworkTransfer.FILE_TYPE_NOW_PLAYING_ARTWORK,
            ((datagram[10].toInt() and 0xFF) shl 8) or (datagram[11].toInt() and 0xFF),
        )
    }

    @Test
    fun singleChunkUsesFirstAndOnly() {
        val data = ByteArray(500) { (it % 251).toByte() }
        val chunks = Iap2ArtworkTransfer.chunks(3, data)
        assertEquals(1, chunks.size)
        assertEquals(Iap2ArtworkTransfer.CMD_FIRST_AND_ONLY, chunks[0][1].toInt() and 0xFF)
        assertEquals(3, chunks[0][0].toInt() and 0xFF)
        assertArrayEquals(data, chunks[0].copyOfRange(2, chunks[0].size))
    }

    @Test
    fun multiChunkUsesFirstDataLastAndReassembles() {
        val data = ByteArray(5_000) { (it * 7 % 256).toByte() }
        val chunks = Iap2ArtworkTransfer.chunks(9, data, chunkBytes = 2_000)
        assertEquals("5000 / 2000 → 3 块", 3, chunks.size)
        assertEquals(Iap2ArtworkTransfer.CMD_FIRST_DATA, chunks[0][1].toInt() and 0xFF)
        assertEquals(Iap2ArtworkTransfer.CMD_DATA, chunks[1][1].toInt() and 0xFF)
        assertEquals(Iap2ArtworkTransfer.CMD_LAST_DATA, chunks[2][1].toInt() and 0xFF)
        chunks.forEach { assertEquals(9, it[0].toInt() and 0xFF) }
        // 拼回去必须与原图逐字节一致：块边界丢了半个字节就是花屏
        val rebuilt = ByteArray(data.size)
        var offset = 0
        chunks.forEach { chunk ->
            val body = chunk.copyOfRange(2, chunk.size)
            body.copyInto(rebuilt, offset)
            offset += body.size
        }
        assertEquals(data.size, offset)
        assertArrayEquals(data, rebuilt)
    }

    /** 恰好整除时，最后一块仍然必须是 LAST_DATA（不能多出一个空块）。 */
    @Test
    fun exactMultipleEndsWithLastData() {
        val data = ByteArray(4_000) { it.toByte() }
        val chunks = Iap2ArtworkTransfer.chunks(1, data, chunkBytes = 2_000)
        assertEquals(2, chunks.size)
        assertEquals(Iap2ArtworkTransfer.CMD_FIRST_DATA, chunks[0][1].toInt() and 0xFF)
        assertEquals(Iap2ArtworkTransfer.CMD_LAST_DATA, chunks[1][1].toInt() and 0xFF)
    }

    @Test
    fun emptyDataProducesNoChunks() {
        assertTrue("空数据不该产生 SETUP 之后的分块（车机会当成 0 字节文件）", Iap2ArtworkTransfer.chunks(1, ByteArray(0)).isEmpty())
    }

    @Test
    fun parseResponseReadsIdAndCommand() {
        val start = Iap2ArtworkTransfer.parseResponse(byteArrayOf(5, Iap2ArtworkTransfer.CMD_START.toByte()))
        assertEquals(5, start?.transferId)
        assertTrue(start!!.isStart)

        val success = Iap2ArtworkTransfer.parseResponse(byteArrayOf(5, Iap2ArtworkTransfer.CMD_SUCCESS.toByte()))
        assertTrue(success!!.isSuccess)

        val failure = Iap2ArtworkTransfer.parseResponse(byteArrayOf(5, Iap2ArtworkTransfer.CMD_FAILURE.toByte()))
        assertTrue(failure!!.isFailure)
    }

    @Test
    fun parseResponseRejectsShortOrZeroId() {
        assertNull("不足 2 字节", Iap2ArtworkTransfer.parseResponse(byteArrayOf(5)))
        assertNull("id=0 是保留值，出现即说明字节流错位", Iap2ArtworkTransfer.parseResponse(byteArrayOf(0, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferIdZeroIsRejected() {
        Iap2ArtworkTransfer.setup(0, 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferIdBeyondByteIsRejected() {
        Iap2ArtworkTransfer.setup(256, 100)
    }
}
