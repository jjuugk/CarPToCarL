package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阶段计时：进阶段一行、超预算要标出来、小结要能回答「一共多久、花在哪」。 */
class ConnectBudgetTest {
    private var now = 0L
    private val logs = ArrayList<String>()
    private val budget = ConnectBudget(clock = { now }, sink = { logs += it })

    @Test
    fun enterReportsPreviousStageElapsed() {
        budget.begin("0x4301")
        now = 2_400
        val spent = budget.enter(ConnectStage.PAIRING)
        assertEquals(2_400L, spent)
        assertTrue(logs.any { it.contains("AirPlay 配对开始") && it.contains("2.4s") })
    }

    @Test
    fun overdueReportsButDoesNotTearDown() {
        budget.begin("test")
        budget.enter(ConnectStage.SETUP)
        now = ConnectStage.SETUP.budgetMs - 1
        assertNull(budget.overdue())
        now = ConnectStage.SETUP.budgetMs + 1
        val line = budget.overdue()
        assertNotNull(line)
        assertTrue(line!!.contains("超预算"))
    }

    @Test
    fun streamingStageHasNoBudget() {
        budget.begin("test")
        budget.enter(ConnectStage.STREAMING)
        now = 24 * 60 * 60_000L
        assertNull("推流阶段没有预算，不该报超时", budget.overdue())
    }

    @Test
    fun summaryNamesEveryStageAndTotal() {
        budget.begin("0x5703")
        now = 8_200
        budget.enter(ConnectStage.WIFI)
        now = 11_300
        budget.enter(ConnectStage.PAIRING)
        now = 131_700
        budget.enter(ConnectStage.SETUP)
        val summary = budget.summary()
        assertTrue(summary.contains("本轮小结"))
        assertTrue(summary.contains("0x5703"))
        assertTrue(summary.contains("加入车机热点"))
        assertTrue(summary.contains("AirPlay 配对"))
        // 配对用了 120.4s > 120s 预算 → 必须标出来，否则等于没测
        assertTrue("超预算的阶段要被标出：$summary", summary.contains("超预算"))
        assertTrue(summary.contains("共 131s"))
    }

    @Test
    fun markStreamingIsLoggedOnceWithTotal() {
        budget.begin("test")
        now = 23_500
        budget.markStreaming()
        assertTrue(logs.any { it.contains("首帧已到达") && it.contains("23.5s") })
    }

    @Test
    fun enterWithoutBeginIsSafe() {
        // 解冻恢复等路径可能没走 begin：不能静默丢计时，也不能崩
        now = 5_000
        budget.enter(ConnectStage.PAIRING)
        assertTrue(budget.isActive)
        assertTrue(logs.any { it.contains("AirPlay 配对开始") })
    }

    @Test
    fun summaryWithoutAnyRoundSaysSo() {
        assertTrue(ConnectBudget(clock = { 0L }).summary().contains("无计时记录"))
    }

    @Test
    fun budgetValuesMatchExistingTimeouts() {
        // 这几个数字不是新发明的，必须与 SessionController 里既有常量一致：
        // PENDING_START_SESSION_WAIT_MS=5min、CAR_WIFI_BIND_WAIT_MS=45s(取整 60s)、
        // PAIRING_WINDOW_MILLIS=120s、INITIAL_SETUP_WINDOW_MS=30s
        assertEquals(5 * 60_000L, ConnectStage.ENDPOINT.budgetMs)
        assertEquals(60_000L, ConnectStage.WIFI.budgetMs)
        assertEquals(120_000L, ConnectStage.PAIRING.budgetMs)
        assertEquals(30_000L, ConnectStage.SETUP.budgetMs)
        assertEquals(20_000L, ConnectStage.FIRST_FRAME.budgetMs)
    }
}
