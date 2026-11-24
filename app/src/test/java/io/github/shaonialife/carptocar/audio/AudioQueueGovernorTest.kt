package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列治理状态机（默认关）的行为契约。
 *
 * 时钟用可变 Long 注入——不依赖 Android SystemClock，也不需要真的 sleep。
 */
class AudioQueueGovernorTest {
    private class Clock {
        var nowMs = 10_000L
        val tick: () -> Long = { nowMs }
    }

    private fun audible(
        size: Int = 64,
        amplitude: Int = 100,
    ): ByteArray = ByteArray(size) { if (it % 2 == 0) amplitude.toByte() else 0 }

    private fun silence(size: Int = 64): ByteArray = ByteArray(size)

    @Test
    fun `默认关闭时容量恒为 1 帧且欠载与静音全部旁路`() {
        val clock = Clock()
        val governor = AudioQueueGovernor(isMedia = true, clock = clock.tick)
        assertFalse(governor.enabled)
        assertEquals(1, governor.capacity())
        assertEquals(AudioQueueGovernor.State.STEADY, governor.state())
        assertFalse(governor.noteUnderflow())
        // 关着时即便扫描静音也永远报有声（不触发停流）
        assertEquals(AudioQueueGovernor.Silence.AUDIBLE, governor.observe(silence(), 64))
    }

    @Test
    fun `媒体开启后稳态 12 帧 欠载升到 25 帧并保持 30 秒后回落`() {
        val clock = Clock()
        val governor = AudioQueueGovernor(isMedia = true, clock = clock.tick)
        governor.enabled = true
        governor.onStreamStarted() // 起流点 = 10000ms

        assertEquals(12, governor.capacity())

        // 起流 1s 内的欠载反馈属于正常空窗，不采纳
        clock.nowMs += 999
        assertFalse(governor.noteUnderflow())
        assertEquals(12, governor.capacity())

        // 1s 之后采纳，升到 25 帧
        clock.nowMs += 2
        assertTrue(governor.noteUnderflow())
        assertEquals(AudioQueueGovernor.State.UNDERFLOW_COOLDOWN, governor.state())
        assertEquals(25, governor.capacity())

        // 冷却期内重复反馈只续期
        clock.nowMs += 20_000
        assertTrue(governor.noteUnderflow())
        assertEquals(25, governor.capacity())

        // 距最后一次反馈满 30s，回落
        clock.nowMs += 30_000
        assertEquals(AudioQueueGovernor.State.STEADY, governor.state())
        assertEquals(12, governor.capacity())
    }

    @Test
    fun `导航流开启后固定 50 帧且不做欠载加深`() {
        val clock = Clock()
        val governor = AudioQueueGovernor(isMedia = false, clock = clock.tick)
        governor.enabled = true
        governor.onStreamStarted()
        clock.nowMs += 5_000
        assertEquals(50, governor.capacity())
        assertFalse(governor.noteUnderflow())
        assertEquals(50, governor.capacity())
    }

    @Test
    fun `媒体连续静音 3 秒才停流 有声立即恢复`() {
        val clock = Clock()
        val governor = AudioQueueGovernor(isMedia = true, clock = clock.tick)
        governor.enabled = true
        governor.onStreamStarted()

        assertEquals(AudioQueueGovernor.Silence.SILENT, governor.observe(silence(), 64))
        clock.nowMs += 2_999
        assertEquals(AudioQueueGovernor.Silence.SILENT, governor.observe(silence(), 64))
        clock.nowMs += 2
        assertEquals(AudioQueueGovernor.Silence.STOP, governor.observe(silence(), 64))

        // 恢复有声：静音计时清零，下一帧重新从 SILENT 起算
        assertEquals(AudioQueueGovernor.Silence.AUDIBLE, governor.observe(audible(), 64))
        assertEquals(AudioQueueGovernor.Silence.SILENT, governor.observe(silence(), 64))
    }

    @Test
    fun `导航流即便连续静音也不停流`() {
        val clock = Clock()
        val governor = AudioQueueGovernor(isMedia = false, clock = clock.tick)
        governor.enabled = true
        governor.onStreamStarted()
        clock.nowMs += 10_000
        assertEquals(AudioQueueGovernor.Silence.AUDIBLE, governor.observe(silence(), 64))
    }

    @Test
    fun `空包与截断包不构成新的静音观测`() {
        val clock = Clock()
        val governor = AudioQueueGovernor(isMedia = true, clock = clock.tick)
        governor.enabled = true
        governor.onStreamStarted()
        assertEquals(AudioQueueGovernor.Silence.SILENT, governor.observe(ByteArray(4), 0))
        assertEquals(AudioQueueGovernor.Silence.SILENT, governor.observe(ByteArray(4), 4))
    }

    @Test
    fun `关闭开关会清空欠载与静音状态`() {
        val clock = Clock()
        val governor = AudioQueueGovernor(isMedia = true, clock = clock.tick)
        governor.enabled = true
        governor.onStreamStarted()
        clock.nowMs += 5_000
        assertTrue(governor.noteUnderflow())
        assertEquals(25, governor.capacity())
        governor.enabled = false
        assertEquals(1, governor.capacity())
    }
}
