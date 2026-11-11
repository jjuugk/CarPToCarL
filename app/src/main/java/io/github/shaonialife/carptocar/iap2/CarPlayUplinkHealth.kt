package io.github.shaonialife.carptocar.iap2

/**
 * 「车机端 CarPlay 服务是不是已经死了」的进程级判定。
 *
 * 现场形态（2026-11-11 三份别克日志）：第一轮连上、出画面，随后我们发出的一批
 * 抓包常量把车机的 WiFi/CarPlay 组件打崩——AirPlay 通道 `Connection reset`，
 * 6030/6000 端口从 ECONNREFUSED 开始连续拒绝。此后蓝牙 iAP2 还活着，每轮都能
 * 识别、MFi、发 `0x4300`，但车机**永远不再回 `0x4301`**，约 16 秒一轮无限重连。
 *
 * 这时的现象对用户是「连不上」，实际是「车机那半边没起来」。继续密集重试有两个坏处：
 * 刷屏把真正的原因淹掉；以及每轮都可能再打断车机正在进行的恢复。
 *
 * 判据刻意收紧到「**本机在这台车机上曾经拿到过 `0x4301`**」才计数：
 * - 从没连上过的车机（第一次配对、车机没开无线 CarPlay）不走这条路，那是另一个问题；
 * - 曾经成功过、现在连续两轮要不到，才说明「它本来是好的，现在坏了」。
 *
 * 另外给端口拒绝一条快通道：同一轮里已经出现事件口/数据口 `ECONNREFUSED`，
 * 一次落空即可判定——车机连监听都没有，不必再等第二轮。
 */
object CarPlayUplinkHealth {
    /** 连续多少轮要不到 `0x4301` 才判死（本机曾成功过才计数）。 */
    const val DEAD_ROUNDS = 2

    /** 车机端 CarPlay 已退出：重连应放慢到 [DEAD_RETRY_DELAY_MILLIS] 一轮。 */
    @Volatile
    var isDead: Boolean = false
        private set

    /** 判死之后的**最长**重连间隔：够长到不再刷屏，又不至于车机重启后一直连不上。 */
    const val DEAD_RETRY_DELAY_MILLIS = 60_000L

    /**
     * 判死之后的重连间隔阶梯：20s → 30s → 45s → 60s（之后固定 60s）。
     *
     * 旧实现判死后一律 60 秒，实测代价太大：用户当场熄火锁车重启车机，只要赶上
     * 刚睡下（最长 60s），这一轮就白等了。刚判死时先快一点复核（20s、30s），
     * 连续多轮仍要不到 0x4301 才认定「它真的不会自己好」，逐步拉长到 60s。
     *
     * 下限仍是 20s：必须高于车机的 `mediaDisconnectTimeout`（15s，见
     * `Iap2BluetoothClient.RETRY_DELAY_MILLIS`），否则每轮重连都会把车机那个
     * 「设备已移除」计时器续上，它永远不重建会话。
     */
    private val DEAD_RETRY_LADDER_MILLIS = longArrayOf(20_000L, 30_000L, 45_000L, 60_000L)

    private var consecutiveMisses = 0
    private var everReachedStartSession = false

    /** 判死之后已经睡过几轮（决定下一轮的间隔档位）。 */
    private var deadSleeps = 0

    /** 本轮（或本轮之后）是否见过端口拒绝——由 AirPlay 侧 [notePortRefused] 置位。 */
    private var portRefusedSinceSuccess = false

    /** 拿到 `0x4301`：这台车机的无线 CarPlay 确实能工作，一切恢复正常节奏。 */
    @Synchronized
    fun noteStartSession() {
        everReachedStartSession = true
        consecutiveMisses = 0
        deadSleeps = 0
        portRefusedSinceSuccess = false
        isDead = false
    }

    /**
     * 见过一次端口拒绝（事件口/数据口 `ECONNREFUSED`）。
     *
     * 单独记录而不是立刻判死：车机上一轮被硬断后清不干净也会短暂拒绝，它自己会好。
     * 只有和「这一轮要不到 0x4301」同时成立时才作为快通道证据。
     */
    @Synchronized
    fun notePortRefused() {
        if (everReachedStartSession) portRefusedSinceSuccess = true
    }

    /**
     * 一轮 iAP2 会话结束。
     *
     * @param gotStartSession 本轮是否拿到过 `0x4301`（含保活期晚到）
     * @return 本轮结束后是否判定为「车机端 CarPlay 已退出」
     */
    @Synchronized
    fun finishRound(gotStartSession: Boolean): Boolean {
        if (gotStartSession) {
            noteStartSession()
            return false
        }
        consecutiveMisses += 1
        if (!everReachedStartSession) return false
        // 端口已经拒绝过：车机连监听都没了，一轮就够。
        if (portRefusedSinceSuccess && consecutiveMisses >= 1) {
            isDead = true
        } else if (consecutiveMisses >= DEAD_ROUNDS) {
            isDead = true
        }
        return isDead
    }

    /** 用户主动重新启动连接时清零：给一次干净的重新判定机会。 */
    @Synchronized
    fun reset() {
        everReachedStartSession = false
        consecutiveMisses = 0
        deadSleeps = 0
        portRefusedSinceSuccess = false
        isDead = false
    }

    /**
     * 判死状态下本轮结束后该睡多久（阶梯见 [DEAD_RETRY_LADDER_MILLIS]）。
     *
     * 每调用一次推进一档，到顶后固定 [DEAD_RETRY_DELAY_MILLIS]；一旦拿到 `0x4301`
     * 或用户重新点连接就回到最短档——「车机刚重启好」这种情形应该被尽快接上。
     */
    @Synchronized
    fun nextDeadRetryDelayMillis(): Long {
        val delay = DEAD_RETRY_LADDER_MILLIS[deadSleeps.coerceIn(0, DEAD_RETRY_LADDER_MILLIS.size - 1)]
        if (deadSleeps < DEAD_RETRY_LADDER_MILLIS.size) deadSleeps += 1
        return delay
    }

    /** 给日志/提示用的一句话状态。 */
    @Synchronized
    fun describe(): String =
        when {
            isDead -> "车机端 CarPlay 已退出（连续 $consecutiveMisses 轮要不到 0x4301）"
            !everReachedStartSession -> "尚未在这台车机上建立过无线会话"
            consecutiveMisses > 0 -> "已连续 $consecutiveMisses 轮要不到 0x4301"
            else -> "正常"
        }
}
