package io.github.shaonialife.carptocar.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 闸门的行为约定：一轮只允许一个、被拦下的要说得出「谁在跑、跑了多久」、
 * 结束必须带原因、强行接手要留痕。
 *
 * 这些不是洁癖：日志里「已有会话在处理中」出现四五次而没有任何上下文，
 * 是 2026-11-11 那晚最费时的谜题之一。
 */
class SessionAttemptGateTest {
    private val lines = mutableListOf<String>()
    private var clock = 1_000L
    private val gate =
        SessionAttemptGate(
            log = { line -> lines += line },
            now = { clock },
        )

    @Test
    fun secondAttemptIsRefusedAndCounted() {
        val first = gate.begin("主动连车机")
        assertNotNull(first)
        clock += 7_000
        val second = gate.begin("车机 ctrl 邀请触发")
        assertNull(second)
        assertEquals(1, gate.blockedSinceStart)
        assertTrue(lines.last().contains("主动连车机"))
        assertTrue(lines.last().contains("7s"))
    }

    @Test
    fun endLogsElapsedAndFreesTheGate() {
        gate.begin("主动连车机")
        clock += 3_000
        assertTrue(gate.end("配对失败"))
        assertFalse(gate.isActive)
        assertTrue(lines.last().contains("3s"))
        assertTrue(lines.last().contains("配对失败"))
        // 结束后可以再开一轮
        assertNotNull(gate.begin("建链失败自动重试 #1"))
    }

    @Test
    fun endWithoutActiveAttemptIsNoOp() {
        assertFalse(gate.end("空转"))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun takeoverRecordsTheReplacedAttempt() {
        gate.begin("主动连车机")
        clock += 12_000
        val fresh = gate.takeover("解冻后自动恢复 #1", "上一轮已不可能推进")
        assertEquals(fresh, gate.attempt)
        assertTrue(lines.any { it.contains("强行接手") && it.contains("12s") })
    }

    @Test
    fun describeTellsWhoIsRunningAndWhyItWasBlocked() {
        gate.begin("主动连车机")
        clock += 5_000
        gate.begin("车机 ctrl 邀请触发")
        val line = gate.describe()
        assertTrue(line.contains("#1"))
        assertTrue(line.contains("主动连车机"))
        assertTrue(line.contains("累计拦下 1 次"))
        assertTrue(line.contains("车机 ctrl 邀请触发"))
    }

    @Test
    fun awaitIdleReturnsImmediatelyWhenFree() {
        assertTrue(gate.awaitIdle(1_000))
    }

    @Test
    fun awaitIdleTimesOutWhileBusy() {
        gate.begin("主动连车机")
        assertFalse(gate.awaitIdle(0))
    }
}
