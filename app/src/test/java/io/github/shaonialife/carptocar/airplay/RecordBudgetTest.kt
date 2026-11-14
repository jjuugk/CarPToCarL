package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级 RECORD 预算：默认放行重复（奥迪 MIB3 需要第二次 RECORD 当 nudge），
 * 被收紧后只放行第一次（宝马 iD6 / JYBOX-80 收到第二次会关掉自己的 AirPlay 服务）。
 */
class RecordBudgetTest {
    @Test
    fun `默认允许重复——连续三次都应放行`() {
        val budget = RecordBudget()
        assertTrue(budget.shouldSend())
        assertTrue(budget.shouldSend())
        assertTrue(budget.shouldSend())
    }

    @Test
    fun `默认允许重复——发过之后仍然放行`() {
        val budget = RecordBudget()
        budget.noteSent()
        budget.noteSent()
        assertTrue(budget.shouldSend())
        assertEquals(2, budget.sentCount)
    }

    @Test
    fun `只允许一次——第一次放行第二次拦下`() {
        val budget = RecordBudget(allowRepeat = false)
        assertTrue(budget.shouldSend())
        budget.noteSent()
        assertFalse(budget.shouldSend())
    }

    @Test
    fun `只允许一次——之后一直拦下`() {
        val budget = RecordBudget(allowRepeat = false)
        budget.noteSent()
        assertFalse(budget.shouldSend())
        assertFalse(budget.shouldSend())
        assertFalse(budget.shouldSend())
    }

    @Test
    fun `运行时收紧——之前放行的次数不影响之后的拦截`() {
        val budget = RecordBudget()
        budget.noteSent()
        assertTrue(budget.shouldSend())
        budget.restrictToSingle()
        assertFalse(budget.shouldSend())
    }

    @Test
    fun `运行时收紧——还没发过时第一次仍放行`() {
        val budget = RecordBudget()
        budget.restrictToSingle()
        assertTrue(budget.shouldSend())
        budget.noteSent()
        assertFalse(budget.shouldSend())
    }

    @Test
    fun `被拦下的跳过不计入已发次数`() {
        val budget = RecordBudget(allowRepeat = false)
        budget.noteSent()
        assertFalse(budget.shouldSend())
        assertEquals(1, budget.sentCount)
    }

    @Test
    fun `拦截原因说明为什么跳过`() {
        val budget = RecordBudget(allowRepeat = false)
        budget.noteSent()
        val reason = budget.describeSkip()
        assertTrue(reason.contains("第二次 RECORD"))
        assertTrue(reason.contains("已发 1 次"))
    }
}
