package io.github.shaonialife.carptocar.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冻结判定测试（2026-10-30）。
 *
 * 要守住的三条底线：
 * 1. **正常心跳不能被误判成冻结**——否则每 3 秒刷一行 PROC，日志又回到看不清的状态；
 * 2. 主线程偶尔跑重活（1~2 秒迟到）也不能报，真正的冻结是「几十秒到几分钟」；
 * 3. 冻结时长要算对（相邻 tick 间隔减去一个正常周期），因为恢复动作按它分级。
 */
class FreezeWatchTest {
    @Test
    fun `正常秒级心跳不判定为冻结`() {
        val watch = FreezeWatch()
        var now = 10_000L
        watch.tick(now) // 起算
        repeat(10) {
            now += 1_000L
            assertEquals(0L, watch.tick(now))
        }
    }

    @Test
    fun `心跳抖动到接近 4 秒仍不报`() {
        val watch = FreezeWatch()
        watch.tick(0L)
        // 间隔 3.9s → 迟到 2.9s，未达 3s 阈值
        assertEquals(0L, watch.tick(3_900L))
    }

    @Test
    fun `真实冻结按间隔减去一个周期算`() {
        val watch = FreezeWatch()
        watch.tick(0L)
        // 22:14:45.726 冻到 22:16:59.875 = 134.1 秒
        val frozen = watch.tick(134_100L)
        assertEquals(133_100L, frozen)
        assertEquals(134_100L, watch.stallMillis(frozen))
    }

    @Test
    fun `十秒以上冻结才值得重建会话`() {
        val watch = FreezeWatch()
        watch.tick(0L)
        assertTrue(watch.tick(8_000L) < FreezeWatch.RECOVER_MIN_FROZEN_MS)
        watch.reset()
        watch.tick(0L)
        assertTrue(watch.tick(30_000L) >= FreezeWatch.RECOVER_MIN_FROZEN_MS)
    }

    @Test
    fun `时钟回退不报冻结并重新起算`() {
        val watch = FreezeWatch()
        watch.tick(50_000L)
        assertEquals(0L, watch.tick(10_000L))
        // 重新起算后仍是正常心跳
        assertEquals(0L, watch.tick(11_000L))
    }

    @Test
    fun `reset 后第一次 tick 只起算`() {
        val watch = FreezeWatch()
        watch.tick(0L)
        watch.reset()
        assertEquals(0L, watch.tick(500_000L))
    }
}
