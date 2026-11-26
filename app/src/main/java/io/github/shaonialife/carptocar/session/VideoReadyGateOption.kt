package io.github.shaonialife.carptocar.session

import android.content.Context

/**
 * 「视频真送达才算这一轮连上」开关（默认关闭，对所有车型生效）。
 *
 * 借鉴 hypercar 的 `ReconnectBudget.videoReady()`：它的重连预算**只在原生管线真的
 * 写出过视频之后**才被承认健康（并且要稳 2 分钟才清零失败计数），而不是 RTSP 回 200
 * 就算数。我们原来的判据是 [SessionController] 里的 `sessionLive`——`wasSessionEstablished`
 * （RECORD 成功）即视为「已连上」，于是 `reconnectGate.clearAttempt()` 立刻把失败计数清零。
 *
 * 问题场景（奥迪 4580、宝马 MGU-81 都出现过）：SETUP/RECORD 全 200、车机给 dataPort、
 * 我们照发 H.264，**车机屏幕始终黑着**。这一轮其实是假成功，却把重连预算清了，
 * 下一轮又从头 3 秒退避开始——表现就是「每几秒连一次、一直连不上」，车机始终没有
 * 一次干净的收尾窗口（这与 `ReconnectGate` 类注释里 MGU-81 的重连风暴是同一类故障）。
 *
 * 打开后：会话建立**不再**自动清零；必须先由首帧门（[SessionController.startFirstFrameGate]）
 * 观察到本会话有帧真的送进车机通道，才算本轮成功、清零失败计数。黑屏轮次则保留计数，
 * 让退避按 3s → 10s → 30s 正常爬升，最后停在「不再自动重试」而不是无限空转。
 *
 * 关闭时 [SessionController] 完全走原判据（`sessionLive` 即清零），行为与改动前一致。
 */
object VideoReadyGateOption {
    private const val PREFS_NAME = "settings"
    private const val KEY = "video_ready_gate_v1"

    /** 进程内当前值。启动时 [load] 一次；界面拨动时 [save] 立即改字段。 */
    @Volatile
    var enabled: Boolean = false
        private set

    fun load(context: Context) {
        enabled =
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY, false)
    }

    fun save(
        context: Context,
        value: Boolean,
    ) {
        enabled = value
        context
            .applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, value)
            .apply()
    }

    val label: String
        get() = if (enabled) "开" else "关"

    /**
     * 会话收尾时**要不要**把重连失败计数清零。
     *
     * - 开关关：清（原行为，`sessionLive` 即算连上）。
     * - 开关开：只有 [videoReady]（本轮首帧门确认车机真收到画面）才清；黑屏轮次保留计数，
     *   让退避按 3s→10s→30s 正常累加，而不是每轮都被清零后重新从 3s 起算。
     */
    fun shouldClearAttempt(videoReady: Boolean): Boolean = shouldClearAttempt(videoReady, enabled)

    /** [shouldClearAttempt] 的纯函数版本（开关值显式传入），供单测直接覆盖三种组合。 */
    fun shouldClearAttempt(
        videoReady: Boolean,
        gateEnabled: Boolean,
    ): Boolean = !gateEnabled || videoReady

    val note: String
        get() =
            if (enabled) {
                "已开启：只有车机真的收到画面才算本轮连上。黑屏轮次不再清零重连次数，" +
                    "退避会正常爬升（避免反复重连把车机拖住）。下次连接起效。"
            } else {
                "默认关：会话建立（RECORD 成功）即视为连上。若出现「显示已连接但车机黑屏、" +
                    "且反复自动重连」，可开启此项排查。下次连接起效。"
            }
}
