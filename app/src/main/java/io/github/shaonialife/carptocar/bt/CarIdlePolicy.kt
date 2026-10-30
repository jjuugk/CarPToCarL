package io.github.shaonialife.carptocar.bt

/**
 * 「车机走远了，该不该收摊」的判定（2026-10-30）。
 *
 * 抽成纯函数与 [CarApproachPolicy] 同源：真正触发靠系统 ACL 广播 + 延时闹钟，
 * 时序不可控；能测的只有「到点这一刻到底关不关」。
 *
 * 30 秒是刻意选的值：车机熄火/人下车时蓝牙会断，但**断一下又回连**很常见
 * （车机重启、HFP 重建、走到车尾信号弱）。等 30 秒就是给这段抖动留窗口——
 * 期间只要重新连上就取消计时，不会把正在恢复的会话误杀。
 *
 * ## 为什么判「在推流」而不是「会话在跑」
 *
 * `isSessionRunning()` 在「点过启动连接、蓝牙服务正监听等车机回连」时也一直是 true，
 * 可以持续好几分钟。拿它当阻断条件，收摊就永远不会触发（功能看起来完全没生效）。
 * 真正需要保护的是**画面正在车机上跑**：CarPlay 的镜像走 WiFi，蓝牙 ACL 只是
 * 控制/配对通道，蓝牙模块重启时画面可能还在——这种情况绝不能把进程掐掉。
 */
object CarIdlePolicy {
    /** 蓝牙断开后等这么久再收摊。 */
    const val IDLE_TIMEOUT_MILLIS = 30_000L

    enum class Verdict {
        /** 该关了：没链路、没画面、用户也没在看界面。 */
        CLOSE,

        /** 车机又连上了（30 秒内回连）——保留会话。 */
        STILL_CONNECTED,

        /** 蓝牙断了但画面还在送（镜像走 WiFi）——不杀，用户正在用。 */
        STREAMING,

        /** 用户正开着界面在看（前台可见）——停在会话这一层，不关进程。 */
        UI_VISIBLE,
    }

    /**
     * @param aclConnected 目标车机的 ACL 链路当前是否连着（由广播维护，跨进程存活）
     * @param streaming 画面是否真的在往车机送
     * @param uiVisible 界面此刻是否在前台（用户正看着手机时不该把进程掐掉）
     */
    fun onIdleTimeout(
        aclConnected: Boolean,
        streaming: Boolean,
        uiVisible: Boolean,
    ): Verdict =
        when {
            aclConnected -> Verdict.STILL_CONNECTED
            streaming -> Verdict.STREAMING
            uiVisible -> Verdict.UI_VISIBLE
            else -> Verdict.CLOSE
        }
}
