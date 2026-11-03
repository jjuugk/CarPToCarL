package io.github.shaonialife.carptocar.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 送帧闸门：帧龄基线、抖动 vs 结构性堆积、重同步冷却。
 *
 * 时间全部显式传入（不依赖真实时钟），这样「持续 2 秒」「冷却 10 秒」这类判据
 * 才能被精确验证——用 `Thread.sleep` 写这类测试只会既慢又不稳。
 */
class VideoDeliveryGateTest {
    /** 跑满 20 帧探测期：时间戳每帧 +50ms，`now` 同步前进，基线建立后排队延迟恒为 0。 */
    private fun VideoDeliveryGate.warmUp(
        startNow: Long = 10_000L,
        startTs: Long = 5_000L,
    ): Long {
        var now = startNow
        for (index in 0 until 20) {
            val verdict = onFrame(now, startTs + index * 50L, writeMs = 5L, keyFrame = index == 0)
            assertFalse("探测期不该触发重同步", verdict.resync)
            now += 50L
        }
        return now
    }

    @Test
    fun `正常流不触发重同步且排队延迟为 0`() {
        val gate = VideoDeliveryGate()
        var now = 10_000L
        var ts = 5_000L
        repeat(120) {
            val verdict = gate.onFrame(now, ts, writeMs = 3L, keyFrame = false)
            assertFalse(verdict.resync)
            now += 50L
            ts += 50L
        }
        val summary = gate.summaryIfDue(now)
        assertNotNull("窗口已过 5 秒，应当产出统计", summary)
        assertEquals(0L, summary!!.backlogMaxMs)
        assertEquals(0, summary.slowWrites)
        assertTrue(summary.timestampUsable)
    }

    @Test
    fun `时间戳基准未知（epoch 级）也能算排队延迟`() {
        val gate = VideoDeliveryGate()
        // 时间戳是 currentTimeMillis 量级，now 是 uptimeMillis：两者差 1.7e12，
        // 但 offset 恒定，基线会把它整体消掉。
        var now = 10_000L
        var ts = 1_700_000_000_000L
        repeat(30) {
            val verdict = gate.onFrame(now, ts, writeMs = 3L, keyFrame = false)
            assertFalse(verdict.resync)
            now += 50L
            ts += 50L
        }
        val summary = gate.summaryIfDue(now + 4_000L)!!
        assertEquals(0L, summary.backlogMaxMs)
        assertTrue(summary.timestampUsable)
    }

    @Test
    fun `时间戳恒定不动时停用而不是误判堆积`() {
        val gate = VideoDeliveryGate()
        var now = 10_000L
        repeat(80) {
            // 该字段恒为 0：若拿它算延迟，offset 会随 now 一路涨，伪装成「越积越多」。
            val verdict = gate.onFrame(now, 0L, writeMs = 3L, keyFrame = false)
            assertFalse("字段不可信时绝不能动画面", verdict.resync)
            now += 50L
        }
        val summary = gate.summaryIfDue(now + 2_000L)!!
        assertFalse(summary.timestampUsable)
        assertTrue(summary.describe().contains("未测"))
    }

    @Test
    fun `时间戳只有极少取值时停用`() {
        val gate = VideoDeliveryGate()
        var now = 10_000L
        repeat(40) {
            // 只有两个取值，来回跳——不是时间戳。
            val verdict = gate.onFrame(now, 100L + (it % 2) * 10L, writeMs = 3L, keyFrame = false)
            assertFalse(verdict.resync)
            now += 50L
        }
        assertFalse(gate.summaryIfDue(now + 4_000L)!!.timestampUsable)
    }

