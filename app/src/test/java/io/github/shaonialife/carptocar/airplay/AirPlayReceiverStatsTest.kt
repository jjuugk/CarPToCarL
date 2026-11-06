package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/feedback` 的接收端收帧统计（`statsDictionary`）。
 *
 * 现场来源：奥迪 4580/CL3D 的 libairplay 在 SETUP 声明 `statsCollectionEnabled=true`
 * 时会回 `statsDictionary`，键是流号、值带 `stty`/`rfps`/`ltnc`/`ahea`。
 * 2026-11-06 那轮黑屏就是靠它才能从手机侧回答「车机到底收没收到帧」。
 */
class AirPlayReceiverStatsTest {
    private fun feedbackWith(stats: Map<*, *>): Map<*, *> =
        mapOf(
            "streams" to emptyList<Any>(),
            "statsDictionary" to stats,
        )

    @Test
    fun `解析屏幕流的实收帧率`() {
        val root =
            feedbackWith(
                mapOf(
                    "1" to mapOf("stty" to 110, "rfps" to 19.8, "ltnc" to 0, "ahea" to 1),
                    "2" to mapOf("stty" to 102, "sr" to 44100),
                ),
            )
        assertTrue(AirPlayReceiverStats.present(root))
        val stats = AirPlayReceiverStats.screenStats(root)
        assertEquals(1, stats.size)
        assertEquals("1", stats[0].streamId)
        assertEquals(110, stats[0].streamType)
        assertEquals(19.8, stats[0].receiveFps!!, 0.001)
        assertEquals(0, stats[0].latency)
        assertEquals(1, stats[0].ahead)
    }

    @Test
    fun `副屏类型 111 也算屏幕`() {
        val root = feedbackWith(mapOf("7" to mapOf("stty" to 111, "rfps" to 5.0)))
        assertEquals(1, AirPlayReceiverStats.screenStats(root).size)
        assertTrue(AirPlayReceiverStats.isScreenType(111))
        assertFalse(AirPlayReceiverStats.isScreenType(102))
    }

    @Test
    fun `stty 缺失时退回 type 键`() {
        val root = feedbackWith(mapOf("3" to mapOf("type" to 110, "rfps" to 12.0)))
        assertEquals(110, AirPlayReceiverStats.screenStats(root).single().streamType)
    }

    @Test
    fun `没有 statsDictionary 时返回空表并如实报告不存在`() {
        val root = mapOf("streams" to emptyList<Any>())
        assertFalse(AirPlayReceiverStats.present(root))
        assertTrue(AirPlayReceiverStats.screenStats(root).isEmpty())
        assertTrue(AirPlayReceiverStats.streamTypes(root).isEmpty())
    }

    @Test
    fun `类型字段缺失或不是字典的条目被忽略`() {
        val root =
            feedbackWith(
                mapOf(
                    "1" to mapOf("rfps" to 20.0), // 没有 stty/type，无法判定是流
                    "2" to "garbage", // 不是字典
                    "3" to mapOf("stty" to 110, "rfps" to 3.0),
                ),
            )
        assertEquals("3", AirPlayReceiverStats.screenStats(root).single().streamId)
    }

    @Test
    fun `有流但没报 rfps 时不臆造数值`() {
        val root = feedbackWith(mapOf("1" to mapOf("stty" to 110)))
        assertNull(AirPlayReceiverStats.screenStats(root).single().receiveFps)
    }

    @Test
    fun `文案带流号类型与帧率且不含本地化小数点`() {
        val stat = ScreenReceiveStat("1", 110, 19.8, 0, 1)
        val line = AirPlayReceiverStats.describe(stat)
        assertTrue(line.contains("stream=1"))
        assertTrue(line.contains("type=110"))
        assertTrue(line.contains("rfps=19.8"))
        assertTrue(line.contains("ltnc=0"))
        assertTrue(line.contains("ahea=1"))
        assertEquals("未知", AirPlayReceiverStats.describeFps(null))
        assertEquals("20.0 fps", AirPlayReceiverStats.describeFps(20.0))
    }

    @Test
    fun `实有流类型可被列出用于诊断`() {
        val root =
            feedbackWith(
                mapOf(
                    "1" to mapOf("stty" to 102),
                    "2" to mapOf("stty" to 110),
                ),
            )
        assertEquals(listOf(102, 110), AirPlayReceiverStats.streamTypes(root).sorted())
    }
}
