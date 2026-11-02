package io.github.shaonialife.carptocar.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 同一 SSID 的入网请求**互斥 + 去重**（纯逻辑，无 Android 依赖，便于单测）。
 *
 * ## 为什么需要
 *
 * 车机热点凭据有两条来源，会在同一个瞬间各触发一次入网：
 *  - `0x5703`（Iap2Wireless）→ `autoJoinCarWifi`
 *  - `0x4301`（CarPlayStartSession）→ 端点交接的 `wifi-join-on-endpoint`
 *
 * 而 `WifiCarPlayConnector.joinCarPlayNetwork` 的第一行是 `release()`
 * （`unregisterNetworkCallback` + `bindProcessToNetwork(null)`）。两个请求同时发时，
 * 后发的那次会把先发的**就地注销**：
 *
 *  1. 先发的永远等不到 `onAvailable` —— 白等满 15s 超时（日志里那行「超时（15s）」
 *     实际只等了 4 秒，是弹窗路径接手的假象）；
 *  2. `onNetworkLost` 随回调一起被注销 —— **被动断开自动重绑整条链路失效**，
 *     断网时日志里连一行「已断开」都不会有；
 *  3. 用户被迫面对系统确认框，整轮从点连接到真连上花掉 28 秒。
 *
 * 2026-11-02 日志（17:54:06 / 17:54:50）三处症状同源，就是这里。
 *
 * ## 语义
 *
 *  - 同一 SSID 已有请求在跑 → 后来者**等它的结果**（[Decision.Wait]），自己不发；
 *  - 同一 SSID 刚失败过，且还在 [dedupeWindowMillis] 内 → [Decision.Skip]；
 *  - 否则 → [Decision.Run]，调用方发请求，结束后必须调 [finish]。
 *
 * [force] 只跳过「刚失败」这段去重：用户点了「重试」是他明确要求的动作。
 * 它**不能**跳过「已有请求在跑」——那正是会互相 release 的情形。
 */
class WifiJoinGate(
    private val dedupeWindowMillis: Long = DEFAULT_DEDUPE_WINDOW_MS,
) {
    /** 一次在跑的入网请求：结果通过 [Ticket] 广播给所有等它的人。 */
    class Ticket internal constructor(
        val ssid: String,
    ) {
        internal val latch = CountDownLatch(1)

        @Volatile
        internal var succeeded: Boolean? = null

        /**
         * 等这一发的结果。返回 null = 窗口内没等到（调用方按失败处理，别再自己发一次）。
         */
        fun await(timeoutMillis: Long): Boolean? {
            try {
                latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // 被中断也是「没等到」；但必须把中断位还回去，否则调用方后面的
                // 阻塞操作（socket 连接、视频收尾）全都会静默失效
                Thread.currentThread().interrupt()
            }
            return succeeded
        }
    }

    /** 决议：调用方据此决定「自己发 / 等别人 / 跳过」。 */
    sealed interface Decision {
        /** 现在就去发请求；拿到结果后必须把 [ticket] 交给 [finish]。 */
        data class Run(
            val ticket: Ticket,
        ) : Decision

        /** 同一 SSID 已有请求在跑：等它，不要自己发（再发会把在跑的那次 release 掉）。 */
        data class Wait(
            val ticket: Ticket,
        ) : Decision

        /** 刚失败过，还在去重窗口内。 */
        data class Skip(
            val sinceMillis: Long,
        ) : Decision
    }

    private val lock = Any()
    private var pending: Ticket? = null
    private var lastSsid: String? = null
    private var lastAtMillis: Long = 0L
    private var lastSucceeded: Boolean = false

    fun decide(
        ssid: String,
        nowMillis: Long,
        force: Boolean = false,
    ): Decision =
        synchronized(lock) {
            val running = pending
            if (running != null && running.ssid == ssid) {
                Decision.Wait(running)
            } else if (!force &&
                lastSsid == ssid &&
                !lastSucceeded &&
                nowMillis - lastAtMillis < dedupeWindowMillis
            ) {
                Decision.Skip(nowMillis - lastAtMillis)
            } else {
                val fresh = Ticket(ssid)
                pending = fresh
                Decision.Run(fresh)
            }
        }

    /** 发起方跑完（或异常终止）后必须调用：[ticket] 是 [decide] 返回 Run 时创建的那一张。 */
    fun finish(
        ticket: Ticket,
        succeeded: Boolean,
        nowMillis: Long,
    ) {
        synchronized(lock) {
            // 只有「仍然是当前这一发」才写去重状态：换了 SSID 之后旧 ticket 才结束
            // 时它属于**另一条热点**，把它写进 lastSsid/lastSucceeded 会让新热点的
            // 正常重试被 5 秒窗口误挡（而旧热点自己反而没被记住）。
            if (pending === ticket) {
                pending = null
                lastSsid = ticket.ssid
                lastAtMillis = nowMillis
                lastSucceeded = succeeded
            }
            ticket.succeeded = succeeded
        }
        ticket.latch.countDown()
    }

    /** 当前是否有请求在跑（诊断/日志用）。 */
    fun runningSsid(): String? = synchronized(lock) { pending?.ssid }

    companion object {
        /**
         * 默认去重窗口。5 秒只挡住「同一瞬间两条路径各发一次」，而一次应用专属网络的
         * 关联 + DHCP 在 5GHz 车机热点上实测 3~8 秒，取 5 秒不会误伤正常重试。
         */
        const val DEFAULT_DEDUPE_WINDOW_MS = 5_000L
    }
}
