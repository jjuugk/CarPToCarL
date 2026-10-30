package io.github.shaonialife.carptocar.bt

/**
 * 「界面到底有没有被拉到前台」的观测点（2026-10-30）。
 *
 * 为什么需要它：Android 10+ 的后台启动界面限制是**静默**的——`startActivity` 不抛异常、
 * 返回后一切正常，只是界面永远不出现。靠近车机触发时手机通常在口袋里/黑屏，
 * 于是表现为「日志说已拉起、人什么都看不到」，而且没有任何一处代码能觉察到失败。
 *
 * 这里放一个极小的汇合点：[MainActivity] 每次被建起或被带到前台时盖章，
 * [CarApproachReceiver] 触发后隔几秒核对一次——章没更新，就说明这次拉起被系统吃了，
 * 于是发一条通知，至少让用户看得见、点得开。
 *
 * 第二个用途是「现在有没有人在看」（[isVisible]，2026-10-30）：车机远离 30 秒后要不要
 * 把进程退掉，取决于用户是不是正拿着手机看界面——正看着就不该被掐掉。
 */
object AppUiPresence {
    /** 最近一次 MainActivity 进入前台（onCreate / onNewIntent）的墙钟毫秒。 */
    @Volatile
    var lastBringUpAt: Long = 0L
        private set

    /**
     * 前台 Activity 计数（用计数而不是布尔：旋转时新旧 Activity 会短暂交叠，
     * 布尔值在那一瞬间会被旧实例的 onPause 误写成「不可见」）。
     */
    private val resumedCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 界面此刻是否在前台可见（用户正看着手机）。 */
    val isVisible: Boolean
        get() = resumedCount.get() > 0

    fun markBroughtUp() {
        lastBringUpAt = System.currentTimeMillis()
    }

    fun markResumed() {
        resumedCount.incrementAndGet()
    }

    fun markPaused() {
        if (resumedCount.decrementAndGet() < 0) resumedCount.set(0)
    }
}
