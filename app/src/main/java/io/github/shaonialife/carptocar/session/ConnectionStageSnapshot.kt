package io.github.shaonialife.carptocar.session

/**
 * 首页「连接阶段」卡片的**只读快照**。
 *
 * 参考 hypercar 的 `PipelineDiagnostics`，但口径按本项目实际能观测到的事实来：
 * 绿灯只表示「本机确认到这一步」，不代表车机已显示画面或已出声。统计字段取不到时
 * 用 null，界面显示「—」，绝不填一个看起来像真值的 0。
 */
data class ConnectionStageSnapshot(
    val active: Boolean = false,
    val hint: String = "尚未发起连接",
    /** 当前 ConnectBudget 阶段名（等端点 / 热点 / 配对 / SETUP / 首帧 / 推流）。 */
    val stage: String = "空闲",
    val recordAccepted: Boolean = false,
    val resourcesAccepted: Boolean = false,
    val controlReady: Boolean = false,
    val wifiReady: Boolean = false,
    val videoChannelReady: Boolean = false,
    val audioChannelReady: Boolean = false,
    /** null = 还没有 AirPlay 客户端，无法判断归属。 */
    val screenOwnedByPhone: Boolean? = null,
    val displaySize: String? = null,
    val videoFormat: String? = null,
    val writtenFrames: Long = 0L,
    val sourceFrames: Long = 0L,
    val uiRequests: Long = 0L,
    val screenRebuilds: Long = 0L,
    val audioPackets: Long = 0L,
    val signalPackets: Long = 0L,
    val timingRequests: Long = 0L,
    val timingReplies: Long = 0L,
    val timingReplyAgeMs: Long? = null,
) {
    companion object {
        val EMPTY = ConnectionStageSnapshot()
    }
}
