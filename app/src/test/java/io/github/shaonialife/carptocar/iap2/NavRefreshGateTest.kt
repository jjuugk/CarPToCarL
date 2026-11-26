package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航卡片刷新节流闸门：只测「现在能不能发」这一件事。
 *
 * 时钟由调用方传进来，所以这里全是纯数字，不碰 Looper / SystemClock。
 */
class NavRefreshGateTest {
    @Test
    fun `不限时任何时候都能发`() {
        val gate = NavRefreshGate()
        gate.intervalMillis = NavCardRefreshOption.UNLIMITED
        gate.markEmitted(1_000L)
        assertTrue(gate.shouldEmitNow(1_000L))
        assertTrue(gate.shouldEmitNow(1_001L))
        assertEquals(0L, gate.dueDelayMs(1_001L))
    }

    @Test
    fun `未发过时直通_首帧不该等窗口`() {
        val gate = NavRefreshGate()
        gate.intervalMillis = 3_000L
        assertTrue(gate.shouldEmitNow(50_000L))
        assertEquals(0L, gate.dueDelayMs(50_000L))
    }

    @Test
    fun `三秒窗口内挡住_到点放行`() {
        val gate = NavRefreshGate()
        gate.intervalMillis = 3_000L
        gate.markEmitted(10_000L)
        assertFalse(gate.shouldEmitNow(10_999L))
        assertFalse(gate.shouldEmitNow(12_999L))
        assertTrue(gate.shouldEmitNow(13_000L))
        assertEquals(1_000L, gate.dueDelayMs(12_000L))
    }

    @Test
    fun `清除帧强制直通_不看窗口`() {
        val gate = NavRefreshGate()
        gate.intervalMillis = 15_000L
        gate.markEmitted(10_000L)
        assertFalse(gate.shouldEmitNow(10_100L))
        assertTrue(gate.shouldEmitNow(10_100L, forced = true))
    }

    @Test
    fun `reset 之后重新走首帧直通`() {
        val gate = NavRefreshGate()
        gate.intervalMillis = 5_000L
        gate.markEmitted(10_000L)
        assertFalse(gate.shouldEmitNow(11_000L))
        gate.reset()
        assertTrue(gate.shouldEmitNow(11_000L))
    }

    @Test
    fun `把间隔改短后按新窗口立即放行`() {
        val gate = NavRefreshGate()
        gate.intervalMillis = 15_000L
        gate.markEmitted(10_000L)
        assertFalse(gate.shouldEmitNow(13_000L))
        gate.intervalMillis = 1_000L
        assertTrue(gate.shouldEmitNow(13_000L))
    }
}
