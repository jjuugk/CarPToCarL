package io.github.shaonialife.carptocar.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收尾只允许一个执行者。
 *
 * 现场问题：用户双击「断开」、通知栏动作与 BT 线程同时要求收尾时，旧实现会并发跑
 * `retireSession()` / `detachActiveBtSessions()`——最坏的情况是上一代的
 * `wifiConnector.release()` 落在新一代刚绑好的网络上，日志里表现为「连上了又没了」。
 */
class AsyncSessionStopTest {
    private val lines = mutableListOf<String>()
    private var clock = 10_000L
    private val stop =
        AsyncSessionStop(
            log = { line -> lines += line },
            now = { clock },
        )

    @Test
    fun firstRequestWinsAndOthersAreCoalesced() {
        assertTrue(stop.request("用户断开"))
        assertFalse(stop.request("通知栏断开"))
        assertEquals(1, stop.coalescedRequests)
        assertTrue(stop.isStopping)
        assertTrue(lines.last().contains("并掉"))
    }

    @Test
    fun finishFreesItForTheNextRound() {
        assertTrue(stop.request("用户断开"))
        stop.finish("用户断开")
        assertFalse(stop.isStopping)
        assertTrue(stop.request("用户断开"))
        // 两次都是「真的去收尾」，没有并掉任何请求
        assertEquals(0, stop.coalescedRequests)
    }

    @Test
    fun stuckTeardownIsTakenOverAfterStaleWindow() {
        assertTrue(stop.request("用户断开"))
        // 卡住 31 秒：再来一个请求必须能接手，否则「断开」永久失效
        clock += 31_000
        assertTrue(stop.request("用户又点了一次断开"))
        assertTrue(lines.last().contains("强制接手"))
    }

    @Test
    fun awaitIdleReturnsWhenNotStopping() {
        assertTrue(stop.awaitIdle(0))
    }

    @Test
    fun awaitIdleTimesOutWhileStopping() {
        stop.request("用户断开")
        assertFalse(stop.awaitIdle(0))
    }

    @Test
    fun describeReportsStateAndCounts() {
        assertEquals(0, stop.coalescedRequests)
        assertTrue(stop.describe().contains("空闲"))
        stop.request("用户断开")
        clock += 2_000
        val line = stop.describe()
        assertTrue(line.contains("收尾中"))
        assertTrue(line.contains("用户断开"))
        assertTrue(line.contains("2s"))
    }
}
