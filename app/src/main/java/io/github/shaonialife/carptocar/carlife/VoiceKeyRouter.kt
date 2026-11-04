package io.github.shaonialife.carptocar.carlife

/**
 * 车机语音键 → CarLife 硬键的状态机（1.2.22）。
 *
 * ## 为什么有这个文件
 *
 * 1.2.21 把语音键接在 iAP2 `0x6802 AccessoryHIDReport` 的 Consumer `0xCF` 上。
 * 回看手机上 2026-11-03 08:52 那份日志，**这条通路在这台车上一次都没走到**：
 * 日志里没有任何 `0x6802` 报文明细、没有任何 `0xCF`，而所有 `/info` 里下发的
 * HID 描述符（Consumer 页那份 46B / 35B）列出的 usage 全是
 * `0xB0/0xB1/0xB3/0xB4/0xB5/0xB6/0xB9/0xBC/0xCD`，**没有 Voice Command**。
 *
 * 真正的语音键是 AirPlay 事件通道命令 `requestSiri`：
 *
 * ```
 * [08:52:12.092] EVENT: 命令 type=requestSiri params={siriAction=1}
 * [08:52:12.643] EVENT: 命令 type=requestSiri params={siriAction=2}
 * [08:52:16.512] EVENT: 命令 type=requestSiri params={siriAction=3}
 * [08:52:19.076] EVENT: 命令 type=requestSiri params={siriAction=2}
 * [08:52:22.766] EVENT: 命令 type=requestSiri params={siriAction=3}
 * ...
 * ```
 *
 * `siriAction` 的取值不是猜的，来自 Apple `AirPlayCommon.h` 的 `AirPlaySiriAction`：
 * `0=NotApplicable / 1=Prewarm（预热）/ 2=ButtonDown / 3=ButtonUp`。
 * 日志形态也对得上：只有第一次按键前有 `1`，之后每次按下都是 `2`、松开都是 `3`。
 *
 * ## 收尾（VR_STOP）为什么要按「按住时长」分开
 *
 * `ButtonUp` 只是**物理按键状态**，不等于「用户要结束识别」。CarPlay 里用户常常
 * 按住方向盘语音键等提示音、松手后才开始说话；这时若松手就发 `KEYCODE_VR_STOP(0x22)`，
 * 会把刚起来的识别当场掐掉。反过来，按住说话（PTT）的用法又确实需要松手收尾。
 *
 * 所以：
 * - **短按/按住不到 [PTT_HOLD_MILLIS]**：只发 `VR_START`，收尾交给 CarLife+ 自己
 *   （静音 / 超时 / 用户点取消），不发 `VR_STOP`；
 * - **长按达到阈值**：松手时补发 `VR_STOP`，按 PTT 语义收尾。
 *
 * 实测日志里的按住时长 0.89s / 2.15s / 3.69s / 3.87s，阈值取 1.5s 正好把
 * 「等提示音就松手」和「按住说一句话」分开。这个值只影响收尾，不影响起识别。
 *
 * 所有下沉到 CarLife 控制通道的动作都由外部 `send` 回调决定在哪个线程执行
 * （SessionController 用 `carlife-cmd` 单线程），本类只做纯状态判定、不碰 socket。
 */
class VoiceKeyRouter(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val send: (Int) -> Unit,
    private val log: (String) -> Unit = {},
) {
    companion object {
        /** Apple AirPlaySiriAction：预热，不是按键，别起识别。 */
        const val SIRI_ACTION_PREWARM = 1

        /** Apple AirPlaySiriAction：语音键按下。 */
        const val SIRI_ACTION_BUTTON_DOWN = 2

        /** Apple AirPlaySiriAction：语音键松开。 */
        const val SIRI_ACTION_BUTTON_UP = 3

        /** 按住超过这个时长才按 PTT 语义在松手时补 VR_STOP。 */
        const val PTT_HOLD_MILLIS = 1_500L

        /** 超过这个时长还没等到松开就当「丢了释放帧」，状态作废，不补发 VR_STOP。 */
        const val STALE_PRESS_MILLIS = 15_000L
    }

    /** 本次按下的时刻（0 = 当前没有按下状态）。 */
    private var downAt = 0L

    /** 本次按下来自哪条通路，只用于日志。 */
    private var downSource = ""

    /** 本次按下是否已经发过 VR_START。 */
    private var startSent = false

    /** AirPlay 事件通道 `requestSiri` 入口。 */
    @Synchronized
    fun onSiriAction(action: Int?) {
        when (action) {
            SIRI_ACTION_PREWARM ->
                log("EVENT: 车机语音键 requestSiri Prewarm（仅预热，不触发 CarLife）")
            SIRI_ACTION_BUTTON_DOWN -> down("requestSiri")
            SIRI_ACTION_BUTTON_UP -> up("requestSiri")
            else -> log("EVENT: 车机语音键 requestSiri siriAction=$action（未定义，忽略）")
        }
    }

    /**
     * HID Consumer `0xCF` 通路（iAP2 `0x6802` 或事件通道 `hidSendReport`）的语音键按下。
     *
     * 只有少数车机走这条路，保留兜底：别的车型也可能真的用 0xCF 送 Voice Command。
     */
    @Synchronized
    fun onHidDown(source: String = "0xCF") = down(source)

    /** 同上，松开。 */
    @Synchronized
    fun onHidUp(source: String = "0xCF") = up(source)

    /** 新一轮会话：别把上一轮没收到释放帧的按下状态带过来。 */
    @Synchronized
    fun reset() {
        downAt = 0L
        downSource = ""
        startSent = false
    }

    private fun down(source: String) {
        val now = nowMillis()
        if (startSent && now - downAt < STALE_PRESS_MILLIS) {
            // HID 报告是状态式的，按住时 ~10Hz 连推同一个位图；requestSiri 也可能
            // 重复 ButtonDown。只认边沿，否则一次长按会连发几十条 VR_START。
            return
        }
        downAt = now
        downSource = source
        startSent = true
        send(CarLifeProtocol.HardKey.VR_START)
        log("EVENT: 车机语音键（$source 按下）→ CarLife 0x68008 keycode=0x21 VR_START")
    }

    private fun up(source: String) {
        if (!startSent) return
        val held = nowMillis() - downAt
        val pressSource = downSource
        startSent = false
        downAt = 0L
        if (held >= STALE_PRESS_MILLIS) {
            // 释放帧丢了太久，不知道用户什么时候松的手：不猜，直接作废。
            log("EVENT: 车机语音键（$pressSource 释放超时 ${held}ms）→ 作废，不补 VR_STOP")
            return
        }
        if (held >= PTT_HOLD_MILLIS) {
            send(CarLifeProtocol.HardKey.VR_STOP)
            log(
                "EVENT: 车机语音键（$pressSource 按下→$source 松开，按住 ${held}ms）" +
                    "→ CarLife 0x68008 keycode=0x22 VR_STOP（PTT 收尾）",
            )
        } else {
            log(
                "EVENT: 车机语音键（$pressSource 按下→$source 松开，短按 ${held}ms）" +
                    "→ 不发 VR_STOP，交给 CarLife+ 自己结束",
            )
        }
    }
}
