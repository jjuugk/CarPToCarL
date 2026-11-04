package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarLifeHuClient.probeCandidates] 的顺序约定。
 *
 * 这个序列是「一轮会话内自动撞档位表」的驱动：
 * - **当前值必须打头**，否则日志里只有后续失败档，看不出基线原本给了什么尺寸；
 * - 其余 1..8 一个不少、一个不重，否则某些档位永远试不到（或同一档试两遍白等 6 秒）。
 */
class ConnectTypeProbeTest {

    @Test
    fun `当前档打头，其余按 1 到 8 补齐`() {
        assertArrayEqualsInt(intArrayOf(5, 1, 2, 3, 4, 6, 7, 8), CarLifeHuClient.probeCandidates(5))
        assertArrayEqualsInt(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8), CarLifeHuClient.probeCandidates(1))
        assertArrayEqualsInt(intArrayOf(8, 1, 2, 3, 4, 5, 6, 7), CarLifeHuClient.probeCandidates(8))
    }

    @Test
    fun `越界值被钳到合法档位`() {
        assertArrayEqualsInt(intArrayOf(8, 1, 2, 3, 4, 5, 6, 7), CarLifeHuClient.probeCandidates(99))
        assertArrayEqualsInt(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8), CarLifeHuClient.probeCandidates(0))
        assertArrayEqualsInt(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8), CarLifeHuClient.probeCandidates(-3))
    }

    @Test
    fun `永远覆盖 1 到 8 且不重复`() {
        for (start in -2..10) {
            val list = CarLifeHuClient.probeCandidates(start).toList()
            assertEquals("起点 $start 的序列长度", 8, list.size)
            assertEquals("起点 $start 的序列内容", (1..8).toList(), list.sorted())
            assertTrue("起点 $start 应打头", list.first() in 1..8)
        }
    }

    private fun assertArrayEqualsInt(expected: IntArray, actual: IntArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
