package io.github.shaonialife.carptocar.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动重连的唯一预算/互斥：建链失败与解冻恢复必须共用它。
 *
 * MGU-81 2026-11-23 17:43~17:47 的日志里，两套独立的退避循环叠加成每 10~20 秒
 * 一轮的重连风暴。这些测试锁住「一次只有一条重试」「新邀请作废旧重试」「预算封顶」。
 */
class ReconnectGateTest {
    @Test
    fun `同一时刻只允许一条重试排队`() {
        val gate = ReconnectGate(log = {})
        val first = gate.reserve("建链失败")
        assertNotNull(first)
        assertEquals(0, first!!.attempt)
        assertEquals(3_000L, first.delayMillis)
        assertNull("第二次触发不能再叠加一条", gate.reserve("解冻恢复"))

        gate.noteStarted(first)
        gate.finish()
        val second = gate.reserve("解冻恢复")
        assertNotNull(second)
        assertEquals(1, second!!.attempt)
        assertEquals(10_000L, second.delayMillis)
    }

    @Test
    fun `车机邀请作废挂着的旧重试`() {
        val gate = ReconnectGate(log = {})
        val reserved = gate.reserve("建链失败")!!
        assertFalse(gate.isStale(reserved))

        assertFalse("预算没用完时邀请照常接手", gate.invalidateForInvite())
        assertTrue("醒来后必须认出令牌已换", gate.isStale(reserved))
        assertNotNull("作废后排队位已释放", gate.reserve("新邀请"))
    }

    @Test
    fun `三次失败后封顶且新会话能重置`() {
        val logs = mutableListOf<String>()
        val gate = ReconnectGate(log = { logs += it })
        repeat(3) { i ->
            val r = gate.reserve("第 $i 次")!!
            gate.noteStarted(r)
            gate.finish()
        }
        assertTrue(gate.exhausted)
        assertNull(gate.reserve("第四次"))
        assertTrue(logs.last().contains("停止自动重试"))

        gate.reset("用户重新点启动连接")
        assertFalse(gate.exhausted)
        assertEquals(0, gate.reserve("新一轮")!!.attempt)
    }

    @Test
    fun `清空失败计数不影响排队位与令牌`() {
        val gate = ReconnectGate(log = {})
        val r = gate.reserve("建链失败")!!
        gate.noteStarted(r)
        gate.clearAttempt()
        assertFalse(gate.exhausted)
        assertFalse("入网成功不该让已经在睡的重试变成过期", gate.isStale(r))
    }
}
