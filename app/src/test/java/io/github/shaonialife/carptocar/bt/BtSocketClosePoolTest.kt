package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 入站蓝牙 socket 的物理关闭额度（[BtSocketClosePool]）。
 *
 * 这里锁的是**额度守恒**：不管是重复提交、close 抛异常、关闭池打满，都必须
 * 「关闭一次 = 归还一次」。多还一格 = 静默超额（协议栈配额照样被打爆），
 * 少还一格 = accept 的并发上限永久缩水，最后整个监听不再接任何连接——
 * 两种都是要重启 App 才好的故障。
 */
class BtSocketClosePoolTest {
    @Test
    fun `额度用尽后拒绝再取`() {
        val pool = BtSocketClosePool(budget = 2)
        assertTrue(pool.tryAcquire())
        assertTrue(pool.tryAcquire())
        assertFalse(pool.tryAcquire())
        assertEquals(0, pool.availableSlots)
    }

    @Test
    fun `关闭返回之后才归还额度`() {
        val pool = BtSocketClosePool(budget = 1, threads = 1, queueDepth = 2)
        val gate = CountDownLatch(1)
        val closed = CountDownLatch(1)
        assertTrue(pool.tryAcquire())
        val socket = Any()
        pool.closeAsync(socket) {
            gate.await(2, TimeUnit.SECONDS)
            closed.countDown()
        }
        // 关闭还在进行：额度必须仍然被占着，否则 accept 会继续接新连接
        assertEquals(0, pool.availableSlots)
        gate.countDown()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        awaitUntil { pool.availableSlots == 1 }
    }

    @Test
    fun `同一个 socket 重复提交只关闭一次且只归还一次额度`() {
        val pool = BtSocketClosePool(budget = 2, threads = 2, queueDepth = 4)
        assertTrue(pool.tryAcquire())
        assertTrue(pool.tryAcquire())
        val closeCount = AtomicInteger(0)
        val first = Any()
        val second = Any()
        // 同一条 socket 的三个关闭点：目标校验拒绝 / 流关闭回调 / 会话收尾
        repeat(3) {
            pool.closeAsync(first) { closeCount.incrementAndGet() }
        }
        pool.closeAsync(second) { closeCount.incrementAndGet() }
        awaitUntil { pool.availableSlots == 2 }
        assertEquals("两个 socket 各关一次", 2, closeCount.get())
    }

    @Test
    fun `关闭抛异常也归还额度`() {
        val pool = BtSocketClosePool(budget = 1, threads = 1, queueDepth = 2)
        assertTrue(pool.tryAcquire())
        pool.closeAsync(Any()) { throw IllegalStateException("对端已关链") }
        // 少还一格会让 accept 永久停摆，这比「关闭抛异常」严重得多
        awaitUntil { pool.availableSlots == 1 }
    }

    @Test
    fun `队列与线程都打满时降级当场关闭且不丢额度`() {
        val pool = BtSocketClosePool(budget = 3, threads = 1, queueDepth = 1)
        assertTrue(pool.tryAcquire())
        assertTrue(pool.tryAcquire())
        assertTrue(pool.tryAcquire())
        val gate = CountDownLatch(1)
        val blocking = AtomicInteger(0)
        // 1 号占住唯一的关闭线程，2 号填满队列（线程 1 + 队列 1 = 满）
        repeat(2) {
            pool.closeAsync(Any()) {
                blocking.incrementAndGet()
                gate.await(2, TimeUnit.SECONDS)
            }
        }
        awaitUntil { blocking.get() == 1 }
        assertEquals("已有套接字在关，额度必须已被占满", 0, pool.availableSlots)
        // 第三个进不了队列（线程 1 + 队列 1 都满）→ 必须在调用线程上当场关掉
        val inline = AtomicInteger(0)
        pool.closeAsync(Any()) { inline.incrementAndGet() }
        assertEquals("队列满时应降级同步关闭，而不是丢掉额度", 1, inline.get())
        assertEquals("当场关掉的那条已归还，队列里的那条还欠着", 1, pool.availableSlots)
        gate.countDown()
        awaitUntil { pool.availableSlots == 3 }
    }

    private fun awaitUntil(
        timeoutMillis: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("等待超时", condition())
    }
}
