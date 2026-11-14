package io.github.shaonialife.carptocar.util

/**
 * 关键事件旁路（不会被日志滚动冲掉的少数几行）。
 *
 * 起因（2026-11-13 的实车日志）：文件日志到 2MB 会滚动，内存缓冲只有 40k 字符，
 * 用户在会话跑了 25 分钟之后导出，拿到的是**会话中段**——`/info` 协商、音频 SETUP
 * 档位、编码器自检峰值、车机音量查询这些"一锤定音"的行全没了。于是每次复盘声音/
 * 画面问题都只能从稳态日志反推，猜的成分很大。
 *
 * 这里把这类「一次会话最多几十条、事后一定想回看」的行单独留在内存里（进程死才丢），
 * 导出日志时附在末尾。判据是**文案前缀白名单**，集中在 [SessionController.appendLog]
 * 一个钩子上，不需要在几十个调用点埋点。
 */
object CriticalEvents {
    /** 上限：一次长会话里的关键事件也就几十条；留 200 条足够覆盖两三轮。 */
    private const val MAX_LINES = 200

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    /**
     * 关键行的判据（包含即算）。
     *
     * 只挑**结论性**的行：协商成功/被拒、档位、自检、启动/停止、字幕式汇总。
     * 千万别把每 30 秒的体检行也放进来——那些是趋势数据，会把真正关键的几条挤出去。
     */
    private val PATTERNS =
        listOf(
            "AIRPLAY: ✓",
            "AIRPLAY: 音频流",
            "AIRPLAY: 车机音量",
            "AIRPLAY: 流 TEARDOWN",
            "AUDIO: 已启动",
            "AUDIO: 已停止",
            "AUDIO: 自检",
            "AUDIO: 车机 controlPort",
            "STREAM: 顺序",
            "MIC: 结论",
            "CTRL: 会话仍在",
            "SESSION",
        )

    private fun isCritical(text: String): Boolean = PATTERNS.any { text.contains(it) }

    /** 记一行（未命中判据时什么也不做）。**任意线程可调**。 */
    fun note(stamped: String) {
        if (!isCritical(stamped)) return
        synchronized(lock) {
            lines.addLast(stamped)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** 快照（按发生顺序）。导出时附在正文末尾。 */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    /** 新一轮进程/会话开始时清空（保留跨轮内容反而会淹没本轮）。 */
    fun clear() {
        synchronized(lock) { lines.clear() }
    }
}
