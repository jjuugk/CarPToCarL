package io.github.shaonialife.carptocar.session

/**
 * 自动重连的**唯一**预算与互斥（2026-11-23）。
 *
 * 以前有两套互相不知道对方存在的重试：
 *
 * - 建链失败 → `connectRetryLock` + 3/10/30 秒退避；
 * - 进程解冻 → `freezeRecovery` + 3/6/12/24 秒循环。
 *
 * MGU-81 的日志里两套同时跑，17:43~17:47 变成每 10~20 秒一轮的重连风暴，车机
 * 一直没机会把旧会话收干净。现在统一成这一套：次数、退避、排队位、作废令牌
 * 都在这里记账，两个触发源只是调用方传进来的 [reason] 不同。
 *
 * [generation] 是「这次排队还算不算数」的令牌：车机新邀请到达时 +1，挂在退避
 * 睡眠里的旧线程醒来发现令牌变了就安静退出——两轮同时配对必然互相 retireSession。
 */
class ReconnectGate(
    private val backoffMillis: LongArray = DEFAULT_BACKOFF_MILLIS,
    private val log: (String) -> Unit = {},
) {
    /** 一次被受理的重试：序号（0-based）、要等多久、以及随之而来的作废令牌。 */
    data class Reservation(
        val attempt: Int,
        val delayMillis: Long,
        val generation: Int,
    )

    private val lock = Any()
    private var scheduled = false
    private var attempt = 0
    private var generation = 0

    val maxAttempts: Int get() = backoffMillis.size

    val exhausted: Boolean get() = synchronized(lock) { attempt >= backoffMillis.size }

    /** 占住排队位并取本次的退避；已有排队或预算用尽时返回 null。 */
    fun reserve(reason: String): Reservation? =
        synchronized(lock) {
            if (scheduled) {
                log("RETRY: 已有一轮重试在排队——本次失败不再叠加（$reason）")
                return null
            }
            if (attempt >= backoffMillis.size) {
                log("RETRY: 已连续失败 $attempt 轮，停止自动重试——请在车机上把 CarPlay 关掉再打开后手动点「启动连接」")
                return null
            }
            val reserved = Reservation(attempt, backoffMillis[attempt], generation)
            scheduled = true
            reserved
        }

    /** 睡完醒来后问一句：期间有没有新轮次接手？ */
    fun isStale(reserved: Reservation): Boolean = synchronized(lock) { generation != reserved.generation }

    /** 真正发起连接时结算序号（失败预算 +1）。 */
    fun noteStarted(reserved: Reservation): Unit = synchronized(lock) { attempt = reserved.attempt + 1 }

    /** 无论这次重试是发了、跳过还是作废，排队位都要归还。 */
    fun finish(): Unit = synchronized(lock) { scheduled = false }

    /**
     * 车机邀请 / 用户新一轮操作到达：令牌 +1，让睡着的旧重试作废。
     * @return true = 预算已用尽，连权威的邀请也不再自动接（沿用改动前的语义）。
     */
    fun invalidateForInvite(): Boolean =
        synchronized(lock) {
            if (attempt >= backoffMillis.size) return true
            generation++
            scheduled = false
            false
        }

    /** 新会话 / 对端结束 / 用户断开：预算与令牌全部从头算。 */
    fun reset(reason: String): Unit =
        synchronized(lock) {
            generation++
            scheduled = false
            attempt = 0
        }

    /** 只清失败计数（入网成功后「这一轮已经连上」但不想作废正在排队的重试）。 */
    fun clearAttempt(): Unit = synchronized(lock) { attempt = 0 }

    companion object {
        /** 3s 覆盖「刚被拆可重进」，10s 覆盖「车机重启 AirPlay 服务」，30s 覆盖「端口关掉再开」。 */
        val DEFAULT_BACKOFF_MILLIS = longArrayOf(3_000L, 10_000L, 30_000L)
    }
}
