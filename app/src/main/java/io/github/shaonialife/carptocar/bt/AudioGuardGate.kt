package io.github.shaonialife.carptocar.bt

/**
 * [BtProfiles.guardAudioProfiles] 的收工判定（纯逻辑，便于单测）。
 *
 * ## 为什么单独抽出来（2026-11-19）
 *
 * 原来这四个条件直接写在 `SessionController.startAudioProfileGuard()` 的
 * `shouldStop` lambda 里，只能回答「要不要停」，回答不了「**因为哪个条件停**」。
 * 而实际排障时这两者的区别很关键：
 *
 * - 「用户断开」停止 = 正常收场；
 * - 「iAP2 bring-up / 端点拿到 / 已在推流」停止 = 车机已经认可我们了，守卫不该再动蓝牙。
 *
 * 如果后续日志里仍然出现会话已激活后的 HFP/A2DP 连断，只有一个可能：又有一轮
 * `connect()` 重新拉起了守卫，或根本不是我们在断。判定与原因都显式化，日志才自证。
 *
 * 顺序即优先级：用户主动断开最高——那时 `iap2Ready` 可能还是上一轮的残留 true，
 * 若不先判它，日志会误报成「iAP2 已 bring-up」。
 */
object AudioGuardGate {
    /**
     * @return 收工原因；`null` = 会话还没起来，守卫该继续盯。
     */
    fun stopReason(
        sessionWanted: Boolean,
        iap2Ready: Boolean,
        sessionActive: Boolean,
        streaming: Boolean,
    ): String? =
        when {
            !sessionWanted -> "用户已断开"
            iap2Ready -> "iAP2 已 bring-up"
            sessionActive -> "CarPlay 端点已拿到"
            streaming -> "已在推流"
            else -> null
        }
}
