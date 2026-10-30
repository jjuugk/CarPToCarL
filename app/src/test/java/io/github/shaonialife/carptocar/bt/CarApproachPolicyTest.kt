package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「靠近车机自动启动」判定测试（2026-10-30）。
 *
 * 真实触发点是系统蓝牙 ACL 广播，时序不可控；这里只锁住「什么情况该拉起 App」这段
 * 纯逻辑——尤其要守住两条：**没指定目标车机时绝不动作**、**手动断开后不许立刻回流**。
 */
class CarApproachPolicyTest {
    private val target = "AA:BB:CC:DD:EE:FF"

    private fun decide(
        address: String? = target,
        targetAddress: String? = target,
        enabled: Boolean = true,
        sessionRunning: Boolean = false,
        suppressed: Boolean = false,
        lastTriggerAt: Long = 0L,
        now: Long = 1_000_000L,
    ) = CarApproachPolicy.onAclConnected(
        eventAddress = address,
        targetAddress = targetAddress,
        enabled = enabled,
        sessionRunning = sessionRunning,
        suppressed = suppressed,
        lastTriggerAt = lastTriggerAt,
        now = now,
    )

    @Test
    fun `目标车机靠近且空闲时触发`() {
        assertEquals(CarApproachPolicy.Decision.TRIGGER, decide())
    }

    @Test
    fun `地址大小写不同也算同一台车机`() {
        assertEquals(
            CarApproachPolicy.Decision.TRIGGER,
            decide(address = "aa:bb:cc:dd:ee:ff", targetAddress = "AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `开关关闭时什么都不做`() {
        assertEquals(CarApproachPolicy.Decision.DISABLED, decide(enabled = false))
    }

    @Test
    fun `未指定目标车机时不触发——避免误连其它已配对设备`() {
        assertEquals(CarApproachPolicy.Decision.NO_TARGET, decide(targetAddress = null))
        assertEquals(CarApproachPolicy.Decision.NO_TARGET, decide(targetAddress = "  "))
    }

    @Test
    fun `别的设备连上来不算车机靠近`() {
        assertEquals(CarApproachPolicy.Decision.NOT_TARGET, decide(address = "11:22:33:44:55:66"))
        assertEquals(CarApproachPolicy.Decision.NOT_TARGET, decide(address = null))
        assertEquals(CarApproachPolicy.Decision.NOT_TARGET, decide(address = ""))
    }

    @Test
    fun `会话已在跑时不重复拉起`() {
        assertEquals(CarApproachPolicy.Decision.SESSION_RUNNING, decide(sessionRunning = true))
    }

    @Test
    fun `手动断开后的抑制期内保持安静`() {
        assertEquals(CarApproachPolicy.Decision.SUPPRESSED, decide(suppressed = true))
    }

    @Test
    fun `抑制优先于节流`() {
        assertEquals(
            CarApproachPolicy.Decision.SUPPRESSED,
            decide(suppressed = true, lastTriggerAt = 999_999L),
        )
    }

    @Test
    fun `同一次靠近只触发一次`() {
        assertEquals(
            CarApproachPolicy.Decision.THROTTLED,
            decide(lastTriggerAt = 1_000_000L - 1L),
        )
        // 刚好到间隔：允许再触发（车机短线重连的场景）
        assertEquals(
            CarApproachPolicy.Decision.TRIGGER,
            decide(lastTriggerAt = 1_000_000L - CarApproachPolicy.RETRIGGER_INTERVAL_MILLIS),
        )
    }

    @Test
    fun `上次触发在未来（系统时间被调整）时按节流处理，不连发`() {
        assertEquals(CarApproachPolicy.Decision.THROTTLED, decide(lastTriggerAt = 1_200_000L))
    }

    @Test
    fun `抑制窗口内保持安静`() {
        assertTrue(
            CarApproachPolicy.suppressionActive(
                suppressed = true,
                suppressedAt = 1_000_000L,
                now = 1_000_000L + 60_000L,
            ),
        )
    }

    @Test
    fun `抑制窗口结束后自动失效——人在车上关掉 App 也能被重新拉起`() {
        // 车机蓝牙一直连着就不会有 ACL_DISCONNECTED，标记没有别的机会被清除；
        // 所以窗口之外必须自然失效，否则用户还在车里却永远等不到自动拉起。
        assertFalse(
            CarApproachPolicy.suppressionActive(
                suppressed = true,
                suppressedAt = 1_000_000L,
                now = 1_000_000L + CarApproachPolicy.SUPPRESS_AFTER_MANUAL_STOP_MILLIS,
            ),
        )
    }

    @Test
    fun `旧版本写的抑制标记（无时刻）仍按有效处理`() {
        assertTrue(CarApproachPolicy.suppressionActive(true, suppressedAt = 0L, now = 2_000_000L))
        assertFalse(CarApproachPolicy.suppressionActive(false, suppressedAt = 0L, now = 2_000_000L))
    }
}
