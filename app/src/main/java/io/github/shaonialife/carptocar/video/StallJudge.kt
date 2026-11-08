package io.github.shaonialife.carptocar.video

/**
 * 画面停摆的**判据与分级干预策略**（纯逻辑，可单测）。
 *
 * 从 `CarLifeHijack` 的停摆看门狗里抽出来，只因为这段判定全是时间阈值与级别跳转，
 * 放在线程循环里既无法验证也容易在后续调整中改错方向。线程、日志、`requestVideoReinit`
 * 与 `client.stop()` 这些副作用仍留在看门狗里。
 *
 * ## 为什么是「分级」而不是一步重建
 *
 * 两条路代价差一个数量级：
 *
 *  - `requestVideoReinit()`：让引擎重建编码器，约 1 秒后吐 SPS+IDR，成本极低；
 *  - 重建会话（`client.stop()`）：引擎冷启动要 30~45 秒才出首个 IDR，用户黑屏
 *    实际被拉长，只是它**确实能救回来**。
 *
 * 所以先要关键帧、要不到再拆会话；而拆会话也要有退避——连续拆会把引擎永远按在
 * 热身期，比不干预还糟。
 */
internal object StallJudge {
    /** 判定停摆后多久请求重建编码器（ms）。 */
    const val REINIT_MILLIS = 8_000L

    /**
     * 停摆期间「持续轻推」的间隔（ms）：每 8 秒再要一次关键帧。
     *
     * 只在 8 秒那一刻推一次不够——2026-11-03 日志里引擎经常要推两三次才吐 IDR。
     */
    const val REINIT_RETRY_MILLIS = 8_000L

    /**
     * 停摆多久仍未恢复就强制重建会话（ms）。
     *
     * 真机日志里「只观测不干预」的停摆最长拖到 128 秒，而且每次自然恢复都伴随一次新会话
     * ——说明重建会话才是有效手段，那就主动做掉。
     */
    const val RECONNECT_MILLIS = 30_000L

    /** 强制重建阈值的上限（ms）：引擎冷启动要 30~45 秒才出首个 IDR。 */
    const val RECONNECT_MAX_MILLIS = 120_000L

    /** 干预动作。 */
    enum class Action {
        NONE,
        REINIT_ENCODER,
        REBUILD_SESSION,
    }

    /**
     * 距上一次收到视频帧过去多少毫秒；`-1` = 本轮还一帧都没收到。
     *
     * 「一帧都没收到」必须能区分出来：引擎刚起来那几秒没有帧是正常的，
     * 按「距今无穷大」判会立刻拆会话。
     */
    fun frameAgeMs(
        lastFrameAt: Long,
        now: Long,
    ): Long = if (lastFrameAt <= 0L) -1L else (now - lastFrameAt).coerceAtLeast(0L)

    /**
     * 这一次轮询该做什么。
     *
     * @param stalledMs 已停摆多久
     * @param interventions 已执行到哪一级：0 = 未干预，1 = 已请求重建编码器，2 = 已重建会话
     * @param reconnectDelay 本轮「重建会话」阈值（会随次数翻倍）
     * @param sinceReinitMs 距上次请求重建编码器多久（未请求过传 [Long.MAX_VALUE]）
     */
    fun nextAction(
        stalledMs: Long,
        interventions: Int,
        reconnectDelay: Long,
        sinceReinitMs: Long,
    ): Action =
        when {
            // 已经拆过会话：这一轮不再动手（连拆会把引擎永远按在热身期）
            interventions >= 2 -> Action.NONE
            // 一级：到点先要关键帧，成本最低
            interventions == 0 && stalledMs >= REINIT_MILLIS -> Action.REINIT_ENCODER
            // 二级：要不到就拆会话（阈值随次数翻倍，见 [nextReconnectDelay]）
            interventions == 1 && stalledMs >= reconnectDelay -> Action.REBUILD_SESSION
            // 中间态：每 [REINIT_RETRY_MILLIS] 再轻推一次
            interventions == 1 && sinceReinitMs >= REINIT_RETRY_MILLIS -> Action.REINIT_ENCODER
            else -> Action.NONE
        }

    /** 下一次「重建会话」阈值：翻倍，封顶 [RECONNECT_MAX_MILLIS]。 */
    fun nextReconnectDelay(current: Long): Long =
        (current * 2).coerceAtMost(RECONNECT_MAX_MILLIS)
}
