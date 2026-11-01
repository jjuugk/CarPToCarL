package io.github.shaonialife.carptocar.util

import android.os.SystemClock

/**
 * 一轮连接的各阶段与预算。
 *
 * 预算值不是新发明的，全部来自现有常量：等端点 = `PENDING_START_SESSION_WAIT_MS`（5 分钟，
 * 奥迪 MMI 9310 实测 0x4301 晚到 2 分 35 秒）、热点 = `CAR_WIFI_BIND_WAIT_MS`（45s 取整到
 * 60s）、配对 = `PAIRING_WINDOW_MILLIS`（120s）、SETUP = `INITIAL_SETUP_WINDOW_MS`（30s）。
 * 首帧 20s 是新加的：走到这一步说明会话已建立，20s 还出不来帧就不是「慢」而是「没有」。
 */
enum class ConnectStage(
    val label: String,
    val budgetMs: Long,
) {
    ENDPOINT("等车机连接信息（0x4301）", 5 * 60_000L),
    WIFI("加入车机热点", 60_000L),
    PAIRING("AirPlay 配对", 120_000L),
    SETUP("会话建立（SETUP）", 30_000L),
    FIRST_FRAME("等首帧", 20_000L),
    STREAMING("推流中", 0L),
    ;

    val hasBudget: Boolean get() = budgetMs > 0L
}

/**
 * 一轮连接的阶段计时器（线程安全，纯内存，可注入时钟 → 单测不依赖 Android）。
 *
 * 解决的是排查时的第一个问题：**「这次连接一共花了多久、花在哪」。**
 * 旧日志里七个阶段的耗时是散落在各处的（端口未开的重试刷屏、mDNS 提示、SETUP 结果…），
 * 用户问「为什么要等这么久」时只能靠人工掐时间（2026-11-01 日志 090105：连接 29.7s，
 * 其中车机回连 14.9s、WiFi 入网 7.7s、/info 慢应答 3.3s）。
 *
 * 现在每进一个阶段打一行、结束时打一行汇总，不用再猜。
 */
class ConnectBudget(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val sink: (String) -> Unit = {},
) {
    private data class Entry(
        val stage: ConnectStage,
        val atMillis: Long,
    )

    private val lock = Any()

    /**
     * 是否有进行中的一轮。
     *
     * **不能用 `startedAt == 0` 当「未开始」的标志**：时钟零点本身是合法值
     * （`elapsedRealtime()` 刚开机时就在 0 附近，单测里更是直接注入 0），
     * 用它做哨兵会让第一段计时整个丢掉。
     */
    private var active = false
    private var startedAt = 0L
    private var reason = ""
    private var current: Entry? = null
    private val history = ArrayList<Entry>(8)

    /** 本轮是否有进行中的计时（未 [begin] 时一切记录为空操作）。 */
    val isActive: Boolean get() = synchronized(lock) { active }

    /** 开始一轮连接。[note] 写清触发来源（0x5703 / 0x4301 / 解冻恢复…）。 */
    fun begin(note: String) {
        synchronized(lock) {
            startedAt = clock()
            active = true
            reason = note
            history.clear()
            current = Entry(ConnectStage.ENDPOINT, startedAt).also { history += it }
        }
        sink("STAGE: 本轮开始（$note）——预算：等端点 5min / 热点 60s / 配对 120s / SETUP 30s / 首帧 20s")
    }

    /** 进入下一个阶段，返回**上一阶段实际耗时**（毫秒；第一阶段返回 0）。 */
    fun enter(stage: ConnectStage): Long {
        val line: String
        val spent: Long
        synchronized(lock) {
            val now = clock()
            if (!active) {
                // 兜底：调用方漏了 begin（例如解冻恢复路径），按「本轮刚开始」处理，
                // 而不是静默什么都不记——丢了计时就等于这次改动白做。
                startedAt = now
                active = true
                reason = "未声明"
                current = null
                history.clear()
            }
            val prev = current
            spent = if (prev == null) 0L else now - prev.atMillis
            val entry = Entry(stage, now)
            current = entry
            history += entry
            line =
                "STAGE: ${stage.label}开始" +
                    if (prev == null) {
                        ""
                    } else {
                        "（上段 ${prev.stage.label} 用时 ${seconds(spent)}" +
                            (if (prev.stage.hasBudget && spent > prev.stage.budgetMs) "，**超预算**" else "") +
                            "，本轮累计 ${seconds(now - startedAt)}）"
                    }
        }
        sink(line)
        return spent
    }

    /** 首帧到达：单独一条日志，因为它是「有没有画面」的分界线。 */
    fun markStreaming(extra: String = "") {
        val line: String
        synchronized(lock) {
            val now = clock()
            if (!active) return
            current = Entry(ConnectStage.STREAMING, now).also { history += it }
            line = "STAGE: ✓ 首帧已到达，开始推流（本轮累计 ${seconds(now - startedAt)}）${if (extra.isEmpty()) "" else " $extra"}"
        }
        sink(line)
    }

    /**
     * 当前阶段是否已超预算；超了返回一句话（含实际耗时与预算），未超返回 null。
     *
     * 只负责**报告**，不负责拆链：拆链由原有的看门狗/窗口逻辑决定，这里不引入第二种
     * 超时语义（两套超时同时生效是这台项目历史上反复踩的坑）。
     */
    fun overdue(): String? {
        synchronized(lock) {
            val entry = current ?: return null
            if (!entry.stage.hasBudget) return null
            val spent = clock() - entry.atMillis
            if (spent <= entry.stage.budgetMs) return null
            return "STAGE: ⚠ ${entry.stage.label}已超预算（用时 ${seconds(spent)}，预算 ${seconds(entry.stage.budgetMs)}）"
        }
    }

    /** 一轮结束时的汇总：「共 143s：等端点 8.2s → 热点 3.1s → 配对 120.4s（超预算）」。 */
    fun summary(): String {
        val (stages, total, tail) =
            synchronized(lock) {
                if (!active) return "STAGE: 本轮无计时记录"
                val now = clock()
                val parts = StringBuilder()
                for ((index, entry) in history.withIndex()) {
                    val end = history.getOrNull(index + 1)?.atMillis ?: now
                    val spent = end - entry.atMillis
                    if (index > 0) parts.append(" → ")
                    parts.append(entry.stage.label).append(' ').append(seconds(spent))
                    if (entry.stage.hasBudget && spent > entry.stage.budgetMs) parts.append("（超预算）")
                }
                Triple(parts.toString(), now - startedAt, reason)
            }
        return "STAGE: 本轮小结（$tail）共 ${seconds(total)}：$stages"
    }

    /** 一分钟以内保留一位小数（连接耗时多在这个量级，差 0.3s 也要看得出来），更长的取整。 */
    private fun seconds(millis: Long): String =
        if (millis < 60_000) {
            String.format(java.util.Locale.US, "%.1fs", millis / 1000.0)
        } else {
            "${millis / 1000}s"
        }
}