    @Test
    fun `结构性堆积持续两秒后重同步一次`() {
        val gate = VideoDeliveryGate()
        var now = gate.warmUp()
        var ts = 5_000L + 20 * 50L
        var resyncs = 0
        // 之后编码器仍每 50ms 出一帧，但送出间隔被拖到 100ms：每帧多积 50ms。
        repeat(40) {
            val verdict = gate.onFrame(now, ts, writeMs = 20L, keyFrame = false)
            if (verdict.resync) {
                resyncs++
                assertNotNull(verdict.reason)
            }
            now += 100L
            ts += 50L
        }
        assertEquals("40 帧足够堆到 1500ms 并持续 2 秒，应当只触发一次", 1, resyncs)
    }

    @Test
    fun `重同步后冷却期内不再重同步`() {
        val gate = VideoDeliveryGate()
        var now = gate.warmUp()
        var ts = 5_000L + 20 * 50L
        var resyncs = 0
        repeat(60) {
            if (gate.onFrame(now, ts, writeMs = 20L, keyFrame = false).resync) resyncs++
            now += 100L
            ts += 50L
        }
        assertEquals(1, resyncs)
    }

    @Test
    fun `关键帧不触发重同步`() {
        val gate = VideoDeliveryGate()
        var now = gate.warmUp()
        var ts = 5_000L + 20 * 50L
        repeat(60) {
            // 重同步的目的就是等关键帧——关键帧自己不该被丢。
            assertFalse(gate.onFrame(now, ts, writeMs = 20L, keyFrame = true).resync)
            now += 100L
            ts += 50L
        }
    }

    @Test
    fun `短暂抖动不触发重同步`() {
        val gate = VideoDeliveryGate()
        var now = gate.warmUp()
        var ts = 5_000L + 20 * 50L
        // 送出节奏被拖慢，延迟爬到 1000ms 后停住——低于 1500ms 硬阈值。
        repeat(20) {
            assertFalse(gate.onFrame(now, ts, writeMs = 30L, keyFrame = false).resync)
            now += 100L
            ts += 50L
        }
        // 之后恢复正常节奏：延迟保持在恒定水平，不再增长。
        repeat(40) {
            assertFalse(gate.onFrame(now, ts, writeMs = 5L, keyFrame = false).resync)
            now += 50L
            ts += 50L
        }
    }

    @Test
    fun `慢写与卡顿分别计数`() {
        val gate = VideoDeliveryGate()
        var now = 10_000L
        var ts = 5_000L
        repeat(20) {
            gate.onFrame(now, ts, writeMs = 5L, keyFrame = false)
            now += 50L
            ts += 50L
        }
        // 100ms：算慢写（>=80）但不算卡顿（<400）
        gate.onFrame(now, ts, writeMs = 100L, keyFrame = false)
        now += 50L
        ts += 50L
        // 500ms：慢写 + 卡顿
        gate.onFrame(now, ts, writeMs = 500L, keyFrame = false)
        now += 50L
        val summary = gate.summaryIfDue(now + 6_000L)
        assertNotNull(summary)
        assertEquals("两次慢写", 2, summary!!.slowWrites)
        assertEquals("其中一次是卡顿", 1, summary.stalls)
        assertEquals(500L, summary.writeMaxMs)
    }

    @Test
    fun `reset 后基线重建`() {
        val gate = VideoDeliveryGate()
        var now = gate.warmUp()
        // 累积一段延迟，确认基线已被拉高
        var ts = 5_000L + 20 * 50L
        repeat(10) {
            gate.onFrame(now, ts, writeMs = 10L, keyFrame = false)
            now += 100L
            ts += 50L
        }
        gate.reset()
        // reset 清空了窗口，这一帧之前取不到统计
        assertNull(gate.summaryIfDue(now))
        // reset 后第一帧：基线重建，排队延迟必须重新从 0 起算，
        // 而不是沿用上一轮的旧基线（否则新会话一上来就被判「已落后 500ms」）。
        assertFalse(gate.onFrame(now, ts, writeMs = 5L, keyFrame = true).resync)
        val fresh = gate.summaryIfDue(now + 5_000L)
        assertNotNull(fresh)
        assertEquals(0L, fresh!!.backlogMaxMs)
    }
}
