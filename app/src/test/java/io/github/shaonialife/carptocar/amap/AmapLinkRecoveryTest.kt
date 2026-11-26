package io.github.shaonialife.carptocar.amap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 高德链接失败重试与 STALE 重建的判定：纯函数，不碰 Android Binder。 */
class AmapLinkRecoveryTest {
    @Test
    fun `失败退避 3 6 12 然后每分钟`() {
        assertEquals(3_000L, amapRetryDelayMs(1))
        assertEquals(6_000L, amapRetryDelayMs(2))
        assertEquals(12_000L, amapRetryDelayMs(3))
        assertEquals(60_000L, amapRetryDelayMs(4))
        assertEquals(60_000L, amapRetryDelayMs(99))
        assertEquals(3_000L, amapRetryDelayMs(0))
    }

    @Test
    fun `真在引导时静默清帧触发重建`() {
        assertTrue(
            shouldRestartAfterStale(
                wasGuiding = true,
                emission = AmapNaviEmission.Clear,
                registered = true,
                frameCount = 42,
                staleRestarts = 0,
            ),
        )
    }

    @Test
    fun `正常导航结束不触发重建`() {
        // onStop 已把 guiding 置 false，tick 之前的 wasGuiding=false。
        assertFalse(
            shouldRestartAfterStale(
                wasGuiding = false,
                emission = AmapNaviEmission.Clear,
                registered = true,
                frameCount = 42,
                staleRestarts = 0,
            ),
        )
    }

    @Test
    fun `没有引导帧或非 Clear 不触发重建`() {
        assertFalse(
            shouldRestartAfterStale(true, null, true, 42, 0),
        )
        assertFalse(
            shouldRestartAfterStale(
                wasGuiding = true,
                emission =
                    AmapNaviEmission.Guide(
                        io.github.shaonialife.carptocar.carlife.CarLifeNaviNextTurn(),
                    ),
                registered = true,
                frameCount = 42,
                staleRestarts = 0,
            ),
        )
    }

    @Test
    fun `未注册 没发过帧 或已重建两次都不再触发`() {
        assertFalse(shouldRestartAfterStale(true, AmapNaviEmission.Clear, false, 42, 0))
        assertFalse(shouldRestartAfterStale(true, AmapNaviEmission.Clear, true, 0, 0))
        assertFalse(
            shouldRestartAfterStale(true, AmapNaviEmission.Clear, true, 42, AMAP_MAX_STALE_RESTARTS),
        )
    }
}
