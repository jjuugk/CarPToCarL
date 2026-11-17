package io.github.shaonialife.carptocar.airplay

/**
 * 两条「2026-11-06 实车踩出来的」纯策略。
 *
 * 为什么单独拎出来：它们都是「一次晚到报文 / 一个错误码 → 整轮连接报废」的判定点，
 * 出错时日志看起来完全正常（配对失败、明文兜底、锁 15 分钟全是既有文案），
 * 只有把规则写成纯函数才测得出来。现场复现见
 * `CarPToCarL-log-20261106-164713.txt` 16:46:38 ~ 16:46:38.734。
 */
internal object AirPlaySessionPolicy {

    /**
     * 端点热更新（0x4301 晚到 / 配对期间 mDNS 刷新）时，能不能顺手关掉当前控制通道。
     *
     * **有交换在飞就不能关**：`close()` 会把等待中的 CSeq 直接判死。配对期间尤其致命——
     * 2026-11-06 16:46 的现场：
     * ```
     * 16:46:38.164  iAP2 0x4301 晚到
     * 16:46:38.170  端点更新（0x4301 晚到）…        ← 这里 close()
     * 16:46:38.170  控制通道关闭——唤醒 1 个待响应请求
     * 16:46:38.171  pair-setup 失败（控制通道已关闭（CSeq=2 POST /pair-setup））
     * ```
     * M1→M3 中间被掐断 ⇒ 车机对重试回 Error=3（退避）⇒ 我们抢先 pair-verify 拿到
     * Error=2 ⇒ 被记成「车机确定性拒绝」⇒ 锁 15 分钟 + 降级明文会话（4 秒后被车机拆链）。
     *
     * 配对循环每轮起步前自己会 `close()`，所以配对期间只改端点字段、下一轮自然生效。
     */
    fun deferEndpointClose(
        pairingInProgress: Boolean,
        pendingRequests: Int,
    ): Boolean = pairingInProgress || pendingRequests > 0

    /**
     * 声明 CARPLAY_CONTROL(bit37) 的车机等不到本轮 ctrl 邀请时，能不能按已发现的 mDNS
     * 端点放行直连（而不是 4 秒就整轮放弃）。
     *
     * 背景（2026-11-17 BMW 28041 / G38-NBTevo，1.2.64 连不上、1.2.32 能连）：
     * `c12d30e` 给 `[awaitCtrlInvite]` 加了门控——bit37 车机只认本轮新邀请，连 mDNS
     * 发现端点都不放行，怕「抢在车机打开 AirPlay 窗口前连接」。这台车恰好声明 bit37，
     * 却从不下发 0x4301、也不发 ctrl 邀请，mDNS 端点却是确定的 `172.16.224.1:7000`，
     * 于是 11 轮全部卡死在等邀请。
     *
     * 判据不看「车机的声明」（会撒谎）也不看「有没有 mDNS 记录」（可能过期），只看
     * **端点端口此刻实测可不可连**：连得上 = 车机 AirPlay 窗口确实开着，放行直连不会
     * 抢跑，正是门控想放行却又怕误判的那种情形；连不上 = 窗口没开，继续等邀请，
     * 保住 `c12d30e` 要防的行为。
     *
     * @param receiverControlled 车机是否声明 bit37；false 的车机本就不走这道门控，
     *   返回 true 表示「按既有行为放行」，不改变它。
     * @param endpointReachable mDNS 端点的 AirPlay 端口（默认 7000）TCP 实测可连。
     */
    fun allowReceiverControlledFallback(
        receiverControlled: Boolean,
        endpointReachable: Boolean,
    ): Boolean = !receiverControlled || endpointReachable
}

/** pair-setup 拿到错误码之后的下一步。 */
internal enum class PairSetupNext {
    /** 车机已存我们的公钥（Error=2）或 403：换新连接直接 pair-verify。 */
    PAIRED_FAST_PATH,

    /** 确定性拒绝且不值得走快路径（MAX_PEERS / MAX_TRIES）：本轮停止重试。 */
    TERMINAL,

    /** 退避 / 忙 / 暂不可用：等一会重试**同一阶段**，不要抢先做 pair-verify。 */
    RETRY_SAME_STAGE,

    /** 其余（含规范外私有码）：按既有行为继续 pair-verify。 */
    CONTINUE_VERIFY,
}

/**
 * pair-setup 失败后的分派规则（纯函数）。
 *
 * 关键一条是 [PairSetupNext.RETRY_SAME_STAGE]：`Error=3`（退避）意味着车机刚把自己的
 * SRP 状态机搅乱（典型诱因就是我们上一轮把 pair-setup 掐断在半路），这时候紧接着发
 * pair-verify 只会拿到 `Error=2`，而 `Error=2` 在 [PairingPolicy.isDeterministic] 里是
 * **确定性失败**——于是「自己打断」被记成「车机不认这台手机」，写进 15 分钟记忆并降级
 * 明文会话。先退避再重试同一阶段，才能让车机把状态收干净。
 *
 * @param backoffAlreadyRetried 本轮已经为退避重试过一次：退避只多给一次机会，
 *   免得在一台「其实已配对、只是回退避码」的车机上空转整个 120s 窗口。
 */
internal fun pairSetupNext(
    code: PairingErrorCode?,
    backoffAlreadyRetried: Boolean,
): PairSetupNext =
    when {
        PairingPolicy.shouldTryPairedFastPath(PairingStage.PAIR_SETUP, code) -> PairSetupNext.PAIRED_FAST_PATH
        PairingPolicy.isDeterministic(PairingStage.PAIR_SETUP, code) -> PairSetupNext.TERMINAL
        code == PairingErrorCode.BACKOFF ||
            code == PairingErrorCode.BUSY ||
            code == PairingErrorCode.UNAVAILABLE ->
            if (backoffAlreadyRetried) PairSetupNext.CONTINUE_VERIFY else PairSetupNext.RETRY_SAME_STAGE
        else -> PairSetupNext.CONTINUE_VERIFY
    }
