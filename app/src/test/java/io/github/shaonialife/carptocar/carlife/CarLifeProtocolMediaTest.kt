package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CarLife 媒体信息（0x10035）与播放进度（0x10036）解析的实车回归。
 *
 * 三条钉子都来自 2026-11-16 的用户日志，不是凭空构造：
 *
 * 1. `收到媒体信息（6328B）但解析为空 head=0a 0d ... 12 0c ...` —— 8.8.5 推的是
 *    **裸 CarlifeMediaInfo**，没有 cnt/list 外壳。旧解析只认 0x08/0x12，全部返回空。
 * 2. 封面字段（field 5）之后跟 fixed32 字段，旧解析遇到非 0/2 的 wireType 就放弃整条。
 * 3. `媒体进度 147039233ms` 一类数值 —— 旧解析按裸 u32 大端读，把 protobuf 的
 *    tag 字节 0x08 当成了最高位；按 varint 解回来正好是 21.1s，之后逐拍 +10 秒。
 */
class CarLifeProtocolMediaTest {
    private fun strField(
        field: Int,
        value: String,
    ): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf(((field shl 3) or 2).toByte(), bytes.size.toByte()) + bytes
    }

    private fun varintField(
        field: Int,
        value: Int,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.add((field shl 3).toByte())
        var v = value
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v == 0) {
                out.add(b.toByte())
                break
            }
            out.add((b or 0x80).toByte())
        }
        return out.toByteArray()
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `8_8_5 直接形态解析出歌名歌手专辑和封面`() {
        val art = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        val payload =
            strField(1, "1794748880120") + // 日志里 13 位歌曲 ID
                strField(2, "山花烂漫") +
                strField(3, "苏星婕") +
                strField(4, "山花烂漫") +
                byteArrayOf(0x2A, art.size.toByte()) + art +
                varintField(6, 193) + // 8.8.5 的 duration 是秒
                byteArrayOf(0x55, 0x01, 0x02, 0x03, 0x04) // field10 fixed32：旧实现在这里丢整条

        val list = CarLifeProtocol.parseMediaInfoList(payload)
        assertEquals(1, list.size)
        val info = list[0]
        assertEquals("1794748880120", info.source)
        assertEquals("山花烂漫", info.song)
        assertEquals("苏星婕", info.artist)
        assertEquals("山花烂漫", info.album)
        assertArrayEquals(art, info.albumArt)
        assertEquals(193_000, info.duration)
    }

    @Test
    fun `旧外壳形态仍可解析且不被直接解析误判`() {
        val inner = strField(2, "爱我别走") + strField(3, "张震岳") + varintField(6, 200)
        val payload = byteArrayOf(0x08, 0x01, 0x12, inner.size.toByte()) + inner

        val list = CarLifeProtocol.parseMediaInfoList(payload)
        assertEquals(1, list.size)
        assertEquals("爱我别走", list[0].song)
        assertEquals("张震岳", list[0].artist)
        assertEquals(200_000, list[0].duration)
    }

    @Test
    fun `duration 已经是毫秒时不再乘一千`() {
        // 真实包总有 field1(source) 打头——路径 A 就是靠首字节 0x0a 认出来的。
        val payload = strField(1, "src") + strField(2, "歌") + varintField(6, 193_000)
        assertEquals(193_000, CarLifeProtocol.parseMediaInfoList(payload)[0].duration)
    }

    @Test
    fun `日志里一亿四千万的进度值其实是 varint 误读`() {
        // 旧日志：CARLIFE: 媒体进度 147039233ms（≈40 小时）。
        // 08 c3 a4 01 是 protobuf「field1 varint(21059)」，即 21.1 秒——合理得多。
        assertEquals(21_059, CarLifeProtocol.parseMediaProgress(hex("08c3a401")))
    }

    @Test
    fun `同一轮日志的进度序列按十秒递增`() {
        val heads =
            listOf(
                "08c3a401", "08ebf201", "088dc102", "08b08f03",
                "08f3e403", "0897b304", "08b78105", "08e7d405",
            )
        val values = heads.map { CarLifeProtocol.parseMediaProgress(hex(it))!! }
        values.zipWithNext().forEach { (a, b) -> assert(b > a) { "$b 没有大于 $a" } }
        assertEquals(31083, values[1])
        assertEquals(92775, values.last())
    }

    @Test
    fun `裸 u32 大端的旧形态仍兼容`() {
        assertEquals(1_000, CarLifeProtocol.parseMediaProgress(byteArrayOf(0, 0, 0x03, 0xE8.toByte())))
    }

    @Test
    fun `超过二十四小时的进度判为字段错位`() {
        assertNull(CarLifeProtocol.parseMediaProgress(hex("7fffffff")))
    }
}
