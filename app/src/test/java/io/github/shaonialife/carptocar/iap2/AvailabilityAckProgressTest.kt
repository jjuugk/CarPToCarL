package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `0x4300` 是否被车机读走的判据（2026-11-11 别克 E2LB 一轮空等 5 分钟的直接原因）。
 */
class AvailabilityAckProgressTest {
    @Test
    fun ackPointingAtLastReceivedCountsAsCaughtUp() {
        assertTrue(AvailabilityAckProgress.caughtUp(acknowledged = 46, lastSent = 46))
    }

    @Test
    fun ackPointingAtNextExpectedAlsoCountsAsCaughtUp() {
        // 有的车机 ack 语义是「期望的下一个序号」：确认了 46 就回 47
        assertTrue(AvailabilityAckProgress.caughtUp(acknowledged = 47, lastSent = 46))
    }

    @Test
    fun ackOneFrameBehindMeansNotRead() {
        // 3767 现场：最后一帧是 50，车机 ack 停在 46 → 后三档根本没被读走
        assertFalse(AvailabilityAckProgress.caughtUp(acknowledged = 46, lastSent = 50))
    }

    @Test
    fun neverAcknowledgedMeansNotCaughtUp() {
        assertFalse(AvailabilityAckProgress.caughtUp(acknowledged = null, lastSent = 12))
    }

    @Test
    fun wrappingSequenceStillUnderstood() {
        // 序号在 255 后回到 0：「期望的下一个」跨环同样是追上
        assertTrue(AvailabilityAckProgress.caughtUp(acknowledged = 0, lastSent = 255))
        // 反过来是落后一帧，不能当成追上
        assertFalse(AvailabilityAckProgress.caughtUp(acknowledged = 255, lastSent = 0))
        assertFalse(AvailabilityAckProgress.caughtUp(acknowledged = 255, lastSent = 1))
    }
}
