package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `0x4300` 发出后「等车机把它读走」的宽限长度（2026-11-14 别克放宽）。
 *
 * 锁住的是两条容易互相踩线的约束：标准车机必须还是 45 秒（奥迪/宝马/台架的快速换档
 * 就是靠它），通用系必须是 90 秒（对齐 link_plus 的单轮会话预算）。
 */
class AvailabilityRetryBudgetTest {
    @Test
    fun standardStackKeepsFortyFiveSeconds() {
        // 三档各一个 8 秒窗口（24s）+ 每档 2 次重传的 RTO 余量 → 45s。
        // 奥迪 MMI 9310 的 ACK 一直正常、0x4301 晚到 2 分 35 秒，本来就碰不到这条判据；
        // 把它也拉长只会每轮白等 45 秒，是回归。
        assertEquals(45_000_000_000L, AvailabilityRetryBudget.fastRetryNanos(cinemoStack = false))
    }

    @Test
    fun cinemoStackGetsNinetySeconds() {
        // link_plus 对别克只有一条 SESSION_TIMEOUT_MILLIS = 90_000 的总预算，且 0x4301
        // 首窗口等不到也不拆链。别克在 0x4300 之后「零业务帧」是常态（BUICK_3138 / 3767）。
        assertEquals(90_000_000_000L, AvailabilityRetryBudget.fastRetryNanos(cinemoStack = true))
    }

    @Test
    fun cinemoBudgetLeavesRoomForTheLadderAndASlowWakeUp() {
        // 90 秒扣掉「首档 8s + 换档 2×8s」之后，还得剩至少 45 秒纯等待——
        // 否则放宽到 90 秒也只是把换档跑完就拆，等于没放宽。
        val ladderNanos = 3 * 8_000_000_000L
        assertTrue(
            "通用系宽限扣掉报文体阶梯后不足 45 秒余量",
            AvailabilityRetryBudget.CINEMO_FAST_RETRY_NANOS - ladderNanos >= 45_000_000_000L,
        )
    }
}
