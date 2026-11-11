package io.github.shaonialife.carptocar.net

/**
 * 车机热点这条 WiFi 现在**到底处于什么状态**——把「网络在但没就绪」和「真的掉了」
 * 分成两种，而不是像以前那样一律记成「绑定当前 WiFi 失败（手机没挂在任何 WiFi 上）」。
 *
 * 为什么非要拆开（2026-11-11 夜间那几轮别克的教训）：
 * 日志里只剩一句「没绑定成功」，三种完全不同的现场在日志里长得一模一样——
 *  1. 手机真没连任何 WiFi（热点被关 / 离开范围）→ 该重新入网；
 *  2. 手机**已经挂在车机热点上**，但进程还没绑过去（系统关联好了，只差一句
 *     `bindProcessToNetwork`）→ 明明零成本就能复用，却重新发 specifier，
 *     把系统那张关联表连同回调一起 `release()` 掉，白等一轮超时；
 *  3. 关联上了、DHCP 还没回来（网络在列表里但没有地址）→ 这时候发 specifier 同样是
 *     自残：`joinCarPlayNetwork` 第一行就是 `release()`，把正在建立的网络拆了。
 *
 * 判读只依赖一份纯数据快照（[CarNetworkSnapshot]），读取动作留给
 * [WifiCarPlayConnector.networkSnapshot]，因此这里可以脱离 Android 单测
 * （见 `CarNetworkReadinessTest` / `NetworkReusePolicyTest`）。
 */
data class CarNetworkSnapshot(
    /** 目标热点名；null = 这一轮本来就不按 SSID 判断（读不到 SSID 的探测路径）。 */
    val expectedSsid: String?,
    /** 进程当前绑定的那条网络的 SSID；null = 没绑，或者读不到名字。 */
    val boundSsid: String?,
    /** 系统当前关联的 WiFi 的 SSID；null = 没连，或者读不到名字。 */
    val associatedSsid: String?,
    /** 进程有没有绑着一条 WiFi 网络（`bindProcessToNetwork` 是否落在一条真实网络上）。 */
    val hasBoundNetwork: Boolean,
    /** 绑着的那条网络拿到地址了吗（关联 + DHCP 是否走完，能不能建 socket）。 */
    val hasAddress: Boolean,
    /** 系统关联着的那条目标热点是否也已经有地址（决定「直接 adopt」还是「再等等」）。 */
    val associatedReady: Boolean = false,
    val localIpv4: String? = null,
    val gateway: String? = null,
    val interfaceName: String? = null,
) {
    /** 有没有「某条 WiFi 网络」存在（不管就绪与否）。 */
    val networkPresent: Boolean get() = hasBoundNetwork || associatedSsid != null

    /** 任何 SSID 都读不到——缺「附近的设备 / 位置」权限时的典型形态。 */
    val ssidUnknown: Boolean get() = boundSsid == null && associatedSsid == null
}

object CarNetworkReadiness {
    /**
     * 五种状态。前三种以前在日志里全是同一句「绑定失败」，这正是要拆开的原因。
     */
    enum class State(
        /** 一句话结论（日志 / UI 共用同一套措辞）。 */
        val label: String,
    ) {
        /** 已绑定、有地址，可以直接建 socket。 */
        READY("已就绪"),

        /** 系统已关联在目标热点上，进程还没绑过去——只差一次 bind，零交互。 */
        ADOPT("已关联未绑定（差一次绑定）"),

        /** 网络在（绑着或关联着），但拿不到地址：DHCP 没走完 / 只有链路本地。 */
        NOT_READY("网络在但没就绪（没有地址）"),

        /** 有 WiFi，但不是目标热点（典型：家里/公司那条还连着）。 */
        WRONG_NETWORK("网络在但不是目标热点"),

        /** 真的没有可用 WiFi——热点关了 / 离开范围。 */
        ABSENT("没连任何 WiFi"),
    }

    /**
     * 一次判读的结论。
     *
     * @param detail 给日志看的一句话，必须写清「绑的是哪条、目标是谁」
     */
    data class Observation(
        val state: State,
        val detail: String,
        val snapshot: CarNetworkSnapshot,
    ) {
        val ready: Boolean get() = state == State.READY
    }

    fun inspect(snapshot: CarNetworkSnapshot): Observation {
        val expected = snapshot.expectedSsid
        val bound = snapshot.boundSsid
        val associated = snapshot.associatedSsid
        val boundReady = snapshot.hasBoundNetwork && snapshot.hasAddress

        if (boundReady) {
            return when {
                // 这一轮不按 SSID 判断（读不到名字的探测路径）：有绑定有地址就是最好的可用状态
                expected == null ->
                    ready(snapshot, "已绑定${ssidLabel(bound)}（目标不按 SSID 判定，有地址即可用）")
                bound == null ->
                    ready(snapshot, "已绑定一条有地址的 WiFi，但读不到它的名字——无法核对是不是 \"$expected\"")
                bound == expected -> ready(snapshot, "已绑定 \"$bound\"")
                else -> Observation(
                    State.WRONG_NETWORK,
                    "绑着的是 \"$bound\"，目标是 \"$expected\"——网络在但不是车机热点",
                    snapshot,
                )
            }
        }

        // 到这里：绑着的那条没地址（或压根没绑）
        if (expected != null && associated == expected) {
            return if (snapshot.associatedReady) {
                Observation(
                    State.ADOPT,
                    "系统已关联 \"$expected\"，但进程没绑过去——绑定即可用，不必重新入网",
                    snapshot,
                )
            } else {
                Observation(
                    State.NOT_READY,
                    "系统已关联 \"$expected\"，但还没拿到地址（DHCP 未完成）——等它，别重新入网",
                    snapshot,
                )
            }
        }
        if (snapshot.hasBoundNetwork) {
            return Observation(
                State.NOT_READY,
                "绑着${ssidLabel(bound)}，但这条网络没有地址（DHCP 未完成 / 只有链路本地）——等它，别重新入网",
                snapshot,
            )
        }
        if (associated != null) {
            return Observation(
                State.WRONG_NETWORK,
                "系统连在 \"$associated\" 上，不是目标${if (expected == null) "" else " \"$expected\""}",
                snapshot,
            )
        }
        return Observation(
            State.ABSENT,
            "手机上没有任何 WiFi 网络${if (expected == null) "" else "（目标 \"$expected\"）"}——" +
                "热点被关闭 / 手机已离开范围",
            snapshot,
        )
    }

    private fun ready(
        snapshot: CarNetworkSnapshot,
        detail: String,
    ): Observation = Observation(State.READY, detail, snapshot)

    private fun ssidLabel(ssid: String?): String = if (ssid == null) "一条 WiFi" else " \"$ssid\""

    /** 一行摘要（UI / 状态行用）：`READY · 已绑定 "BUICK_3767"`。 */
    fun summarize(observation: Observation): String = "${observation.state.label} · ${observation.detail}"
}
