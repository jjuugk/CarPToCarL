package io.github.shaonialife.carptocar.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画面停摆分级干预策略（[StallJudge]）。
 *
 * 断言的是**行为边界**而不是实现：8 秒要关键帧、每 8 秒轻推、30 秒拆会话、阈值翻倍封顶，
 * 以及两条不该动手的情形（还没收到过帧、通话中——后者在调用侧拦，这里只锁「已到 2 级就
 * 不再重复拆」）。
 */
class StallJudgeTest {

    @Test
    fun frameAgeDistinguishesNeverReceivedFromJustReceived() {
        // -1 是「本轮还没收到过帧」的哨兵：引擎刚起来那几秒不能按「距今无穷大」判停摆
        assertEquals(-1L, StallJudge.frameAgeMs(lastFrameAt = 0L, now = 1_000_000L))
        assertEquals(-1L, StallJudge.frameAgeMs(lastFrameAt = -5L, now = 1_000_000L))
        assertEquals(0L, StallJudge.frameAgeMs(lastFrameAt = 1_000_000L, now = 1_000_000L))
        assertEquals(2_400L, StallJudge.frameAgeMs(lastFrameAt = 1_000_000L, now = 1_002_400L))
        // 时钟异常（now 早于 last）不能算出负数，否则会被当成「刚收到」而永不判停摆
        assertEquals(0L, StallJudge.frameAgeMs(lastFrameAt = 2_000L, now = 1_000L))
    }

    @Test
    fun nothingHappensBeforeEightSeconds() {
        for (stalled in longArrayOf(0L, 3_000L, 7_999L)) {
            assertEquals(
                "停摆 ${stalled}ms 不该动手",
                StallJudge.Action.NONE,
                StallJudge.nextAction(
                    stalledMs = stalled,
                    interventions = 0,
                    reconnectDelay = StallJudge.RECONNECT_MILLIS,
                    sinceReinitMs = Long.MAX_VALUE,
                ),
            )
        }
    }

    @Test
    fun firstInterventionAsksForEncoderRebuild() {
        assertEquals(
            StallJudge.Action.REINIT_ENCODER,
            StallJudge.nextAction(
                stalledMs = StallJudge.REINIT_MILLIS,
                interventions = 0,
                reconnectDelay = StallJudge.RECONNECT_MILLIS,
                sinceReinitMs = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun retriesAreThrottledToEveryEightSeconds() {
        // 刚推过（1 秒前）：不重复推
        assertEquals(
            StallJudge.Action.NONE,
            StallJudge.nextAction(
                stalledMs = 12_000L,
                interventions = 1,
                reconnectDelay = StallJudge.RECONNECT_MILLIS,
                sinceReinitMs = 1_000L,
            ),
        )
        // 满 8 秒：再推一次（引擎常要推两三次才吐 IDR）
        assertEquals(
            StallJudge.Action.REINIT_ENCODER,
            StallJudge.nextAction(
                stalledMs = 16_000L,
                interventions = 1,
                reconnectDelay = StallJudge.RECONNECT_MILLIS,
                sinceReinitMs = StallJudge.REINIT_RETRY_MILLIS,
            ),
        )
    }

    @Test
    fun rebuildsSessionAtThresholdNotBefore() {
        assertEquals(
            StallJudge.Action.REINIT_ENCODER,
            StallJudge.nextAction(
                stalledMs = 29_000L,
                interventions = 1,
                reconnectDelay = StallJudge.RECONNECT_MILLIS,
                sinceReinitMs = StallJudge.REINIT_RETRY_MILLIS,
            ),
        )
        assertEquals(
            StallJudge.Action.REBUILD_SESSION,
            StallJudge.nextAction(
                stalledMs = StallJudge.RECONNECT_MILLIS,
                interventions = 1,
                reconnectDelay = StallJudge.RECONNECT_MILLIS,
                sinceReinitMs = StallJudge.REINIT_RETRY_MILLIS,
            ),
        )
    }

    @Test
    fun neverRebuildsTwiceInOneStall() {
        // 已拆过一次：画面还不回来就只等，不再拆——连拆会把引擎永远按在热身期
        assertEquals(
            StallJudge.Action.NONE,
            StallJudge.nextAction(
                stalledMs = 600_000L,
                interventions = 2,
                reconnectDelay = 60_000L,
                sinceReinitMs = 1_000L,
            ),
        )
    }

    @Test
    fun reconnectDelayDoublesAndCaps() {
        assertEquals(60_000L, StallJudge.nextReconnectDelay(30_000L))
        assertEquals(120_000L, StallJudge.nextReconnectDelay(60_000L))
        // 封顶：引擎冷启动要 30~45 秒，阈值再大也没意义，但不能无限涨
        assertEquals(
            StallJudge.RECONNECT_MAX_MILLIS,
            StallJudge.nextReconnectDelay(StallJudge.RECONNECT_MAX_MILLIS),
        )
        assertEquals(
            StallJudge.RECONNECT_MAX_MILLIS,
            StallJudge.nextReconnectDelay(1_000_000L),
        )
    }
}
