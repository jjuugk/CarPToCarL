package io.github.shaonialife.carptocar.airplay

/**
 * 「复用网络 ≠ 复用认证」的强制重做策略（纯逻辑，无 Android 依赖，便于单测）。
 *
 * ## 为什么要它
 *
 * 参考实现 OcarPlay（CarPlay 接收端）在复用已选 WLAN 时打的是
 * `CURRENT_WIFI_REUSE verified_previous_transport=true fresh_authentication_required=true
 * network_switch=false`，并配一条 `RELEASE_SESSION_BLOCKED no_fresh_integrity_permit`：
 *
 *  - **只复用网络**：L2 连上不等于对端还是上次那个状态，认证一律重新做；
 *  - **连续失败要换姿态**：连续两轮没建立起会话，就不能再拿「上一次的结论」当依据
 *    （典型是 [PairingMemory] 里那条「车机确定性拒绝配对」的 15 分钟记忆——
 *    车机侧删了配对记录/复位之后，我们还在按旧结论跳过认证，于是越连越连不上）。
 *
 * 所以这里只做一件事：数**同一个接收端**的连续失败轮次，到阈值就让下一轮
 * 「认证重做」——调用方据此忽略配对记忆、强制重走 pair-setup（见
 * `SessionController` 的配对策略分派）。
 *
 * ## 边界
 *
 *  - 换接收端（key 变了）立刻归零：换了另一台车机，就是全新的一套状态；
 *  - 任何一轮建立成功都归零，且清掉待执行的「重做」标记；
 *  - 触发后计数归零（不是永久强制）：重做一轮若又失败，再攒两轮才会再触发，
 *    避免在一台彻底连不上的车机上每轮都多花 120s 配对窗口。
 *
 * key 取 [PairingMemory.receiverKey] 的同一种口径（公钥 → deviceid → SSID），
 * 两处必须一致，否则「记忆跳过了配对」和「失败计数」会各算各的。
 */
class FreshAuthPolicy(
    /** 连续失败几轮后强制重做认证。2 = 与 OcarPlay 的「两次之后必须重做」一致。 */
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
) {
    private var receiverKey: String? = null
    private var consecutiveFailures = 0
    private var redoPending = false

    /** 一轮开始（拿到本轮接收端身份）。换设备即归零。 */
    @Synchronized
    fun onAttempt(key: String) {
        if (receiverKey != key) {
            receiverKey = key
            consecutiveFailures = 0
            redoPending = false
        }
    }

    /**
     * 记录一轮结果。
     *
     * @return true = 这一轮失败刚好把计数推到阈值，**下一轮必须重做认证**。
     */
    @Synchronized
    fun onResult(
        key: String,
        established: Boolean,
    ): Boolean {
        onAttempt(key)
        if (established) {
            consecutiveFailures = 0
            redoPending = false
            return false
        }
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            consecutiveFailures = 0
            redoPending = true
            return true
        }
        return false
    }

    /** 本轮是否必须重做认证（一次性：读到就消费掉）。 */
    @Synchronized
    fun consumeRedo(key: String): Boolean {
        onAttempt(key)
        val redo = redoPending
        redoPending = false
        return redo
    }

    /** 当前连续失败次数（诊断/测试用）。 */
    @Synchronized
    fun failuresFor(key: String): Int {
        onAttempt(key)
        return consecutiveFailures
    }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 2
    }
}
