package io.github.shaonialife.carptocar.session

import android.os.SystemClock

/**
 * 会话收尾的「唯一执行者」。
 *
 * 要求停会话的地方有四路，而且经常同时到：用户点「断开」（主线程）、通知栏动作、
 * `onSenderLinkClosed`（BT 线程）、车机邀请要求重建（Bg 线程）。旧实现各自往下跑
 * `retireSession()` / `detachActiveBtSessions()`，于是同一秒里会看到「已手动断开」
 * 打两遍、BT socket 被关两次、`CarPlayService.stop` 重入——最坏的情况是**收尾与
 * 新一轮建链交错**：新一代刚绑好的 WiFi 被上一代的 `wifiConnector.release()` 拆掉，
 * 日志里表现为「连上了又没了」，但两行日志之间隔了几百行，事后根本对不上。
 *
 * 这个类只做一件事：**让第一个请求者去收尾，其余的直接返回**。它不持有线程、不排队
 * 任务（收尾必须由调用者就地同步执行，才能保证「断开」返回时确实已经断干净）。
 *
 * 卡死防护：某次收尾异常卡住超过 [STALE_MILLIS]，下一次请求会强制接手并记一行日志——
 * 绝不因为一次失败让「断开」永久失效。
 */
class AsyncSessionStop(
    private val log: (String) -> Unit = {},
    private val now: () -> Long = { SystemClock.uptimeMillis() },
) {
    private val lock = Any()
    private var stoppingSince = 0L
    private var pendingReason: String? = null
    private var coalesced = 0
    private var lastFinishedAt = 0L

    val isStopping: Boolean get() = synchronized(lock) { stoppingSince != 0L }

    /** 正在收尾的那一次是由谁发起的。 */
    val reason: String? get() = synchronized(lock) { pendingReason }

    /** 被并掉的请求数（「同一秒点两次断开」这类）。 */
    val coalescedRequests: Int get() = synchronized(lock) { coalesced }

    /**
     * 申请执行收尾。
     *
     * @return **true = 由本线程执行收尾**（调用方负责在 finally 里 [finish]）；
     *   false = 已有一轮在收尾，本次什么都不用做
     */
    fun request(why: String): Boolean {
        var staleReason: String? = null
        val granted =
            synchronized(lock) {
                val since = stoppingSince
                if (since != 0L) {
                    val age = now() - since
                    if (age < STALE_MILLIS) {
                        coalesced++
                        false
                    } else {
                        // 上一轮卡住了：强制接手，别让「断开」永久失效
                        staleReason = pendingReason
                        stoppingSince = now()
                        pendingReason = why
                        true
                    }
                } else {
                    stoppingSince = now()
                    pendingReason = why
                    true
                }
            }
        if (!granted) {
            val running = reason
            log(
                "STOP: 「$why」要求收尾，但「${running ?: "上一轮"}」正在收尾" +
                    "（已 ${(now() - synchronized(lock) { stoppingSince }) / 1_000}s）——并掉，不重复拆解",
            )
        } else {
            staleReason?.let {
                log("STOP: 上一轮收尾（$it）已卡住超过 ${STALE_MILLIS / 1_000}s——强制接手（$why）")
            }
        }
        return granted
    }

    /** 收尾完成（**必须在 finally 里调**，否则后面的请求全被并掉）。 */
    fun finish(why: String) {
        synchronized(lock) {
            stoppingSince = 0L
            pendingReason = null
            lastFinishedAt = now()
            (lock as Object).notifyAll()
        }
        log("STOP: 收尾完成（$why）")
    }

    /** 等收尾结束。true = 空闲（可以安全开始新一轮）。 */
    fun awaitIdle(timeoutMillis: Long): Boolean {
        val deadline = now() + timeoutMillis
        synchronized(lock) {
            while (stoppingSince != 0L) {
                val left = deadline - now()
                if (left <= 0) return false
                try {
                    (lock as Object).wait(left)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return stoppingSince == 0L
                }
            }
            return true
        }
    }

    /** 一行状态（状态行 / 日志汇总用）。 */
    fun describe(): String {
        val stopping = synchronized(lock) { stoppingSince }
        val why = synchronized(lock) { pendingReason }
        val merged = synchronized(lock) { coalesced }
        val head =
            if (stopping == 0L) {
                "空闲"
            } else {
                "收尾中「${why ?: "?"}」已 ${(now() - stopping) / 1_000}s"
            }
        return "STOP: $head；累计并掉 $merged 次重复请求；上次完成于 " +
            (if (lastFinishedAt == 0L) "尚未发生" else "${(now() - lastFinishedAt) / 1_000}s 前")
    }

    private companion object {
        /** 收尾卡住多久算失效（超过就强制接手）。收尾本身正常在 2 秒内完成。 */
        const val STALE_MILLIS = 30_000L
    }
}
