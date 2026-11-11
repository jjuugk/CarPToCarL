package io.github.shaonialife.carptocar.net

/**
 * 「这条热点还能不能直接复用」的判定：把 [CarNetworkReadiness] 的状态翻译成**下一步动作**。
 *
 * 为什么单独抽一层：复用判断原来散在 SessionController 的几处 `if (boundCarSsid == ssid
 * && currentNetwork != null)`，它信的是**我们自己记的账**（`boundCarSsid`），而不是系统
 * 现在的样子。系统在下面悄悄换过网络（热点掉了、被 ROM 踢回家里那条）时，账本还是
 * 车机热点、`currentNetwork` 也还非空，于是我们「复用」的其实是家里那条 WiFi——
 * 之后 mDNS、ctrl 广播、建链全打在错的网络上，日志里只看到「已绑定车机热点，跳过重复绑定」。
 *
 * 现在的规矩：
 *  - 复用之前必须 `expected == bound`（读不到名字时至少要有地址且确实绑着）；
 *  - 「网络在但没就绪」→ [Decision.WaitForAddress]，等地址，**绝不重新发 specifier**
 *    （`joinCarPlayNetwork` 第一行是 `release()`，会把正在建立的网络拆掉）；
 *  - 「已关联未绑定」→ [Decision.AdoptNow]，零交互绑一下；
 *  - 「真的掉了」/「不是这条」→ [Decision.Rejoin]，才走完整入网。
 */
object NetworkReusePolicy {
    /** 等 DHCP 给出地址的上限。超过就别等了，交给完整入网流程重来。 */
    const val WAIT_FOR_ADDRESS_MILLIS = 3_000L

    sealed interface Decision {
        /** 一句话「为什么这么判」，直接进日志（与 UI 共用）。 */
        val reason: String

        /** 直接复用当前绑定的网络：已就绪且（能核对时）就是目标热点。 */
        data class Reuse(
            override val reason: String,
        ) : Decision

        /** 系统已关联目标热点、只是进程没绑——[WifiCarPlayConnector.adoptAssociated] 一下即可。 */
        data class AdoptNow(
            override val reason: String,
        ) : Decision

        /** 网络在但没地址：等它就绪，别重新入网（重新入网会 release 掉这条）。 */
        data class WaitForAddress(
            override val reason: String,
            val waitMillis: Long = WAIT_FOR_ADDRESS_MILLIS,
        ) : Decision

        /** 真的掉了 / 不是目标热点：只能走完整入网。 */
        data class Rejoin(
            override val reason: String,
        ) : Decision
    }

    fun decide(observation: CarNetworkReadiness.Observation): Decision =
        when (observation.state) {
            CarNetworkReadiness.State.READY -> Decision.Reuse(observation.detail)
            CarNetworkReadiness.State.ADOPT -> Decision.AdoptNow(observation.detail)
            CarNetworkReadiness.State.NOT_READY ->
                Decision.WaitForAddress(observation.detail, WAIT_FOR_ADDRESS_MILLIS)
            CarNetworkReadiness.State.WRONG_NETWORK -> Decision.Rejoin(observation.detail)
            CarNetworkReadiness.State.ABSENT -> Decision.Rejoin(observation.detail)
        }

    /** 这条判定值不值得写进日志（`Reuse` 的常态复用不刷屏，由调用方自己补一行）。 */
    fun shouldLog(decision: Decision): Boolean = decision !is Decision.Reuse

    /**
     * 一行日志：`WIFI 复用判定：网络在但没就绪… → 等地址（最长 3000ms）`。
     *
     * 状态与动作都写出来，是因为排障时最想知道的正是「当时系统认为是什么状态、
     * 我们因此做了什么」——只写动作的话，事后仍然要猜当时读到了什么。
     */
    fun describe(decision: Decision): String {
        val action =
            when (decision) {
                is Decision.Reuse -> "直接复用"
                is Decision.AdoptNow -> "绑定即可用（不重新入网）"
                is Decision.WaitForAddress -> "等地址（最长 ${decision.waitMillis}ms，不重新入网）"
                is Decision.Rejoin -> "重新入网"
            }
        return "WIFI 复用判定：${decision.reason} → $action"
    }
}
