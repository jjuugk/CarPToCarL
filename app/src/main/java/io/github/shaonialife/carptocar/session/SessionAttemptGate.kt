package io.github.shaonialife.carptocar.session

import android.os.SystemClock

/**
 * 会话建链闸门：一轮「端点 → 绑网 → 配对 → SETUP → 推流」只允许一个在跑。
 *
 * 原来这件事靠 `sessionStarting: AtomicBoolean` 加一句 `compareAndSet`，能挡住并发，
 * 但挡不出**任何可读的信息**：日志里只有「已有会话在处理中，忽略本次端点」，卡住时
 * 既不知道是哪一轮在跑、跑了多久、从哪儿来的，也不知道这一轮被拦下了几次。别克那几晚
 * 的日志里，同一分钟内「忽略本次端点」出现四五次，事后完全没法判断是上一轮僵尸卡住
 * 还是车机在连发 0x4301。
 *
 * 所以把它换成一个记账的闸门：
 *  - 每轮有**编号、来源、起始时刻**，开始/结束各落一行（含耗时）；
 *  - 结束必须带原因，[takeover] 专门用于「上一轮已确定死亡、由新一轮强行接手」——
 *    这种强行解锁以前散在四处，每次都可能把**别人的**轮次解锁掉；
 *  - 被拦下的请求累计次数与最后来源，[describe] 一行说清。
 *
 * 线程安全：内部一把 monitor 锁；[log] 一律在锁外调用，避免与 appendLog 的锁嵌套。
 */
class SessionAttemptGate(
    private val log: (String) -> Unit = {},
    private val now: () -> Long = { SystemClock.uptimeMillis() },
) {
    /** 一轮建链。 */
    data class Attempt(
        val id: Int,
        val source: String,
        val startedAtMillis: Long,
    ) {
        fun elapsed(nowMillis: Long): Long = nowMillis - startedAtMillis
    }

    /** 锁内产出的结果，锁外再打日志（[attempt] 为 null 时 [blocker] 是占位的那一轮）。 */
    private class Outcome(val attempt: Attempt?, val blocker: Attempt?)

    private val lock = Any()
    private var active: Attempt? = null
    private var nextId = 1
    private var blockedCount = 0
    private var lastBlockedSource: String? = null
    private var finishedCount = 0

    /** 正在跑的那一轮；null = 空闲。 */
    val attempt: Attempt?
        get() = synchronized(lock) { active }

    val isActive: Boolean get() = attempt != null

    /** 正在跑的那一轮的来源（日志/UI 用）。 */
    val activeSource: String?
        get() = attempt?.source

    /** 本次运行里被闸门拦下的次数。 */
    val blockedSinceStart: Int
        get() = synchronized(lock) { blockedCount }

    /**
     * 申请进场。
     *
     * @return 拿到的那一轮；**null = 已有一轮在跑**（本次不进场，调用方按「忽略本次端点」处理）
     */
    fun begin(source: String): Attempt? {
        val outcome =
            synchronized(lock) {
                val running = active
                if (running != null) {
                    blockedCount++
                    lastBlockedSource = source
                    Outcome(null, running)
                } else {
                    val created = Attempt(nextId++, source, now())
                    active = created
                    Outcome(created, null)
                }
            }
        val created = outcome.attempt
        if (created != null) {
            log("GATE: 会话 #${created.id} 开始（$source）")
        } else {
            val blocker = outcome.blocker
            if (blocker != null) {
                log(
                    "GATE: 「$source」想开新一轮，但 #${blocker.id}「${blocker.source}」还在跑" +
                        "（已 ${blocker.elapsed(now()) / 1_000}s，累计拦下 $blockedCount 次）——本轮不进场",
                )
            }
        }
        return created
    }

    /**
     * 结束当前这一轮。
     *
     * @return true = 确实有一轮被结束（用于「只有真的结束了才打收尾日志」的场合）
     */
    fun end(reason: String): Boolean {
        val finished =
            synchronized(lock) {
                val running = active
                if (running == null) {
                    null
                } else {
                    active = null
                    finishedCount++
                    (lock as Object).notifyAll()
                    running
                }
            } ?: return false
        log("GATE: 会话 #${finished.id} 结束（${finished.elapsed(now()) / 1_000}s）——$reason")
        return true
    }

    /**
     * 强行接手：把当前这一轮判死并开一轮新的。
     *
     * 用于「上一轮已确定不可能再推进」的场合（解冻恢复、推流中车机要求重建、用户取消后
     * 配对线程仍卡在不可中断的等待里）。以前各处直接 `sessionStarting.set(false)`，
     * 谁都能把别人的轮次解锁；这里至少把「替掉的是哪一轮、为什么」写进日志。
     */
    fun takeover(source: String, reason: String): Attempt {
        var previous: Attempt? = null
        val created =
            synchronized(lock) {
                previous = active
                val fresh = Attempt(nextId++, source, now())
                active = fresh
                (lock as Object).notifyAll()
                fresh
            }
        previous?.let {
            log(
                "GATE: 强行接手 #${it.id}「${it.source}」（已 ${it.elapsed(now()) / 1_000}s）——$reason",
            )
        }
        return created
    }

    /** 等当前这一轮结束（最多 [timeoutMillis]）。true = 空闲。 */
    fun awaitIdle(timeoutMillis: Long): Boolean {
        val deadline = now() + timeoutMillis
        synchronized(lock) {
            while (active != null) {
                val left = deadline - now()
                if (left <= 0) return false
                try {
                    (lock as Object).wait(left)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return active == null
                }
            }
            return true
        }
    }

    /** 一行状态（状态行 / 日志汇总用）。 */
    fun describe(): String {
        val running = synchronized(lock) { active }
        val blocked = synchronized(lock) { blockedCount }
        val lastSource = synchronized(lock) { lastBlockedSource }
        val done = synchronized(lock) { finishedCount }
        val head =
            if (running == null) {
                "空闲（本次运行已结束 $done 轮）"
            } else {
                "#${running.id}「${running.source}」进行中，已 ${running.elapsed(now()) / 1_000}s"
            }
        val tail =
            if (blocked == 0) {
                ""
            } else {
                "；累计拦下 $blocked 次（最后来源：${lastSource ?: "?"}）"
            }
        return "GATE: $head$tail"
    }
}
