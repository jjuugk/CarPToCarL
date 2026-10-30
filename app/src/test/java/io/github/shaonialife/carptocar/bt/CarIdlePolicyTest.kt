package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「车机远离 30 秒后该不该收摊」的判定测试（2026-10-30）。
 *
 * 要守住的三条底线：
 * 1. 车机又连上了 → **绝不**退出进程（否则会把正常重连打断）；
 * 2. 蓝牙断了但画面还在推（镜像走 WiFi）→ 也不退，用户正在用；
 * 3. 用户正开着界面看 → 还是不退（人还在用，掐掉等于闪退）。
 */
class CarIdlePolicyTest {
    private fun verdict(
        aclConnected: Boolean = false,
        streaming: Boolean = false,
        uiVisible: Boolean = false,
    ) = CarIdlePolicy.onIdleTimeout(
        aclConnected = aclConnected,
        streaming = streaming,
        uiVisible = uiVisible,
    )

    @Test
    fun `链路断开且无画面无界面时收摊`() {
        assertEquals(CarIdlePolicy.Verdict.CLOSE, verdict())
    }

    @Test
    fun `30 秒内车机回连则保留会话`() {
        assertEquals(CarIdlePolicy.Verdict.STILL_CONNECTED, verdict(aclConnected = true))
    }

    @Test
    fun `画面仍在推送时不退出进程`() {
        assertEquals(CarIdlePolicy.Verdict.STREAMING, verdict(streaming = true))
    }

    @Test
    fun `用户正看着界面时不退进程`() {
        assertEquals(CarIdlePolicy.Verdict.UI_VISIBLE, verdict(uiVisible = true))
    }

    @Test
    fun `链路优先于推流与界面判定`() {
        // 回连后画面多半也在重建：只要链路在，结论就不该是「已断开」，
        // 否则会误判为可以收摊。
        assertEquals(
            CarIdlePolicy.Verdict.STILL_CONNECTED,
            verdict(aclConnected = true, streaming = true, uiVisible = true),
        )
    }

    @Test
    fun `推流优先于界面可见`() {
        // 画面在送、用户又开着界面：结论落在「别杀」这一侧，具体哪种都不重要。
        assertEquals(
            CarIdlePolicy.Verdict.STREAMING,
            verdict(streaming = true, uiVisible = true),
        )
    }

    @Test
    fun `计时窗口是 30 秒`() {
        assertEquals(30_000L, CarIdlePolicy.IDLE_TIMEOUT_MILLIS)
    }
}
