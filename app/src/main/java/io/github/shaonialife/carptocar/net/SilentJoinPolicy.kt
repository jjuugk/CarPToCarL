package io.github.shaonialife.carptocar.net

/**
 * 「先静默、连续失败几次再降级」的决策器（纯逻辑，无 Android 依赖，便于单测）。
 *
 * ## 为什么需要
 *
 * 静默入网（[WifiCarPlayConnector.joinSilentlyBySuggestion]）是不弹任何框的唯一路径，
 * 代价是：WiFi 建议**没被授权过**时，这段时间一定是白等——系统不会自己连，我们却要
 * 等满窗口才降级。车机（BMW iD9 实测）只等约 27 秒就拆蓝牙链，每轮白等十几秒等于
 * 把整个窗口吃光。
 *
 * 所以：给静默路径一个**有限次数的预算**。
 *  - 第一次：登记建议后系统可能正在弹授权通知，值得等；
 *  - 第二次：接住「刚刚被授权」的那一下；
 *  - 之后：直接走确认框路径，不再浪费时间。
 *
 * 一旦静默成功过一次（[recordResult] 传 true），计数归零——之后每一轮都先走静默，
 * 因为它已经被证明可用，而且通常 1~3 秒就返回。
 *
 * 换 SSID 时预算重置：换了另一台车机，就是全新的一套建议，值得重新试。
 */
class SilentJoinPolicy(
    private val maxConsecutiveMisses: Int = 2,
) {
    private var ssid: String? = null
    private var misses: Int = 0

    /** 本轮还要不要先试静默。 */
    @Synchronized
    fun shouldTrySilently(ssid: String): Boolean {
        switchIfNeeded(ssid)
        return misses < maxConsecutiveMisses
    }

    /** 记录一次静默尝试的结果（[joined] = 是否真的连上了）。 */
    @Synchronized
    fun recordResult(
        ssid: String,
        joined: Boolean,
    ) {
        switchIfNeeded(ssid)
        misses = if (joined) 0 else misses + 1
    }

    /** 当前连续失败次数（诊断/测试用）。 */
    @Synchronized
    fun missesFor(ssid: String): Int {
        switchIfNeeded(ssid)
        return misses
    }

    private fun switchIfNeeded(ssid: String) {
        if (this.ssid != ssid) {
            this.ssid = ssid
            misses = 0
        }
    }
}
