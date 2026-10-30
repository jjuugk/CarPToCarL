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
 */
object AppUiPresence {
    /** 最近一次 MainActivity 进入前台（onCreate / onNewIntent）的墙钟毫秒。 */
    @Volatile
    var lastBringUpAt: Long = 0L
        private set

    fun markBroughtUp() {
        lastBringUpAt = System.currentTimeMillis()
    }
}
