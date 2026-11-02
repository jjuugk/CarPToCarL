package io.github.shaonialife.carptocar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 入网互斥/去重的边界全部钉死。
 *
 * 判错的代价不是「慢一点」：同一 SSID 两次入网并发时，后发的那次会把先发的
 * 回调就地注销（`joinCarPlayNetwork` 第一行就是 `release()`），于是白等 15s、
 * 而且**被动断开的自动重绑整条失效**（2026-11-02 日志的三处症状同源）。
 */
class WifiJoinGateTest {
    private fun gate() = WifiJoinGate(5_000L)

    @Test
    fun firstRequestRuns() {
        val gate = gate()
        val decision = gate.decide("Audi_5GHz", nowMillis = 1_000L)
        assertTrue(decision is WifiJoinGate.Decision.Run)
    }

    @Test
    fun secondRequestOfSameSsidWaits() {
        val gate = gate()
        val first = gate.decide("Audi_5GHz", nowMillis = 1_000L) as WifiJoinGate.Decision.Run
        val second = gate.decide("Audi_5GHz", nowMillis = 1_040L)
        assertTrue("同 SSID 并发必须等待，不能自己再发一次", second is WifiJoinGate.Decision.Wait)
        assertEquals(first.ticket, (second as WifiJoinGate.Decision.Wait).ticket)
    }

    @Test
    fun waiterSeesResultOfRunningRequest() {
        val gate = gate()
        val running = gate.decide("Audi_5GHz", nowMillis = 1_000L) as WifiJoinGate.Decision.Run
        val waiting = gate.decide("Audi_5GHz", nowMillis = 1_100L) as WifiJoinGate.Decision.Wait
        val done = CountDownLatch(1)
        var observed: Boolean? = null
        Thread {
            observed = waiting.ticket.await(2_000L)
            done.countDown()
        }.start()
        gate.finish(running.ticket, succeeded = true, nowMillis = 1_400L)
        assertTrue("等待方必须被唤醒", done.await(2, TimeUnit.SECONDS))
        assertTrue("等待方应拿到「已加入」", observed == true)
    }

    @Test
    fun differentSsidIsNotBlocked() {
        val gate = gate()
        gate.decide("ID7Pro WiFi 5G", nowMillis = 1_000L)
        val other = gate.decide("My VW 7555 5G", nowMillis = 1_010L)
        assertTrue("换车机/换热点不该被上一条挡住", other is WifiJoinGate.Decision.Run)
    }

    @Test
    fun recentFailureIsDeduped() {
        val gate = gate()
        val run = gate.decide("Audi_5GHz", nowMillis = 1_000L) as WifiJoinGate.Decision.Run
        gate.finish(run.ticket, succeeded = false, nowMillis = 16_000L)
        // 5 秒窗口内：另一条路径立刻又来一遍，必须跳过（否则又是互相 release）
        val again = gate.decide("Audi_5GHz", nowMillis = 17_000L)
        assertTrue(again is WifiJoinGate.Decision.Skip)
        assertEquals(1_000L, (again as WifiJoinGate.Decision.Skip).sinceMillis)
    }

    @Test
    fun dedupeWindowExpires() {
        val gate = gate()
        val run = gate.decide("Audi_5GHz", nowMillis = 1_000L) as WifiJoinGate.Decision.Run
        gate.finish(run.ticket, succeeded = false, nowMillis = 16_000L)
        val later = gate.decide("Audi_5GHz", nowMillis = 21_100L)
        assertTrue("过了 5 秒就该允许重试", later is WifiJoinGate.Decision.Run)
    }

    @Test
    fun forceBypassesDedupe() {
        val gate = gate()
        val run = gate.decide("Audi_5GHz", nowMillis = 1_000L) as WifiJoinGate.Decision.Run
        gate.finish(run.ticket, succeeded = false, nowMillis = 16_000L)
        // 用户点了「重试」= 他明确要求的动作，去重窗口不该拦
        val retry = gate.decide("Audi_5GHz", nowMillis = 16_500L, force = true)
        assertTrue(retry is WifiJoinGate.Decision.Run)
    }

    @Test
    fun forceDoesNotBypassRunningRequest() {
        val gate = gate()
        gate.decide("Audi_5GHz", nowMillis = 1_000L)
        // 已有请求在跑时，即使用户点重试也不能并发——那才是会互相 release 的情形
        val retry = gate.decide("Audi_5GHz", nowMillis = 1_100L, force = true)
        assertTrue(retry is WifiJoinGate.Decision.Wait)
    }

    @Test
    fun successIsNotDeduped() {
        val gate = gate()
        val run = gate.decide("Audi_5GHz", nowMillis = 1_000L) as WifiJoinGate.Decision.Run
        gate.finish(run.ticket, succeeded = true, nowMillis = 8_000L)
        // 成功过的 SSID 再来一次不需要去重（调用方会先看 currentNetwork，
        // 真掉线了也得允许立刻重连）
        val again = gate.decide("Audi_5GHz", nowMillis = 8_500L)
        assertTrue(again is WifiJoinGate.Decision.Run)
    }

    @Test
    fun pendingClearsAfterFinish() {
        val gate = gate()
        val run = gate.decide("Audi_5GHz", nowMillis = 1_000L) as WifiJoinGate.Decision.Run
        assertEquals("Audi_5GHz", gate.runningSsid())
        gate.finish(run.ticket, succeeded = true, nowMillis = 9_000L)
        org.junit.Assert.assertNull(gate.runningSsid())
    }

    @Test
    fun supersededTicketDoesNotPolluteDedupe() {
        val gate = gate()
        val first = gate.decide("Audi_5GHz", nowMillis = 1_000L) as WifiJoinGate.Decision.Run
        gate.decide("ID7Pro WiFi 5G", nowMillis = 1_010L) as WifiJoinGate.Decision.Run
        // 旧的那发晚一拍才失败：它属于**另一条热点**，不能替新热点点亮去重窗口
        gate.finish(first.ticket, succeeded = false, nowMillis = 12_000L)
        val again = gate.decide("Audi_5GHz", nowMillis = 12_100L)
        assertTrue("被顶替的旧请求失败不该挡住这一条的重试", again is WifiJoinGate.Decision.Run)
    }

    @Test
    fun supersededTicketStillWakesItsWaiter() {
        val gate = gate()
        val first = gate.decide("Audi_5GHz", nowMillis = 1_000L) as WifiJoinGate.Decision.Run
        val waiter = gate.decide("Audi_5GHz", nowMillis = 1_050L) as WifiJoinGate.Decision.Wait
        gate.decide("ID7Pro WiFi 5G", nowMillis = 1_100L)
        val done = CountDownLatch(1)
        var observed: Boolean? = null
        Thread {
            observed = waiter.ticket.await(2_000L)
            done.countDown()
        }.start()
        gate.finish(first.ticket, succeeded = true, nowMillis = 1_400L)
        assertTrue("被顶替也要唤醒等待方，否则它要白等到超时", done.await(2, TimeUnit.SECONDS))
        assertTrue(observed == true)
    }
}
