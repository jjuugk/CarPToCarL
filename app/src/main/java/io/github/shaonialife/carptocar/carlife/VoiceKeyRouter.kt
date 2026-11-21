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
 * ## 三连击 = 重启音频（1.2.64）
 *
 * 短按三次（每次 ≤ [GESTURE_TAP_MAX_HOLD_MILLIS]，总跨度 ≤ [GESTURE_WINDOW_MILLIS]）
 * 判定为「重启音频」手势，交给上层做真正的事。两条纪律：
 *
 * - **按住说话不算点击**：PTT 的松开必须把连击计数清掉，否则「说一句 + 点两下」
 *   会被凑成三连击；
 * - **手势成立时补一条 VR_STOP**：前两下已经让引擎起了识别、车机也会借走主音频，
 *   带着这个借用去重建音频等于一边建一边被掐。补一条收尾让车机立刻归还。
 *
 * 全部下沉到 CarLife 控制通道的动作都由外部 `send` 回调决定在哪个线程执行
 * （SessionController 用 `carlife-cmd` 单线程），本类只做纯状态判定、不碰 socket。
 *
 * ## 2026-11-21 之后：起识别改走 `0x10066`
 *
 * 实车证据表明 `0x68008 KEYCODE_VR_START(0x21)` 在 CarLife+ 8.8.5 上**没有任何效果**
 * （发出后引擎 8.5 秒到 25 分钟都不报 `语音=1`）。所以 `send(VR_START)` 的载荷虽然还是
 * 这个键码语义，实际下发换成了 `MSG_CMD_HU_VOICE_CONTROL (0x10066)`——映射在
 * [CarLifeHijack.sendVoiceKey]，本类不关心协议细节。`VR_STOP` 仍走 `0x68008`。
 */
class VoiceKeyRouter(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val send: (Int) -> Unit,
    private val log: (String) -> Unit = {},
    /** 「三连击重启音频」手势是否启用（UI 开关，见 [VoiceKeyGesture]）。 */
    private val gestureEnabled: () -> Boolean = { false },
    /** 三连击成立：重启音频。由上层决定在哪个线程执行。 */
    private val onTripleTap: () -> Unit = {},
) {
    companion object {
        /** 三连击需要几次点击。 */
        const val GESTURE_TAPS = 3

        /**
         * 单次「点击」的最长按住时长。
         *
         * 语音键的主用途是按住说话（实车按住时长 0.89s / 2.15s / 3.69s / 3.87s），
         * 那些绝不能算成连击；用户在等提示音时松手的短按大约 200~400ms，
         * 取 700ms 留足余量又不碰 PTT。
         */
        const val GESTURE_TAP_MAX_HOLD_MILLIS = 700L

        /**
         * 三连击的总窗口：**第一次按下**到**第三次松开**的跨度上限。
         *
         * 按得快的人连点三次约 0.9 秒，按得慢的约 1.6 秒；2 秒能盖住绝大多数手感，
         * 又不至于把「每隔一秒按一下」的零散按键凑成一次。
         */
        const val GESTURE_WINDOW_MILLIS = 2_000L

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

    /** 最近几次「短按」的按下时刻（最多留 [GESTURE_TAPS] 个），用于三连击判定。 */
    private val taps = ArrayDeque<Long>()

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
        taps.clear()
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
        // 1.2.78 起 VR_START 实际下发的是 `0x10066`（语音控制），不是 `0x68008 0x21`：
        // 后者在 CarLife+ 8.8.5 上是死的，见 CarLifeProtocol.HU_VOICE_CONTROL 注释。
        log("EVENT: 车机语音键（$source 按下）→ CarLife 0x10066 语音控制（起识别）")
    }

    private fun up(source: String) {
        if (!startSent) return
        val now = nowMillis()
        val pressAt = downAt
        val held = now - pressAt
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
            // 按住说话是 PTT，不属于连击：顺手把计数清掉，免得「说一句 + 点两下」
            // 被凑成三连击。
            taps.clear()
            return
        }
        log(
            "EVENT: 车机语音键（$pressSource 按下→$source 松开，短按 ${held}ms）" +
                "→ 不发 VR_STOP，交给 CarLife+ 自己结束",
        )
        noteTap(pressAt, now, held)
    }

    /**
     * 记一次「短按」，凑满 [GESTURE_TAPS] 次就判定为重启音频手势。
     *
     * 手势成立时必须**把刚发出去的 VR_START 收掉**：前两下已经让 CarLife 起了语音识别，
     * 车机也会顺势借走主音频（`BORROWED_CONSTRAINED`）——那正是「音乐被静音」的来源，
     * 带着它去重启音频等于一边重建一边被掐。补一条 `VR_STOP` 让引擎立刻收尾。
     */
    private fun noteTap(
        pressAt: Long,
        upAt: Long,
        held: Long,
    ) {
        if (!gestureEnabled()) {
            // 手势关闭时连按就是连按：保持「每一下都是一次语音识别」的原有行为。
            taps.clear()
            return
        }
        if (held > GESTURE_TAP_MAX_HOLD_MILLIS) {
            taps.clear()
            return
        }
        taps.addLast(pressAt)
        // 窗口以「这一次松开」为终点往前推：跨度超了就丢掉最早的点击。
        // 不能写成 `size > GESTURE_TAPS` 才裁——那样三次慢点击（每次隔 900ms）
        // 照样会凑成一次「三连击」，用户在等提示音时慢慢点三次就被误判成重启音频。
        while (taps.isNotEmpty() && upAt - taps.first() > GESTURE_WINDOW_MILLIS) {
            taps.removeFirst()
        }
        if (taps.size < GESTURE_TAPS) return
        taps.clear()
        send(CarLifeProtocol.HardKey.VR_STOP)
        log("EVENT: 语音键三连击 → 重启音频（已撤掉本次语音识别，车机无需再借主音频）")
        onTripleTap()
    }
}
