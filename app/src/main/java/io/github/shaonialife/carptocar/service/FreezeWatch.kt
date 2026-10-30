package io.github.shaonialife.carptocar.service

/**
 * 进程冻结判定（纯逻辑，可单测）。
 *
 * ## 为什么需要它
 *
 * HyperOS / MagicOS 这类 ROM 在后台冻结进程时**不杀进程、不报错、不回调**：
 * 所有线程一起挂起，日志整段消失，解冻后从原处继续跑。现象是「画面突然定住，
 * 一两分钟后车机把会话撤了」，而日志上什么都看不到——只能靠猜。
 *
 * ## 判定手法
 *
 * 保活服务每秒 tick 一次，比较 `SystemClock.elapsedRealtime()`（单调时钟：
 * 不受用户改系统时间影响，但**冻结期间照常走**）与上一次 tick 的差值：
 * 正常差 ~1000ms；差 134000ms 就是进程被冻了 133 秒。
 *
 * 阈值定在 3 秒而非 1 秒：主线程偶尔跑一次重活（shell 调用上限 4s、
 * 编码器重建等）会造成 1~2 秒的正常迟到，不该报成冻结。
 *
 * 注意本类只做**判定**，落日志与恢复动作在 [CarPlayService] / `SessionController`。
 */
class FreezeWatch(
    private val tickMillis: Long = DEFAULT_TICK_MS,
    private val minFrozenMillis: Long = DEFAULT_MIN_FROZEN_MS,
) {
    private var lastTickAt = NO_TICK

    /**
     * 喂一次心跳。
     *
     * @param nowElapsed 当前 `SystemClock.elapsedRealtime()`
     * @return 本次相邻心跳之间被冻住的毫秒数；未达阈值（正常跳动 / 轻微迟到）返回 0
     */
    fun tick(nowElapsed: Long): Long {
        val previous = lastTickAt
        lastTickAt = nowElapsed
        // 第一次 tick 只用来起算
        if (previous == NO_TICK) return 0
        val gap = nowElapsed - previous
        // 时钟回退（异常）时不判定，重新起算
        if (gap < 0) return 0
        val frozen = gap - tickMillis
        return if (frozen >= minFrozenMillis) frozen else 0
    }

    /** 心跳中断的原始时长（冻结时长 + 一个正常周期），用于日志里写清楚「中断了多少」。 */
    fun stallMillis(frozenMillis: Long): Long = frozenMillis + tickMillis

    fun reset() {
        lastTickAt = NO_TICK
    }

    companion object {
        private const val NO_TICK = -1L

        /** 心跳周期：保活服务里每秒一次。 */
        const val DEFAULT_TICK_MS = 1_000L

        /** 判定阈值：心跳比正常晚到 3 秒以上（即相邻间隔 ≥ 4s）才当冻结。 */
        const val DEFAULT_MIN_FROZEN_MS = 3_000L

        /**
         * 值得重建会话的冻结时长。
         *
         * 冻结十几秒时车机通常已经按 keepAlive 超时撤会话（实测 133 秒整条链路被拆），
         * 而 3~10 秒的抖动多半只是主线程被占住，会话并未受伤——不必惊动它。
         */
        const val RECOVER_MIN_FROZEN_MS = 10_000L
    }
}
