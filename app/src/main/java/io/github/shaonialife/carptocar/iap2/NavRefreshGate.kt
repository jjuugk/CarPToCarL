package io.github.shaonialife.carptocar.iap2

/**
 * 导航引导「上媒体卡片」的刷新节流闸门（纯逻辑、可单测、不碰任何 Android API）。
 *
 * ## 为什么需要它
 *
 * 导航期间的媒体卡片由 `SessionController.pushNowPlayingGuide()` 维护：内容一变
 * （转向名 + **剩余距离** + 路名）就发一条 `0x5001`。距离每变一次就变一次内容，
 * CarLife 引擎与高德各按 1Hz 往上推，车机于是每秒收一条——这条路径上原本
 * **没有任何频率上限**。
 *
 * 车机侧要为每条 `0x5001` 处理一次卡片刷新（并按 artwork 编号查缓存），
 * 密集刷新会让它来不及处理。所以需要一个可调的最小发送间隔。
 *
 * ## 语义
 *
 * - `intervalMillis <= 0`：不限制，`shouldEmitNow` 恒 `true`（= 改动前行为）。
 * - `forced = true`：直通，不看窗口。导航结束要清卡片时必须走这条，
 *   否则卡片会多挂一秒旧箭头。
 * - 从未发过（`emitted == false`）：直通。本次导航的第一版不该等窗口。
 *
 * 窗口内要不要保留中间版本由调用方决定——本类只回答「现在能不能发」。
 */
class NavRefreshGate {
    /** 最小发送间隔（毫秒）。0 或负数 = 不限制。 */
    @Volatile
    var intervalMillis: Long = 0L

    private var lastEmitAtMs = 0L
    private var emitted = false

    /**
     * @param nowMs 单调时钟（毫秒）。调用方负责取，便于单测注入。
     * @param forced 强制直通（清除帧等）。
     * @return `true` = 现在就发；`false` = 落在窗口内，调用方应缓存到 [dueDelayMs] 后再发。
     */
    @Synchronized
    fun shouldEmitNow(
        nowMs: Long,
        forced: Boolean = false,
    ): Boolean {
        if (forced || !emitted) return true
        val interval = intervalMillis
        if (interval <= 0L) return true
        return nowMs - lastEmitAtMs >= interval
    }

    /** 记录「刚刚发过一版」。必须在真正扇出之后调用。 */
    @Synchronized
    fun markEmitted(nowMs: Long) {
        emitted = true
        lastEmitAtMs = nowMs
    }

    /**
     * 距下一次可发还差多少毫秒。
     *
     * `0` = 现在就可以（未发过 / 不限 / 窗口已过）。调用方用它决定尾发任务的延迟。
     */
    @Synchronized
    fun dueDelayMs(nowMs: Long): Long {
        if (!emitted) return 0L
        val interval = intervalMillis
        if (interval <= 0L) return 0L
        return (interval - (nowMs - lastEmitAtMs)).coerceAtLeast(0L)
    }

    /** 会话退役 / 导航结束时清掉窗口，让下一轮的首帧重新走直通。 */
    @Synchronized
    fun reset() {
        emitted = false
        lastEmitAtMs = 0L
    }
}
