package io.github.shaonialife.carptocar.bt

import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 入站蓝牙 socket 的「物理关闭额度」（进程级）。
 *
 * 为什么需要：`BluetoothSocket.close()` 在协议栈里是**阻塞**的（对端不响应时能卡到秒级），
 * 而 Android 的 RFCOMM 关闭/连接共用进程级配额。旧实现直接在 accept 循环里同步 close，
 * 车机反复回连时一条条堆在关闭上，配额吃满后新的 connect 直接失败——现象是
 * 「越连越差，重启 App 就好了」。
 *
 * 做法（与 link_plus 的 `socketBudget` 同构）：逻辑会话可以立刻退役，套接字的物理关闭
 * 交给 2 线程有界池（队列 6），**额度只在 close 返回之后才归还**；额度用尽就暂停 accept，
 * 而不是继续把没关掉的套接字堆进协议栈。
 *
 * 调用约定：只有**已经**通过 [tryAcquire] 拿到额度的套接字才能交给 [closeAsync]，
 * 它负责归还那一份额度。同一个 key 重复提交只入队一次。
 */
internal class BtSocketClosePool(
    /** 同时在用/在关（尚未真正 close 返回）的套接字上限。 */
    val budget: Int = DEFAULT_BUDGET,
    threads: Int = DEFAULT_THREADS,
    queueDepth: Int = DEFAULT_QUEUE_DEPTH,
) {
    companion object {
        const val DEFAULT_BUDGET = 6
        private const val DEFAULT_THREADS = 2
        private const val DEFAULT_QUEUE_DEPTH = 6
    }

    private val permits = Semaphore(budget)

    /**
     * 已提交关闭的套接字（弱引用，与 link_plus 一致：对象被回收后本来也不会再有人关它）。
     *
     * 关闭点分散在「目标校验拒绝 / 流关闭回调 / 会话收尾」三处，同一个 socket 必然被
     * 请求多次，重复入队会把额度还多——那比额度泄漏更隐蔽（静默超额）。
     */
    private val closing: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))

    private val executor =
        ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueDepth),
            ThreadFactory { task -> Thread(task, "iap2-bt-close").apply { isDaemon = true } },
        )

    /** 当前可用额度（0 = 该暂停 accept 了）。 */
    val availableSlots: Int get() = permits.availablePermits()

    /** 取一个额度；false = 还有套接字没真正关掉，调用方应退让而不是继续 accept。 */
    fun tryAcquire(): Boolean = permits.tryAcquire()

    /** 放弃已经取到的额度（accept 失败 / 已无监听时）。 */
    fun release() {
        permits.release()
    }

    /**
     * 异步关闭 [key]（通常是 `BluetoothSocket`），[close] 返回后归还额度。
     *
     * [close] 抛异常（对端已关链、流已关）照样归还——否则一次异常就让 accept 永久停摆。
     */
    fun closeAsync(
        key: Any,
        close: () -> Unit,
    ) {
        if (!closing.add(key)) return
        val task =
            Runnable {
                try {
                    close()
                } catch (_: Throwable) {
                    // 关闭失败也算「这条额度用完了」：协议栈可能已经把它回收
                } finally {
                    permits.release()
                }
            }
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // 线程和队列都满了：宁可当场阻塞着关，也不能把这条额度丢掉
            task.run()
        }
    }
}
