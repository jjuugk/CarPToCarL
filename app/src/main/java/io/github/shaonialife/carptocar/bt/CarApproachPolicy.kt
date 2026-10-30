package io.github.shaonialife.carptocar.bt

/**
 * 「靠近车机自动启动」的判定逻辑（2026-10-30）。
 *
 * 抽成纯函数是为了单测：真实触发靠系统 ACL 广播，时序与线程都不可控，
 * 能测的只有「什么情况下该触发/不该触发」这一段。
 *
 * 静默约束：只用系统已经在投递的蓝牙 ACL 事件（车机靠近时车机自己会回连手机），
 * **不扫描、不轮询、不主动探测**——扫描会弹系统提示并持续占用射频。
 */
object CarApproachPolicy {
    /** 同一次靠近最多触发一次：车机偶发断连重连时不要反复拉起 App。 */
    const val RETRIGGER_INTERVAL_MILLIS = 90_000L

    /** 用户手动断开/取消后，同一段停车期间不再自动拉起（下车断链时自动解除）。 */
    const val SUPPRESS_AFTER_MANUAL_STOP_MILLIS = 10 * 60_000L

    enum class Decision {
        /** 命中：该拉起 App 并开始连接。 */
        TRIGGER,

        /** 开关关着。 */
        DISABLED,

        /** 没指定目标车机——不做任何事，避免连到别的已配对设备。 */
        NO_TARGET,

        /** 事件里的设备不是目标车机（耳机/手表/别人的手机…）。 */
        NOT_TARGET,

        /** 会话已经在跑（或正在连接），不需要再拉起。 */
        SESSION_RUNNING,

        /** 用户刚手动断开/取消——这一轮停车期间保持安静。 */
        SUPPRESSED,

        /** 同一次靠近已经触发过。 */
        THROTTLED,
    }

    /**
     * 「刚断开过」的抑制是否仍然有效。
     *
     * 抑制原本只在 ACL 断开时清除，可「人在车上把 App 关掉」之后蓝牙链路一直连着、
     * 不会再有 ACL_DISCONNECTED——标记就永远不清，用户还在车里却再也等不到自动拉起。
     * 所以给它一个 [SUPPRESS_AFTER_MANUAL_STOP_MILLIS] 的窗口：窗口内安静（防
     * 「断了又立刻被拉起」的死循环），窗口外自然失效。
     *
     * @param suppressedAt 置位时刻（墙钟毫秒，0 = 旧版本写的标记，按「仍有效」处理）
     */
    fun suppressionActive(
        suppressed: Boolean,
        suppressedAt: Long,
        now: Long,
    ): Boolean {
        if (!suppressed) return false
        if (suppressedAt <= 0L) return true
        val elapsed = now - suppressedAt
        // 系统时间被往回调时 elapsed 为负：当作刚置位，继续安静
        return elapsed in 0 until SUPPRESS_AFTER_MANUAL_STOP_MILLIS
    }

    /**
     * @param eventAddress 系统 ACL 广播里带的设备地址（可能为空）
     * @param targetAddress 用户在「目标车机」里选定的地址（null = 未指定）
     * @param sessionRunning 已有会话在跑或正在连接
     * @param suppressed 用户手动断开后的抑制窗口内
     * @param lastTriggerAt 上次触发时刻（墙钟毫秒，0 = 从未触发）
     */
    fun onAclConnected(
        eventAddress: String?,
        targetAddress: String?,
        enabled: Boolean,
        sessionRunning: Boolean,
        suppressed: Boolean,
        lastTriggerAt: Long,
        now: Long,
    ): Decision {
        if (!enabled) return Decision.DISABLED
        if (targetAddress.isNullOrBlank()) return Decision.NO_TARGET
        val address = eventAddress?.takeIf { it.isNotBlank() } ?: return Decision.NOT_TARGET
        if (!address.equals(targetAddress, ignoreCase = true)) return Decision.NOT_TARGET
        if (sessionRunning) return Decision.SESSION_RUNNING
        if (suppressed) return Decision.SUPPRESSED
        if (lastTriggerAt > 0L) {
            // 用 `<` 而不是区间判断：系统时间被往回调整时 elapsed 为负，
            // 同样按「刚触发过」处理，避免一次靠近连发多次。
            val elapsed = now - lastTriggerAt
            if (elapsed < RETRIGGER_INTERVAL_MILLIS) return Decision.THROTTLED
        }
        return Decision.TRIGGER
    }
}
